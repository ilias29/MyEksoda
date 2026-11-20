package io.eksoda.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.map
import io.eksoda.core.data.di.IoDispatcher
import io.eksoda.core.data.sync.SyncControl
import io.eksoda.core.data.sync.SyncDevice
import io.eksoda.core.data.sync.SyncPreferences
import io.eksoda.core.database.dao.SyncDao
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.database.entity.SyncPeerEntity
import io.eksoda.core.model.SyncEvent
import io.eksoda.core.model.SyncJoinResult
import io.eksoda.core.model.SyncPeer
import io.eksoda.core.model.SyncResult
import io.eksoda.core.model.SyncStart
import io.eksoda.core.model.SyncStatus
import io.eksoda.engine.sync.PairingCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Household sync between phones on the same Wi-Fi (docs/design/household-sync.md): the on/off
 * switch, the paired phones and the sync history. Everything here is local to this phone.
 */
interface SyncRepository {
    /** Off by default. While off, the app opens no socket and announces nothing. */
    val enabled: Flow<Boolean>

    /** Turning sync on the first time makes the household key, unless this phone joined one. */
    suspend fun setEnabled(enabled: Boolean)

    /** Whether this phone holds a household key (it made one, or joined a household). */
    val hasHouseholdKey: Flow<Boolean>

    /**
     * The code another phone scans to join this household (QR text, `PairingCode`). It holds
     * the household key: show it only while asked for, and never keep it.
     */
    suspend fun pairingCode(): Result<String>

    /** Joins the household of the phone whose code [text] is, and turns sync on. */
    suspend fun join(text: String): SyncJoinResult

    /** Forgets the household key and the paired phones and turns sync off; the data stays. */
    suspend fun leave(): Result<Unit>

    /** The paired phones, by name. */
    val peers: Flow<List<SyncPeer>>

    /** The sync history, newest first, paged. */
    fun getHistory(): Flow<PagingData<SyncEvent>>

    /** When the last sync that went through finished; null before the first one. */
    val lastSuccess: Flow<Instant?>

    /** Listening, phones found, syncing: while the app is open. */
    val status: Flow<SyncStatus>

    /** "Sync now": with every household phone it can reach; the outcome goes to the history. */
    suspend fun syncNow()
}

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val preferences: DataStore<Preferences>,
    private val sync: SyncDao,
    private val device: SyncDevice,
    private val control: SyncControl,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) : SyncRepository {
    override val enabled: Flow<Boolean> =
        preferences.data.map { it[SyncPreferences.ENABLED] == true }

    override suspend fun setEnabled(enabled: Boolean) {
        if (enabled) withContext(io) { device.keyOrCreate() }
        preferences.edit { it[SyncPreferences.ENABLED] = enabled }
    }

    override val status: Flow<SyncStatus> get() = control.status

    override suspend fun syncNow() = control.syncNow()

    override val hasHouseholdKey: Flow<Boolean> get() = device.hasKey

    override suspend fun pairingCode(): Result<String> = withContext(io) {
        runCatching {
            val key = device.keyOrCreate()
            PairingCode(key, device.deviceId, device.deviceName, control.address()).encode()
        }
    }

    /**
     * The other phone becomes a paired phone at once (the code names it). Another household's
     * key replaces this phone's, and the phones paired with the old one are forgotten.
     */
    override suspend fun join(text: String): SyncJoinResult {
        val code = PairingCode.decode(text.trim()) ?: return SyncJoinResult.NotACode
        return withContext(io) {
            if (code.deviceId == device.deviceId) return@withContext SyncJoinResult.OwnCode
            runCatching {
                if (device.key()?.contentEquals(code.key) != true) {
                    device.replaceKey(code.key)
                    sync.deletePeers()
                }
                val known = sync.peer(code.deviceId)
                sync.upsertPeer(
                    SyncPeerEntity(
                        deviceId = code.deviceId,
                        name = code.deviceName.ifBlank { known?.name.orEmpty() },
                        pairedAt = known?.pairedAt ?: clock.millis(),
                        lastAddress = code.address ?: known?.lastAddress,
                        lastSeenAt = known?.lastSeenAt,
                        lastSuccessAt = known?.lastSuccessAt,
                    ),
                )
                preferences.edit { it[SyncPreferences.ENABLED] = true }
                // The first sync, through the address in the code if the phone is not found.
                control.syncSoon()
                SyncJoinResult.Joined(code.deviceName)
            }.getOrElse { SyncJoinResult.Failed }
        }
    }

    override suspend fun leave(): Result<Unit> = withContext(io) {
        runCatching {
            preferences.edit { it[SyncPreferences.ENABLED] = false }
            device.forgetKey()
            sync.deletePeers()
        }
    }

    override val peers: Flow<List<SyncPeer>> =
        sync.observePeers().map { peers -> peers.map { it.toModel() } }

    override fun getHistory(): Flow<PagingData<SyncEvent>> =
        Pager(listPagingConfig(PAGE_SIZE)) { sync.pagingEvents() }.flow
            .map { page -> page.map { it.toModel() } }

    override val lastSuccess: Flow<Instant?> =
        sync.observeLastSuccess().map { millis -> millis?.let(Instant::ofEpochMilli) }

    private fun SyncPeerEntity.toModel() = SyncPeer(
        deviceId = deviceId,
        name = name,
        pairedAt = Instant.ofEpochMilli(pairedAt),
        lastSeenAt = lastSeenAt?.let(Instant::ofEpochMilli),
        lastSuccessAt = lastSuccessAt?.let(Instant::ofEpochMilli),
    )

    private fun SyncEventEntity.toModel() = SyncEvent(
        id = id,
        peerName = peerName,
        startedBy = SyncStart.entries.first { it.code == startedBy },
        startedAt = Instant.ofEpochMilli(startedAt),
        finishedAt = finishedAt?.let(Instant::ofEpochMilli),
        result = SyncResult.entries.first { it.code == result },
        errorCode = errorCode,
        inserted = inserted,
        updated = updated,
        deleted = deleted,
    )

    private companion object {
        const val PAGE_SIZE = 30
    }
}
