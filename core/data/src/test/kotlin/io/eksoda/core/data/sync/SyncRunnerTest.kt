package io.eksoda.core.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.model.SyncStart
import io.eksoda.engine.sync.HouseholdKey
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
    private lateinit var a: SyncTestPhone
    private lateinit var b: SyncTestPhone

    @Before
    fun setUp() {
        a = SyncTestPhone("phone-a", "Pixel 8", key, clock, dispatcher)
        b = SyncTestPhone("phone-b", "Galaxy", key, clock, dispatcher)
        a.db.execSQL(HOUSEHOLD.replace("ID", "hA").replace("NAME", "Σπίτι"))
        b.db.execSQL(HOUSEHOLD.replace("ID", "hB").replace("NAME", "Home"))
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
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
        assertThat(onB.startedBy).isEqualTo("peer")
        assertThat(onB.peerName).isEqualTo("Pixel 8")
        assertThat(a.database.syncDao().peer("phone-b")?.lastSuccessAt).isEqualTo(NOW)
        assertThat(b.database.syncDao().peer("phone-a")?.lastSuccessAt).isEqualTo(NOW)
        // B learned A from the connection; A keeps the address it connected to.
        assertThat(a.database.syncDao().peer("phone-b")?.lastAddress).isNotNull()
        assertThat(b.database.syncDao().peer("phone-a")?.lastAddress).isNull()
    }

    @Test
    fun `an automatic sync with a phone synced a moment ago, or not known, is skipped`() =
        runBlocking {
            sync(client = a, server = b, startedBy = SyncStart.USER)

            a.runner.connect(PeerAddress("phone-b", LOOPBACK, 1), SyncStart.AUTO)
            a.runner.connect(PeerAddress(null, LOOPBACK, 1), SyncStart.AUTO)

            assertThat(a.events().size).isEqualTo(1)
        }

    @Test
    fun `a phone of another household is refused, and only the one that tried keeps an entry`() =
        runBlocking {
            SyncTestPhone("phone-c", "Moto", HouseholdKey.create(), clock, dispatcher).use {
                sync(client = it, server = a, startedBy = SyncStart.USER)

                assertThat(it.events().single().errorCode).isEqualTo("not_paired")
                assertThat(a.events()).isEqualTo(emptyList<SyncEventEntity>())
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
    fun `an entry a killed app left running ends as interrupted`() = runBlocking {
        a.database.syncDao().insertEvent(
            SyncEventEntity("e1", null, null, "auto", 1, null, "running", null, 0, 0, 0),
        )

        a.runner.closeStaleEntries()

        val entry = a.events().single()
        assertThat(entry.result).isEqualTo("failed")
        assertThat(entry.errorCode).isEqualTo("interrupted")
    }

    @Test
    fun `with the real address rule, loopback is neither dialed nor answered`() = runBlocking {
        SyncTestPhone("phone-s", "Strict", key, clock, dispatcher, SyncAddressPolicy()).use {
            it.runner.connect(PeerAddress("phone-b", LOOPBACK, 1), SyncStart.USER)
            assertThat(it.events().single().errorCode).isEqualTo("peer_unreachable")

            sync(client = a, server = it, startedBy = SyncStart.USER)
            // Refused before a word: only the phone that tried has an entry.
            assertThat(it.events().size).isEqualTo(1)
            assertThat(a.events().single().result).isEqualTo("failed")
        }
    }

    @Test
    fun `a phone without the household key neither dials nor answers`() = runBlocking {
        SyncTestPhone("phone-k", "Keyless", key = null, clock, dispatcher).use {
            it.runner.connect(PeerAddress("phone-b", LOOPBACK, 1), SyncStart.USER)
            sync(client = a, server = it, startedBy = SyncStart.USER)
            assertThat(it.events()).isEqualTo(emptyList<SyncEventEntity>())
            assertThat(a.events().single().result).isEqualTo("failed")
        }
    }

    @Test
    fun `a connection that drops halfway is interrupted`() = runBlocking {
        listeningSocket().use { listening ->
            coroutineScope {
                // Answers the call and hangs up.
                val hangUp = async(dispatcher) { listening.accept().close() }
                a.runner.connect(
                    PeerAddress("phone-b", LOOPBACK, listening.localPort),
                    SyncStart.USER,
                )
                hangUp.await()
            }
        }
        assertThat(a.events().single().errorCode).isEqualTo("interrupted")
    }

    /** [client] connects to [server] listening on loopback. */
    private suspend fun sync(client: SyncTestPhone, server: SyncTestPhone, startedBy: SyncStart) {
        listeningSocket().use { listening ->
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

    private companion object {
        const val NOW = 1_763_300_000_000L
        const val THREADS = 4
        const val HOUSEHOLD = "INSERT INTO household (id, name, default_currency, timezone, " +
            "created_at, updated_at) VALUES ('ID', 'NAME', 'EUR', 'Europe/Athens', 1, 1)"
    }
}
