package io.eksoda.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.eksoda.core.data.db.WriteCoordinator
import io.eksoda.core.database.EksodaDatabase
import io.eksoda.core.database.entity.SyncEventEntity
import kotlinx.coroutines.CoroutineDispatcher
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Clock

/** Lets loopback through, for phones syncing on this machine. */
internal val ANY_ADDRESS = object : SyncAddressPolicy() {
    override fun allows(address: InetAddress) = true
}

/** This machine's IPv4 loopback, where the test phones listen. */
internal val LOOPBACK = InetAddress.getByName("127.0.0.1") as Inet4Address

/** How long a test waits for a phone to call: a regression fails the test instead of hanging. */
internal const val ACCEPT_TIMEOUT_MILLIS = 10_000

/** A socket on loopback for a test phone to call, that gives up after [ACCEPT_TIMEOUT_MILLIS]. */
internal fun listeningSocket(): ServerSocket =
    ServerSocket(0, 1, LOOPBACK).apply { soTimeout = ACCEPT_TIMEOUT_MILLIS }

/**
 * A phone in household sync on this machine: an in-memory database, a [FakeSyncDevice] holding
 * [key] (null: none) and a runner. [close] closes its database.
 */
internal class SyncTestPhone(
    id: String,
    name: String,
    key: ByteArray?,
    clock: Clock,
    io: CoroutineDispatcher,
    policy: SyncAddressPolicy = ANY_ADDRESS,
) : AutoCloseable {
    val database: EksodaDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        EksodaDatabase::class.java,
    ).allowMainThreadQueries().build()
    val db get() = database.openHelper.writableDatabase
    val device = FakeSyncDevice(id, name, key)
    val runner = SyncRunner(
        device = device,
        merger = SyncMerger(database, WriteCoordinator(database), clock),
        sync = database.syncDao(),
        policy = policy,
        clock = clock,
        io = io,
    )

    /** The history, oldest first. */
    fun events(): List<SyncEventEntity> =
        db.query("SELECT id FROM sync_event ORDER BY started_at, id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.map { id -> eventRow(id) }

    /** "started by:result:error" of every entry in the history, oldest first. */
    fun history(): List<String> =
        events().map { "${it.startedBy}:${it.result}:${it.errorCode.orEmpty()}" }

    /** "id:name" of every household row. */
    fun names(): List<String> = db.query("SELECT id, name FROM household").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add("${cursor.getString(0)}:${cursor.getString(1)}")
        }
    }

    override fun close() = database.close()

    private fun eventRow(id: String): SyncEventEntity =
        db.query("SELECT * FROM sync_event WHERE id = ?", arrayOf(id)).use { c ->
            c.moveToFirst()
            SyncEventEntity(
                id = id,
                peerDeviceId = c.getString(c.getColumnIndexOrThrow("peer_device_id")),
                peerName = c.getString(c.getColumnIndexOrThrow("peer_name")),
                startedBy = c.getString(c.getColumnIndexOrThrow("started_by")),
                startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
                finishedAt = c.getLong(c.getColumnIndexOrThrow("finished_at")),
                result = c.getString(c.getColumnIndexOrThrow("result")),
                errorCode = c.getString(c.getColumnIndexOrThrow("error_code")),
                inserted = c.getInt(c.getColumnIndexOrThrow("inserted")),
                updated = c.getInt(c.getColumnIndexOrThrow("updated")),
                deleted = c.getInt(c.getColumnIndexOrThrow("deleted")),
            )
        }
}
