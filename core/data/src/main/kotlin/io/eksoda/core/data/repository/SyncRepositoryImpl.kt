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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

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

    override val hasHouseholdKey: Flow<Boolean>
        get() = device.keyVersion.map { it != null }.distinctUntilChanged()

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
