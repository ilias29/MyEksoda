package io.eksoda.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.database.entity.SyncPeerEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncDaoTest {
    private lateinit var database: EksodaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            EksodaDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the history is newest first and the last success ignores failures`() = runTest {
        val dao = database.syncDao()
        assertThat(dao.observeLastSuccess().first()).isNull()

        dao.insertEvent(event("a", startedAt = 100, finishedAt = 110, result = "done"))
        dao.insertEvent(event("b", startedAt = 300, finishedAt = 310, result = "failed"))
        dao.insertEvent(event("c", startedAt = 200, finishedAt = null, result = "running"))

        val page = dao.pagingEvents().load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 10, placeholdersEnabled = false),
        )
        assertThat(page).isInstanceOf(PagingSource.LoadResult.Page::class)
        val ids = (page as PagingSource.LoadResult.Page).data.map { it.id }
        assertThat(ids).containsExactly("b", "c", "a")
        assertThat(dao.observeLastSuccess().first()).isEqualTo(110L)
    }

    @Test
    fun `a peer is updated in place`() = runTest {
        val dao = database.syncDao()
        val peer = SyncPeerEntity("d1", "Pixel 9", pairedAt = 1, null, null, null)
        dao.upsertPeer(peer)
        dao.upsertPeer(peer.copy(lastSuccessAt = 5))
        assertThat(dao.observePeers().first()).containsExactly(peer.copy(lastSuccessAt = 5))
    }

    private fun event(id: String, startedAt: Long, finishedAt: Long?, result: String) =
        SyncEventEntity(
            id = id,
            peerDeviceId = "d1",
            peerName = "Pixel 9",
            startedBy = "auto",
            startedAt = startedAt,
            finishedAt = finishedAt,
            result = result,
            errorCode = if (result == "failed") "peer_unreachable" else null,
            inserted = 0,
            updated = 0,
            deleted = 0,
        )
}
