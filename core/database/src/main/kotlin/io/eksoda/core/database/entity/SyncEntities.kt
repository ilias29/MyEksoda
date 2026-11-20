package io.eksoda.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Household sync (docs/design/household-sync.md). Both tables belong to this phone only: they
// are never sent to another phone and never written to a backup file (BackupArchive.LOCAL_TABLES).

/** A phone of the household that this phone syncs with. */
@Entity(tableName = "sync_peer")
data class SyncPeerEntity(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    val name: String,
    @ColumnInfo(name = "paired_at") val pairedAt: Long,
    /** Where it was last reached on the local network ("192.168.1.20:41234"). */
    @ColumnInfo(name = "last_address") val lastAddress: String?,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long?,
)

/**
 * One sync attempt, for the history on the Sync screen. It keeps the phone's name as it was, so
 * the entry still reads right after the phone is forgotten (no foreign key to `sync_peer`).
 */
@Entity(tableName = "sync_event", indices = [Index(value = ["started_at", "id"])])
data class SyncEventEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "peer_device_id") val peerDeviceId: String?,
    @ColumnInfo(name = "peer_name") val peerName: String?,
    /** auto | user */
    @ColumnInfo(name = "started_by") val startedBy: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long?,
    /** running | done | failed */
    val result: String,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    /** Rows this phone added, changed and deleted when merging what it received. */
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
)
