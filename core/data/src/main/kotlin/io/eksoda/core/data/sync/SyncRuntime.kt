package io.eksoda.core.data.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.eksoda.core.data.di.ApplicationScope
import io.eksoda.core.data.di.IoDispatcher
import io.eksoda.core.database.dao.SyncDao
import io.eksoda.core.model.SyncStart
import io.eksoda.core.model.SyncStatus
import io.eksoda.engine.sync.HouseholdKey
import io.eksoda.engine.sync.LocalNetwork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

/** The household sync preference, shared by the repository and the runtime. */
internal object SyncPreferences {
    val ENABLED = booleanPreferencesKey("household_sync_enabled")
}

/** What the repository asks of the running sync (the runtime; tests use a fake). */
interface SyncControl {
    val status: Flow<SyncStatus>

    /** Syncs with every household phone it can reach, now; the outcome goes to the history. */
    suspend fun syncNow()

    /** [syncNow] in the background: the first sync after joining. */
    fun syncSoon()

    /** Where this phone listens now, for its pairing code, or null. */
    fun address(): String?
}

/**
 * Household sync while the app is open (docs/design/household-sync.md, "Discovery and
 * triggers"): when the app is in the foreground, sync is on and this phone holds a household
 * key, it listens on a TCP port, announces itself and looks for the household's other phones.
 * A phone found is synced with automatically (the one with the smaller device id starts, at
 * most every 15 minutes); "Sync now" syncs with every phone found. When any of the three
 * stops, the socket closes and nothing is announced.
 */
@Singleton
class SyncRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: DataStore<Preferences>,
    private val device: SyncDevice,
    private val runner: SyncRunner,
    private val discovery: SyncDiscovery,
    private val sync: SyncDao,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SyncControl {
    private val listeningPort = MutableStateFlow<Int?>(null)
    private val found = MutableStateFlow<Map<String, PeerAddress>>(emptyMap())

    override val status: Flow<SyncStatus> =
        combine(listeningPort, found, runner.syncing) { port, peers, syncing ->
            SyncStatus(listening = port != null, phonesFound = peers.size, syncing = syncing)
        }

    /** Called once, at app start. */
    fun start() {
        scope.launch {
            val enabled = preferences.data.map { it[SyncPreferences.ENABLED] == true }
            combine(foreground(), enabled, device.hasKey) { open, on, key -> open && on && key }
                .distinctUntilChanged()
                .collectLatest { active -> if (active) listen() }
        }
    }

    /** "Sync now": with every household phone found here, else where each was last seen. */
    override suspend fun syncNow() = withContext(io) {
        val targets = found.value.values.toList().ifEmpty {
            sync.peerList().mapNotNull { peer ->
                peer.lastAddress?.let { PeerAddress.parse(peer.deviceId, it) }
            }
        }
        if (targets.isEmpty()) runner.nobodyToSync()
        targets.forEach { runner.connect(it, SyncStart.USER) }
    }

    override fun syncSoon() {
        scope.launch { syncNow() }
    }

    /**
     * Where this phone listens, for its pairing code ("192.168.1.20:41234"): its local IPv4
     * address on the current network, or null when it is not listening or has none.
     */
    override fun address(): String? {
        val port = listeningPort.value ?: return null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val host = manager.getLinkProperties(manager.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && LocalNetwork.isLocal(it) }
            ?: return null
        return PeerAddress(null, host, port).text()
    }

    private fun foreground(): Flow<Boolean> = ProcessLifecycleOwner.get().lifecycle
        .currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }

    /** Runs until cancelled (the app goes to the background, sync goes off, the key goes). */
    private suspend fun listen() = withContext(io) {
        val key = device.key() ?: return@withContext
        val tag = HouseholdKey.tag(key)
        val server = try {
            ServerSocket(0)
        } catch (ignored: IOException) {
            return@withContext
        }
        try {
            coroutineScope {
                val tasks = this
                listeningPort.value = server.localPort
                // Closing the socket ends the blocking accept below.
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        server.close()
                    }
                }
                launch { acceptAll(server) }
                launch { discovery.announce(server.localPort, device.deviceId, tag) }
                discovery.peers(tag, device.deviceId).collect { peers ->
                    found.value = peers
                    peers.values.forEach { peer -> tasks.launch { autoSync(peer) } }
                }
            }
        } finally {
            server.close()
            listeningPort.value = null
            found.value = emptyMap()
        }
    }

    private suspend fun acceptAll(server: ServerSocket) = coroutineScope {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (ignored: IOException) {
                // The socket was closed: the app went to the background or sync went off.
                break
            }
            launch { runner.serve(socket) }
        }
    }

    /** Both phones find each other: only the one with the smaller id starts the sync. */
    private suspend fun autoSync(peer: PeerAddress) {
        val other = peer.deviceId ?: return
        if (device.deviceId < other) runner.connect(peer, SyncStart.AUTO)
    }
}
