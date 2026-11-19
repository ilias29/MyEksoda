package io.eksoda.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.map
import io.eksoda.core.database.dao.SyncDao
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.database.entity.SyncPeerEntity
import io.eksoda.core.model.SyncEvent
import io.eksoda.core.model.SyncPeer
import io.eksoda.core.model.SyncResult
import io.eksoda.core.model.SyncStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    suspend fun setEnabled(enabled: Boolean)

    /** The paired phones, by name. */
    val peers: Flow<List<SyncPeer>>

    /** The sync history, newest first, paged. */
    fun getHistory(): Flow<PagingData<SyncEvent>>

    /** When the last sync that went through finished; null before the first one. */
    val lastSuccess: Flow<Instant?>
}

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val preferences: DataStore<Preferences>,
    private val sync: SyncDao,
) : SyncRepository {
    override val enabled: Flow<Boolean> = preferences.data.map { it[ENABLED_KEY] == true }

    override suspend fun setEnabled(enabled: Boolean) {
        preferences.edit { it[ENABLED_KEY] = enabled }
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
        val ENABLED_KEY = booleanPreferencesKey("household_sync_enabled")
        const val PAGE_SIZE = 30
    }
}
