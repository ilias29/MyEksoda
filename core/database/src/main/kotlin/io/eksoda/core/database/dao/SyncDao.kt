package io.eksoda.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.database.entity.SyncPeerEntity
import kotlinx.coroutines.flow.Flow

/** The paired phones and the sync history, both local to this phone. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_peer ORDER BY name, device_id")
    fun observePeers(): Flow<List<SyncPeerEntity>>

    @Query("SELECT * FROM sync_peer WHERE device_id = :deviceId")
    suspend fun peer(deviceId: String): SyncPeerEntity?

    @Query("SELECT * FROM sync_peer")
    suspend fun peerList(): List<SyncPeerEntity>

    @Upsert
    suspend fun upsertPeer(peer: SyncPeerEntity)

    /** Leaving household sync, or joining another household. */
    @Query("DELETE FROM sync_peer")
    suspend fun deletePeers()

    /** The history newest first, walking the `(started_at, id)` index backwards. */
    @Query("SELECT * FROM sync_event ORDER BY started_at DESC, id DESC")
    fun pagingEvents(): PagingSource<Int, SyncEventEntity>

    @Insert
    suspend fun insertEvent(event: SyncEventEntity)

    @Update
    suspend fun updateEvent(event: SyncEventEntity)

    /** Entries a killed app left running: they ended, unfinished. */
    @Query(
        """UPDATE sync_event SET result = 'failed', error_code = :code, finished_at = :now
           WHERE result = 'running'""",
    )
    suspend fun failRunning(code: String, now: Long)

    /** Keeps the newest [keep] entries of the history. */
    @Query(
        """DELETE FROM sync_event WHERE id NOT IN
           (SELECT id FROM sync_event ORDER BY started_at DESC, id DESC LIMIT :keep)""",
    )
    suspend fun pruneEvents(keep: Int)

    /** When the last sync that went through finished; null before the first one. */
    @Query("SELECT MAX(finished_at) FROM sync_event WHERE result = 'done'")
    fun observeLastSuccess(): Flow<Long?>
}
