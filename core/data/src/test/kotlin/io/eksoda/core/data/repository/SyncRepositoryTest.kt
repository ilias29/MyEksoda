package io.eksoda.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.eksoda.core.data.sync.FakeSyncDevice
import io.eksoda.core.data.sync.SyncControl
import io.eksoda.core.database.EksodaDatabase
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.database.entity.SyncPeerEntity
import io.eksoda.core.model.SyncJoinResult
import io.eksoda.core.model.SyncResult
import io.eksoda.core.model.SyncStart
import io.eksoda.core.model.SyncStatus
import io.eksoda.engine.sync.HouseholdKey
import io.eksoda.engine.sync.PairingCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SyncRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: EksodaDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repository: SyncRepositoryImpl
    private val device = FakeSyncDevice("this-phone", "Test phone", key = null)
    private val control = FakeControl()
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EksodaDatabase::class.java,
        ).allowMainThreadQueries().build()
        scope = CoroutineScope(dispatcher + SupervisorJob())
        val preferences = PreferenceDataStoreFactory.create(scope = scope) {
            folder.root.resolve("settings.preferences_pb")
        }
        repository = SyncRepositoryImpl(
            preferences,
            database.syncDao(),
            device,
            control,
            clock,
            dispatcher,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun `turning sync on the first time makes the household key`() = runTest {
        assertThat(repository.hasHouseholdKey.first()).isFalse()

        repository.setEnabled(true)

        assertThat(repository.enabled.first()).isTrue()
        assertThat(device.key()).isNotNull()
        assertThat(repository.hasHouseholdKey.first()).isTrue()
    }

    @Test
    fun `the code another phone scans holds this phone's key and id`() = runTest {
        val code = PairingCode.decode(repository.pairingCode().getOrThrow())

        assertThat(code?.key?.toList()).isEqualTo(device.key()?.toList())
        assertThat(code?.deviceId).isEqualTo("this-phone")
        assertThat(code?.deviceName).isEqualTo("Test phone")
        assertThat(code?.address).isEqualTo("192.168.1.30:5000")
    }

    @Test
    fun `joining takes the other household's key, pairs its phone and turns sync on`() = runTest {
        device.keyOrCreate()
        database.syncDao().upsertPeer(peer("old-phone"))
        val key = HouseholdKey.create()
        val code = PairingCode(key, "their-phone", "Pixel 8", "192.168.1.20:4000").encode()

        val result = repository.join(code)

        assertThat(result).isEqualTo(SyncJoinResult.Joined("Pixel 8"))
        assertThat(device.key()?.toList()).isEqualTo(key.toList())
        val peers = repository.peers.first()
        assertThat(peers.map { it.deviceId }).containsExactly("their-phone")
        assertThat(peers.single().pairedAt.toEpochMilli()).isEqualTo(NOW)
        assertThat(database.syncDao().peer("their-phone")?.lastAddress)
            .isEqualTo("192.168.1.20:4000")
        assertThat(repository.enabled.first()).isTrue()
        assertThat(control.syncs).isEqualTo(1)
    }

    @Test
    fun `joining a household this phone is already in keeps its phones`() = runTest {
        val key = device.keyOrCreate()
        database.syncDao().upsertPeer(peer("old-phone"))

        repository.join(PairingCode(key, "their-phone", "Pixel 8").encode())

        assertThat(repository.peers.first().map { it.deviceId })
            .containsExactlyInAnyOrder("old-phone", "their-phone")
    }

    @Test
    fun `a code that is not a household code, or is this phone's own, changes nothing`() = runTest {
        assertThat(repository.join("https://e-invoicing.gr/r/1")).isEqualTo(SyncJoinResult.NotACode)
        val own = PairingCode(HouseholdKey.create(), "this-phone", "Test phone").encode()
        assertThat(repository.join(own)).isEqualTo(SyncJoinResult.OwnCode)
        assertThat(device.key()).isNull()
        assertThat(repository.enabled.first()).isFalse()
    }

    @Test
    fun `leaving forgets the key and the phones, and turns sync off`() = runTest {
        repository.setEnabled(true)
        database.syncDao().upsertPeer(peer("old-phone"))

        repository.leave().getOrThrow()

        assertThat(device.key()).isNull()
        assertThat(repository.peers.first()).isEmpty()
        assertThat(repository.enabled.first()).isFalse()
    }

    @Test
    fun `the status and sync now are the running sync's`() = runTest {
        assertThat(repository.status.first()).isEqualTo(SyncStatus())
        repository.syncNow()
        assertThat(control.syncs).isEqualTo(1)
    }

    @Test
    fun `the history lists every sync, newest first`() = runTest {
        // Before the first sync there is no last success.
        assertThat(repository.lastSuccess.first()).isNull()
        val dao = database.syncDao()
        dao.insertEvent(SyncEventEntity("e1", "p", "Pixel 8", "auto", 1, 2, "done", null, 3, 1, 0))
        dao.insertEvent(
            SyncEventEntity("e2", null, null, "user", 5, 6, "failed", "peer_unreachable", 0, 0, 0),
        )
        dao.insertEvent(
            SyncEventEntity("e3", "p", "Pixel 8", "peer", 9, null, "running", null, 0, 0, 0),
        )

        val history = repository.getHistory().asSnapshot()
        assertThat(history.map { it.id }).containsExactly("e3", "e2", "e1")
        val (running, failed, done) = history
        assertThat(listOf(running.startedBy, running.result, running.finishedAt))
            .isEqualTo(listOf(SyncStart.PEER, SyncResult.RUNNING, null))
        assertThat(listOf(failed.result, failed.errorCode, failed.peerName))
            .isEqualTo(listOf(SyncResult.FAILED, "peer_unreachable", null))
        assertThat(listOf(done.inserted, done.updated, done.deleted)).isEqualTo(listOf(3, 1, 0))
        assertThat(done.finishedAt).isEqualTo(Instant.ofEpochMilli(2))
        // The last sync that went through.
        assertThat(repository.lastSuccess.first()).isEqualTo(Instant.ofEpochMilli(2))
    }

    @Test
    fun `a paired phone shows when it was last seen and last synced`() = runTest {
        database.syncDao().upsertPeer(SyncPeerEntity("p", "Pixel 8", 1, "192.168.1.20:4000", 5, 6))
        val peer = repository.peers.first().single()
        assertThat(listOf(peer.lastSeenAt, peer.lastSuccessAt))
            .isEqualTo(listOf(Instant.ofEpochMilli(5), Instant.ofEpochMilli(6)))
    }

    private fun peer(id: String) = SyncPeerEntity(id, id, pairedAt = 1, null, null, null)

    private class FakeControl : SyncControl {
        var syncs = 0
        override val status: Flow<SyncStatus> = flowOf(SyncStatus())

        override suspend fun syncNow() {
            syncs++
        }

        override fun syncSoon() {
            syncs++
        }

        override fun address() = "192.168.1.30:5000"
    }

    private companion object {
        const val NOW = 1_763_300_000_000L
    }
}
