package io.eksoda.core.data.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.database.entity.SyncPeerEntity
import io.eksoda.core.model.SyncStart
import io.eksoda.engine.sync.HouseholdKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Household sync while the app is open: phone A runs the runtime on this machine's loopback
 * (the discovery is a fake that hands it out), phone B answers with a plain runner.
 */
@RunWith(AndroidJUnit4::class)
class SyncRuntimeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
    private val threads = Executors.newFixedThreadPool(THREADS)
    private val dispatcher = threads.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val key = HouseholdKey.create()
    private val process = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    private val discovery = FakeDiscovery()
    private lateinit var a: SyncTestPhone
    private lateinit var b: SyncTestPhone
    private lateinit var runtime: SyncRuntime
    private val preferences by lazy {
        PreferenceDataStoreFactory.create(scope = scope) {
            folder.root.resolve("settings.preferences_pb")
        }
    }

    @Before
    fun setUp() {
        a = SyncTestPhone("phone-a", "Pixel 8", key, clock, dispatcher)
        b = SyncTestPhone("phone-b", "Galaxy", key, clock, dispatcher)
        runtime = SyncRuntime(
            preferences,
            a.device,
            a.runner,
            discovery,
            a.database.syncDao(),
            scope,
            dispatcher,
        )
    }

    @After
    fun tearDown() {
        // The process lifecycle outlives the test: back to the background.
        process.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        // Everything the runtime started ends (sockets closed, entries written) before the
        // databases close, so nothing spills into the next test.
        runBlocking { withTimeout(TIMEOUT) { scope.coroutineContext.job.cancelAndJoin() } }
        threads.shutdown()
        threads.awaitTermination(TIMEOUT, TimeUnit.MILLISECONDS)
        a.close()
        b.close()
    }

    private suspend fun enable(on: Boolean) {
        preferences.edit { it[SyncPreferences.ENABLED] = on }
    }

    private fun foreground(open: Boolean) = process.handleLifecycleEvent(
        if (open) Lifecycle.Event.ON_START else Lifecycle.Event.ON_STOP,
    )

    /** The app open, sync on, on a network: A listens; the port it listens on. */
    private suspend fun listening(): Int {
        runtime.start()
        enable(true)
        discovery.address.value = LOOPBACK
        foreground(true)
        return checkNotNull(discovery.announced.until { it != null })
    }

    private suspend fun <T> Flow<T>.until(predicate: (T) -> Boolean): T =
        withTimeout(TIMEOUT) { first(predicate) }

    /** Waits for [phone]'s history to read [expected] ("started by:result:error"). */
    private suspend fun awaitHistory(phone: SyncTestPhone, vararg expected: String) {
        withTimeout(TIMEOUT) {
            while (phone.history() != expected.toList()) delay(POLL)
        }
    }

    @Test
    fun `the phone listens and announces itself only while the app is open with sync on`() =
        runBlocking {
            val port = listening()
            assertThat(runtime.status.until { it.listening }.phonesFound).isEqualTo(0)
            assertThat(runtime.address()).isEqualTo("127.0.0.1:$port")
            assertThat(discovery.announcedTag).isEqualTo(HouseholdKey.tag(key))

            foreground(false)
            runtime.status.until { !it.listening }
            discovery.announced.until { it == null }
            assertThat(runtime.address()).isNull()
        }

    @Test
    fun `switching sync off, losing the key or the network stops listening`() = runBlocking {
        listening()
        val stops = listOf<Pair<suspend () -> Unit, suspend () -> Unit>>(
            suspend { enable(false) } to suspend { enable(true) },
            suspend { a.device.forgetKey() } to suspend { a.device.replaceKey(key) },
            suspend { discovery.address.value = null } to
                suspend { discovery.address.value = LOOPBACK },
        )
        for ((stop, restart) in stops) {
            stop()
            runtime.status.until { !it.listening }
            restart()
            runtime.status.until { it.listening }
        }
    }

    @Test
    fun `an address it cannot listen on is skipped until the network changes`() = runBlocking {
        runtime.start()
        enable(true)
        foreground(true)
        // A documentation address no interface of this machine has.
        discovery.address.value = InetAddress.getByName("192.0.2.1") as Inet4Address
        delay(SETTLE)
        assertThat(runtime.address()).isNull()
        discovery.address.value = LOOPBACK
        runtime.status.until { it.listening }
        Unit
    }

    @Test
    fun `a phone that appears is synced with, by the phone with the smaller id`() = runBlocking {
        listening()
        listeningSocket().use { server ->
            val serving = async(dispatcher) { b.runner.serve(server.accept()) }
            discovery.peers.value =
                mapOf("phone-b" to PeerAddress("phone-b", LOOPBACK, server.localPort))
            serving.await()
        }
        assertThat(runtime.status.until { it.phonesFound == 1 }.listening).isEqualTo(true)
        awaitHistory(a, "auto:done:")
        awaitHistory(b, "peer:done:")
    }

    @Test
    fun `a phone with a smaller id that appears starts the sync itself`() = runBlocking {
        listening()
        ServerSocket(0, 1, LOOPBACK).use { server ->
            server.soTimeout = SETTLE.toInt()
            discovery.peers.value =
                mapOf("phone-0" to PeerAddress("phone-0", LOOPBACK, server.localPort))
            runtime.status.until { it.phonesFound == 1 }
            assertFailure { server.accept() }.isInstanceOf(SocketTimeoutException::class)
        }
        assertThat(a.history()).isEqualTo(emptyList())
    }

    @Test
    fun `a household phone that connects is served`() = runBlocking {
        val port = listening()
        b.runner.connect(PeerAddress("phone-a", LOOPBACK, port), SyncStart.USER)
        awaitHistory(b, "user:done:")
        awaitHistory(a, "peer:done:")
    }

    @Test
    fun `sync now reaches the paired phones where they were last seen`() = runBlocking {
        enable(true)
        listeningSocket().use { server ->
            a.database.syncDao().upsertPeer(
                SyncPeerEntity("phone-b", "Galaxy", 1, "127.0.0.1:${server.localPort}", null, null),
            )
            val serving = async(dispatcher) { b.runner.serve(server.accept()) }
            runtime.syncNow()
            serving.await()
        }
        awaitHistory(a, "user:done:")
    }

    @Test
    fun `sync now with nobody to reach says so, and does nothing while sync is off`() =
        runBlocking {
            runtime.syncNow()
            assertThat(a.history()).isEqualTo(emptyList())

            enable(true)
            runtime.syncSoon()
            awaitHistory(a, "user:failed:peer_unreachable")
        }

    @Test
    fun `a sync a killed app left running is closed at start`() = runBlocking {
        a.database.syncDao().insertEvent(
            SyncEventEntity("e1", null, null, "auto", 1, null, "running", null, 0, 0, 0),
        )
        runtime.start()
        awaitHistory(a, "auto:failed:interrupted")
    }

    /** The network and the phones on it, as the test sets them. */
    private class FakeDiscovery : PeerDiscovery {
        val address = MutableStateFlow<Inet4Address?>(null)
        val peers = MutableStateFlow<Map<String, PeerAddress>>(emptyMap())

        /** The port announced now, or null. */
        val announced = MutableStateFlow<Int?>(null)

        @Volatile
        var announcedTag: String? = null

        override fun localAddress(): Flow<Inet4Address?> = address

        override suspend fun announce(port: Int, deviceId: String, tag: String) {
            announcedTag = tag
            announced.value = port
            try {
                awaitCancellation()
            } finally {
                announced.value = null
            }
        }

        override fun peers(tag: String, selfId: String): Flow<Map<String, PeerAddress>> = peers
    }

    private companion object {
        const val NOW = 1_763_300_000_000L
        const val THREADS = 8
        const val TIMEOUT = 10_000L
        const val POLL = 20L
        const val SETTLE = 300L
    }
}
