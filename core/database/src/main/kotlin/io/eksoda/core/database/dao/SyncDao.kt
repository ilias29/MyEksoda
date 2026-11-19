package io.eksoda.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.database.entity.SyncPeerEntity
import kotlinx.coroutines.flow.Flow

/** The paired phones and the sync history, both local to this phone. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_peer ORDER BY name, device_id")
    fun observePeers(): Flow<List<SyncPeerEntity>>

    @Upsert
    suspend fun upsertPeer(peer: SyncPeerEntity)

    /** The history newest first, walking the `(started_at, id)` index backwards. */
    @Query("SELECT * FROM sync_event ORDER BY started_at DESC, id DESC")
    fun pagingEvents(): PagingSource<Int, SyncEventEntity>

    @Insert
    suspend fun insertEvent(event: SyncEventEntity)

    /** When the last sync that went through finished; null before the first one. */
    @Query("SELECT MAX(finished_at) FROM sync_event WHERE result = 'done'")
    fun observeLastSuccess(): Flow<Long?>
}
