package io.eksoda.engine.sync

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.prop
import io.eksoda.engine.sync.SyncRows.merchant
import io.eksoda.engine.sync.SyncRows.receipt
import io.eksoda.engine.sync.SyncRows.snapshot
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.util.concurrent.Executors

class SyncSessionTest {
    private val key = HouseholdKey.create()

    @Test
    fun `after a session both phones hold the same data`() {
        val client = Phone(
            deviceId = "client",
            deviceName = "Pixel 8",
            data = snapshot(
                "merchant" to listOf(merchant("m1")),
                "receipt" to listOf(receipt("r1", mark = "1", url = "https://a.example")),
            ),
        )
        val server = Phone(
            deviceId = "server",
            deviceName = "Galaxy",
            data = snapshot(
                "merchant" to listOf(merchant("m1")),
                "receipt" to listOf(receipt("r2", mark = "2", url = "https://b.example")),
            ),
        )

        val (onClient, onServer) = session(client, key, server, key)

        assertThat(onClient.getOrThrow().first).isEqualTo(SyncPeerInfo("server", "Galaxy"))
        assertThat(onServer.getOrThrow().first).isEqualTo(SyncPeerInfo("client", "Pixel 8"))
        assertThat(onClient.getOrThrow().second).isEqualTo(SyncCounts(1, 0, 0))
        assertThat(onServer.getOrThrow().second).isEqualTo(SyncCounts(1, 0, 0))
        assertThat(client.data.forPeer().tables).isEqualTo(server.data.forPeer().tables)
        // The links stayed where they were scanned.
        val clientUrls = client.data.rows("receipt").associate { it["id"] to it["source_url"] }
        assertThat(clientUrls).isEqualTo(mapOf("r1" to "https://a.example", "r2" to null))
    }

    @Test
    fun `a phone without the household key is refused, and both know why`() {
        val (onClient, onServer) = session(Phone("c"), HouseholdKey.create(), Phone("s"), key)

        assertCode(SyncException.NOT_PAIRED, onClient)
        assertCode(SyncException.NOT_PAIRED, onServer)
    }

    @Test
    fun `phones with different schemas stop before any data moves`() {
        val server = Phone("s", schema = TEST_SCHEMA + 1)
        val (onClient, onServer) = session(Phone("c"), key, server, key)

        assertCode(SyncException.SCHEMA_MISMATCH, onClient)
        assertCode(SyncException.SCHEMA_MISMATCH, onServer)
        assertThat(server.merged).isFalse()
    }

    @Test
    fun `a merge that fails on the server is reported to the client`() {
        val server = Phone("s", failMerge = true)
        val client = Phone("c")
        val (onClient, onServer) = session(client, key, server, key)

        assertCode(SyncException.MERGE_FAILED, onServer)
        assertCode(SyncException.MERGE_FAILED, onClient)
        assertThat(client.merged).isFalse()
    }

    @Test
    fun `a connection that breaks off is interrupted`() {
        val toServer = PipedOutputStream()
        val serverInput = PipedInputStream(toServer)
        toServer.close()

        val result = runCatching {
            runBlocking { SyncSession.accept(serverInput, PipedOutputStream(), key, Phone("s")) }
        }

        assertCode(SyncException.INTERRUPTED, result)
    }

    @Test
    fun `only local network addresses are allowed`() {
        listOf("192.168.1.20", "10.0.0.2", "172.16.5.1", "169.254.3.4", "fe80::1", "fd12::1")
            .forEach { assertThat(LocalNetwork.isLocal(address(it)), it).isTrue() }
        listOf("8.8.8.8", "172.32.0.1", "127.0.0.1", "0.0.0.0", "::1", "2001:db8::1")
            .forEach { assertThat(LocalNetwork.isLocal(address(it)), it).isFalse() }
    }

    /** Runs a client and a server against each other over two pipes. */
    private fun session(
        client: Phone,
        clientKey: ByteArray,
        server: Phone,
        serverKey: ByteArray,
    ): Pair<Result<Pair<SyncPeerInfo, SyncCounts>>, Result<Pair<SyncPeerInfo, SyncCounts>>> {
        val toServer = PipedOutputStream()
        val toClient = PipedOutputStream()
        val serverInput = PipedInputStream(toServer, PIPE)
        val clientInput = PipedInputStream(toClient, PIPE)
        // Two real threads: each side blocks on its pipe while the other writes.
        val threads = Executors.newFixedThreadPool(2)
        return try {
            runBlocking(threads.asCoroutineDispatcher()) {
                val onServer = async {
                    runCatching { SyncSession.accept(serverInput, toClient, serverKey, server) }
                        .also { toClient.close() }
                }
                val onClient = async {
                    runCatching { SyncSession.connect(clientInput, toServer, clientKey, client) }
                        .also { toServer.close() }
                }
                onClient.await() to onServer.await()
            }
        } finally {
            threads.shutdown()
        }
    }

    private fun assertCode(code: String, result: Result<*>) {
        assertFailure { result.getOrThrow() }
            .isInstanceOf(SyncException::class)
            .prop(SyncException::code)
            .isEqualTo(code)
    }

    private fun address(literal: String) = InetAddress.getByName(literal)

    /** A phone whose data is a snapshot, merged with the real merge. */
    private class Phone(
        override val deviceId: String,
        override val deviceName: String = deviceId,
        var data: Snapshot = Snapshot(TEST_SCHEMA, emptyMap()),
        override val schema: Int = TEST_SCHEMA,
        private val failMerge: Boolean = false,
    ) : SyncLocal {
        var merged = false

        override suspend fun snapshot() = data.forPeer()

        override suspend fun merge(remote: Snapshot): SyncCounts {
            if (failMerge) throw SyncException(SyncException.MERGE_FAILED)
            val result = SyncMerge.merge(data, remote)
            data = Snapshot(schema, result.tables)
            merged = true
            return SyncCounts(result.inserted, result.updated, result.deleted)
        }
    }

    private companion object {
        const val PIPE = 64 * 1024
    }
}
