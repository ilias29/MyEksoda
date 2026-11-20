package io.eksoda.core.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.eksoda.core.data.di.ApplicationScope
import io.eksoda.core.data.di.IoDispatcher
import io.eksoda.core.database.dao.SyncDao
import io.eksoda.core.model.SyncStart
import io.eksoda.core.model.SyncStatus
import io.eksoda.engine.sync.HouseholdKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
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
 * triggers"): when the app is in the foreground, sync is on, this phone holds a household key
 * and it is on Wi-Fi (or Ethernet), it listens on that network only, announces itself and
 * looks for the household's other phones. A phone that appears is synced with automatically
 * (the one with the smaller device id starts, at most every 15 minutes); "Sync now" syncs
 * with every paired phone it can reach. When any condition changes (the key too: joining
 * another household), it starts over; when one stops, the socket closes and nothing is
 * announced.
 */
@Singleton
class SyncRuntime @Inject constructor(
    private val preferences: DataStore<Preferences>,
    private val device: SyncDevice,
    private val runner: SyncRunner,
    private val discovery: SyncDiscovery,
    private val sync: SyncDao,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SyncControl {
    private val listening = MutableStateFlow<InetSocketAddress?>(null)
    private val found = MutableStateFlow<Map<String, PeerAddress>>(emptyMap())

    override val status: Flow<SyncStatus> =
        combine(listening, found, runner.syncing) { address, peers, syncing ->
            SyncStatus(listening = address != null, phonesFound = peers.size, syncing = syncing)
        }

    /** Called once, at app start. */
    fun start() {
        scope.launch {
            runCatching { runner.closeStaleEntries() }
            val enabled = preferences.data.map { it[SyncPreferences.ENABLED] == true }
            combine(
                foreground(),
                enabled,
                device.keyVersion,
                discovery.localAddress(),
            ) { open, on, key, address ->
                val wanted = open && on
                if (wanted && key != null && address != null) Target(key, address) else null
            }
                .distinctUntilChanged()
                .collectLatest { target -> if (target != null) listenGuarded(target.address) }
        }
    }

    /**
     * "Sync now": with every household phone found here, and with every other paired phone
     * where it was last seen (discovery may be blocked where the phone is not); with none, the
     * history says so.
     */
    override suspend fun syncNow() = withContext(io) {
        // Switched off meanwhile (or left the household after joining): no socket at all.
        if (preferences.data.first()[SyncPreferences.ENABLED] != true) return@withContext
        val discovered = found.value
        val elsewhere = runCatching { sync.peerList() }.getOrDefault(emptyList())
            .filter { it.deviceId !in discovered }
            .mapNotNull { peer -> peer.lastAddress?.let { PeerAddress.parse(peer.deviceId, it) } }
        val targets = discovered.values + elsewhere
        if (targets.isEmpty()) runner.nobodyToSync()
        targets.forEach { runner.connect(it, SyncStart.USER) }
    }

    override fun syncSoon() {
        scope.launch { syncNow() }
    }

    /** Where this phone listens, for its pairing code ("192.168.1.20:41234"), or null. */
    override fun address(): String? {
        val address = listening.value ?: return null
        return PeerAddress(null, address.address, address.port).text()
    }

    private fun foreground(): Flow<Boolean> = ProcessLifecycleOwner.get().lifecycle
        .currentStateFlow
        .map { it.isAtLeast(Lifecycle.State.STARTED) }

    /** Nothing listening throws may stop sync for good: the next change starts it again. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun listenGuarded(address: Inet4Address) {
        try {
            listen(address)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Sync stays off on this network until the app returns to the foreground.
        }
    }

    /** Runs until cancelled (the app goes to the background, sync goes off, the key goes). */
    private suspend fun listen(address: Inet4Address) = withContext(io) {
        val key = runCatching { device.key() }.getOrNull() ?: return@withContext
        val tag = HouseholdKey.tag(key)
        val server = try {
            ServerSocket(0, BACKLOG, address)
        } catch (ignored: IOException) {
            return@withContext
        }
        try {
            coroutineScope {
                val tasks = this
                listening.value = InetSocketAddress(address, server.localPort)
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
                    // Only a phone that just appeared starts an automatic sync.
                    val appeared = peers.keys - found.value.keys
                    found.value = peers
                    appeared.mapNotNull(peers::get).forEach { tasks.launch { autoSync(it) } }
                }
            }
        } finally {
            server.close()
            listening.value = null
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

    /** What listening depends on: the key's version and this phone's Wi-Fi address. */
    private data class Target(val keyVersion: Int, val address: Inet4Address)

    private companion object {
        const val BACKLOG = 4
    }
}
