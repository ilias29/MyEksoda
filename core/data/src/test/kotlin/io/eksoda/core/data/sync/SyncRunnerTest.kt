package io.eksoda.core.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.eksoda.core.data.db.WriteCoordinator
import io.eksoda.core.database.EksodaDatabase
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.model.SyncStart
import io.eksoda.engine.sync.HouseholdKey
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors

/**
 * Two phones (in-memory databases) syncing over a real TCP connection on this machine. The
 * address rule lets loopback through here only.
 */
@RunWith(AndroidJUnit4::class)
class SyncRunnerTest {
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)

    /** Real threads: each phone blocks on its socket while the other answers. */
    private val threads = Executors.newFixedThreadPool(THREADS)
    private val dispatcher = threads.asCoroutineDispatcher()
    private val key = HouseholdKey.create()
    private lateinit var a: Phone
    private lateinit var b: Phone

    @Before
    fun setUp() {
        a = Phone("phone-a", "Pixel 8", key)
        b = Phone("phone-b", "Galaxy", key)
        a.db.execSQL(HOUSEHOLD.replace("ID", "hA").replace("NAME", "Σπίτι"))
        b.db.execSQL(HOUSEHOLD.replace("ID", "hB").replace("NAME", "Home"))
    }

    @After
    fun tearDown() {
        a.database.close()
        b.database.close()
        threads.shutdown()
    }

    @Test
    fun `a sync pairs the phones, merges both and leaves an entry on each`() = runBlocking {
        sync(client = a, server = b, startedBy = SyncStart.USER)

        assertThat(a.names()).isEqualTo(b.names())
        val onA = a.events().single()
        val onB = b.events().single()
        assertThat(onA.result).isEqualTo("done")
        assertThat(onA.startedBy).isEqualTo("user")
        assertThat(onA.peerName).isEqualTo("Galaxy")
        assertThat(onB.result).isEqualTo("done")
        assertThat(onB.startedBy).isEqualTo("auto")
        assertThat(onB.peerName).isEqualTo("Pixel 8")
        assertThat(a.database.syncDao().peer("phone-b")?.lastSuccessAt).isEqualTo(NOW)
        assertThat(b.database.syncDao().peer("phone-a")?.lastSuccessAt).isEqualTo(NOW)
        // B learned A from the connection; A keeps the address it connected to.
        assertThat(a.database.syncDao().peer("phone-b")?.lastAddress).isNotNull()
        assertThat(b.database.syncDao().peer("phone-a")?.lastAddress).isNull()
    }

    @Test
    fun `an automatic sync with a phone synced a moment ago is skipped`() = runBlocking {
        sync(client = a, server = b, startedBy = SyncStart.USER)

        a.runner.connect(PeerAddress("phone-b", LOOPBACK, 1), SyncStart.AUTO)

        assertThat(a.events().size).isEqualTo(1)
    }

    @Test
    fun `a phone of another household is refused, and only the one that tried keeps an entry`() =
        runBlocking {
            val stranger = Phone("phone-c", "Moto", HouseholdKey.create())
            try {
                sync(client = stranger, server = a, startedBy = SyncStart.USER)

                assertThat(stranger.events().single().errorCode).isEqualTo("not_paired")
                assertThat(a.events()).isEqualTo(emptyList<SyncEventEntity>())
            } finally {
                stranger.database.close()
            }
        }

    @Test
    fun `a phone that does not answer is unreachable`() = runBlocking {
        val closed = ServerSocket(0, 1, LOOPBACK).use { it.localPort }

        a.runner.connect(PeerAddress("phone-b", LOOPBACK, closed), SyncStart.USER)
        a.runner.nobodyToSync()

        assertThat(a.events().map { it.errorCode })
            .containsExactly("peer_unreachable", "peer_unreachable")
    }

    @Test
    fun `addresses are written and read back as stored`() {
        val v4 = PeerAddress.parse("d", "192.168.1.20:41234")
        val v6 = PeerAddress.parse("d", "[fe80::1]:41234")

        assertThat(v4?.text()).isEqualTo("192.168.1.20:41234")
        assertThat(v6?.text()).isEqualTo("[fe80:0:0:0:0:0:0:1]:41234")
        assertThat(PeerAddress.parse("d", "phone.local:41234")).isNull()
        assertThat(PeerAddress.parse("d", "192.168.1.20:99999")).isNull()
        assertThat(PeerAddress.parse("d", "192.168.1.20")).isNull()
    }

    /** [client] connects to [server] listening on loopback. */
    private suspend fun sync(client: Phone, server: Phone, startedBy: SyncStart) {
        ServerSocket(0, 1, LOOPBACK).use { listening ->
            coroutineScope {
                val serving = async(dispatcher) { server.runner.serve(listening.accept()) }
                client.runner.connect(
                    PeerAddress(server.device.deviceId, LOOPBACK, listening.localPort),
                    startedBy,
                )
                serving.await()
            }
        }
    }

    private inner class Phone(id: String, name: String, key: ByteArray) {
        val database: EksodaDatabase = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EksodaDatabase::class.java,
        ).allowMainThreadQueries().build()
        val db get() = database.openHelper.writableDatabase
        val device = FixedDevice(id, name, key)
        val runner = SyncRunner(
            device = device,
            merger = SyncMerger(database, WriteCoordinator(database), clock),
            sync = database.syncDao(),
            policy = object : SyncAddressPolicy() {
                override fun allows(address: InetAddress) = true
            },
            clock = clock,
            io = dispatcher,
        )

        fun events(): List<SyncEventEntity> =
            db.query("SELECT id FROM sync_event ORDER BY started_at, id").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }.map { id -> eventRow(id) }

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

        fun names(): List<String> = db.query("SELECT id, name FROM household").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add("${cursor.getString(0)}:${cursor.getString(1)}")
            }
        }
    }

    private class FixedDevice(
        override val deviceId: String,
        override val deviceName: String,
        private val key: ByteArray,
    ) : SyncDevice {
        override val hasKey: StateFlow<Boolean> = MutableStateFlow(true)

        override fun key() = key.copyOf()

        override fun keyOrCreate() = key.copyOf()

        override fun replaceKey(key: ByteArray) = Unit

        override fun forgetKey() = Unit
    }

    private companion object {
        const val NOW = 1_763_300_000_000L
        const val THREADS = 4
        val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()
        const val HOUSEHOLD = "INSERT INTO household (id, name, default_currency, timezone, " +
            "created_at, updated_at) VALUES ('ID', 'NAME', 'EUR', 'Europe/Athens', 1, 1)"
    }
}
