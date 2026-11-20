package io.eksoda.core.data.sync

import io.eksoda.core.data.di.IoDispatcher
import io.eksoda.core.database.dao.SyncDao
import io.eksoda.core.database.entity.SyncEventEntity
import io.eksoda.core.database.entity.SyncPeerEntity
import io.eksoda.core.model.SyncResult
import io.eksoda.core.model.SyncStart
import io.eksoda.engine.sync.LocalNetwork
import io.eksoda.engine.sync.Snapshot
import io.eksoda.engine.sync.SyncCounts
import io.eksoda.engine.sync.SyncException
import io.eksoda.engine.sync.SyncLocal
import io.eksoda.engine.sync.SyncPeerInfo
import io.eksoda.engine.sync.SyncSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Where a household phone listens on the local network. */
data class PeerAddress(val deviceId: String?, val host: InetAddress, val port: Int) {
    /** "192.168.1.20:41234", "[fe80::1]:41234": as stored in `sync_peer.last_address`. */
    fun text(): String {
        val literal = host.hostAddress.orEmpty().substringBefore('%')
        return if (host is Inet6Address) "[$literal]:$port" else "$literal:$port"
    }

    companion object {
        /** Null for anything but a literal address and port (no name is ever resolved). */
        fun parse(deviceId: String?, text: String): PeerAddress? {
            val host = text.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
            val port = text.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..MAX_PORT }
            if (port == null || !LITERAL.matches(host)) return null
            return runCatching { PeerAddress(deviceId, InetAddress.getByName(host), port) }
                .getOrNull()
        }

        private const val MAX_PORT = 65_535
        private val LITERAL = Regex("[0-9a-fA-F.:]+")
    }
}

/** Which addresses household sync may connect to or accept: the local network only. */
open class SyncAddressPolicy @Inject constructor() {
    open fun allows(address: InetAddress): Boolean = LocalNetwork.isLocal(address)
}

/**
 * Runs household sync sessions (core:engine `SyncSession`), one at a time, and keeps the
 * history: every sync this phone starts gets an entry, and so does every sync another
 * household phone starts with it. A stranger that connects without the key leaves none.
 */
@Singleton
class SyncRunner @Inject constructor(
    private val device: SyncDevice,
    merger: SyncMerger,
    private val sync: SyncDao,
    private val policy: SyncAddressPolicy,
    private val clock: Clock,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private val running = MutableStateFlow(false)

    val syncing: StateFlow<Boolean> = running.asStateFlow()

    private val local = object : SyncLocal {
        override val deviceId get() = device.deviceId
        override val deviceName get() = device.deviceName
        override val schema get() = merger.schema

        override suspend fun snapshot(): Snapshot = merger.snapshotForPeer()

        override suspend fun merge(remote: Snapshot): SyncCounts = merger.merge(remote)
    }

    /**
     * Syncs with the phone at [target]. An automatic sync is skipped when that phone synced
     * with this one in the last [AUTO_INTERVAL_MILLIS].
     */
    suspend fun connect(target: PeerAddress, startedBy: SyncStart) = withContext(io) {
        lock.withLock {
            val key = device.key() ?: return@withLock
            val known = target.deviceId?.let { sync.peer(it) }
            if (startedBy == SyncStart.AUTO && recentlySynced(known)) return@withLock
            val event = started(startedBy, known?.deviceId, known?.name)
            var peer: SyncPeerInfo? = null
            val outcome = session { open(target, key) { peer = it } }
            finished(event, peer, outcome, address = target.text())
        }
    }

    /** Answers a household phone that connected to this one; closes [socket]. */
    suspend fun serve(socket: Socket) = withContext(io) {
        socket.use {
            if (!policy.allows(socket.inetAddress)) return@withContext
            val key = device.key() ?: return@withContext
            socket.soTimeout = READ_TIMEOUT
            lock.withLock {
                var event: SyncEventEntity? = null
                var peer: SyncPeerInfo? = null
                val outcome = session {
                    SyncSession.accept(socket.inputStream, socket.outputStream, key, local) {
                        peer = it
                        event = started(SyncStart.AUTO, it.deviceId, it.deviceName)
                    }.second
                }
                // Its port here is not the one it listens on: its stored address stays.
                event?.let { finished(it, peer, outcome, address = null) }
            }
        }
    }

    /** "Sync now" with no household phone to try: the history says so. */
    suspend fun nobodyToSync() = withContext(io) {
        val event = started(SyncStart.USER, null, null)
        finished(event, null, Result.failure(SyncException(PEER_UNREACHABLE)), address = null)
    }

    private suspend fun open(
        target: PeerAddress,
        key: ByteArray,
        onPeer: (SyncPeerInfo) -> Unit,
    ): SyncCounts {
        if (!policy.allows(target.host)) unreachable()
        return Socket().use { socket ->
            try {
                socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT)
            } catch (e: IOException) {
                unreachable(e)
            }
            socket.soTimeout = READ_TIMEOUT
            SyncSession.connect(socket.inputStream, socket.outputStream, key, local) {
                onPeer(it)
            }.second
        }
    }

    private fun recentlySynced(peer: SyncPeerEntity?): Boolean {
        val last = peer?.lastSuccessAt ?: return false
        return clock.millis() - last < AUTO_INTERVAL_MILLIS
    }

    private suspend fun started(startedBy: SyncStart, peerId: String?, name: String?) =
        SyncEventEntity(
            id = UUID.randomUUID().toString(),
            peerDeviceId = peerId,
            peerName = name,
            startedBy = startedBy.code,
            startedAt = clock.millis(),
            finishedAt = null,
            result = SyncResult.RUNNING.code,
            errorCode = null,
            inserted = 0,
            updated = 0,
            deleted = 0,
        ).also { sync.insertEvent(it) }

    private suspend fun finished(
        event: SyncEventEntity,
        peer: SyncPeerInfo?,
        outcome: Result<SyncCounts>,
        address: String?,
    ) {
        val now = clock.millis()
        val named = event.copy(
            peerDeviceId = peer?.deviceId ?: event.peerDeviceId,
            peerName = peer?.deviceName ?: event.peerName,
            finishedAt = now,
        )
        outcome.fold(
            onSuccess = { counts ->
                sync.updateEvent(
                    named.copy(
                        result = SyncResult.DONE.code,
                        inserted = counts.inserted,
                        updated = counts.updated,
                        deleted = counts.deleted,
                    ),
                )
            },
            onFailure = { error ->
                val code = (error as? SyncException)?.code ?: SyncException.INTERRUPTED
                sync.updateEvent(named.copy(result = SyncResult.FAILED.code, errorCode = code))
            },
        )
        // A phone that answered with the key is of the household: it is (or stays) paired.
        if (peer != null) remember(peer, address, success = outcome.isSuccess, now = now)
        sync.pruneEvents(HISTORY_SIZE)
    }

    private suspend fun remember(
        peer: SyncPeerInfo,
        address: String?,
        success: Boolean,
        now: Long,
    ) {
        val known = sync.peer(peer.deviceId)
        sync.upsertPeer(
            SyncPeerEntity(
                deviceId = peer.deviceId,
                name = peer.deviceName.ifBlank { known?.name.orEmpty() },
                pairedAt = known?.pairedAt ?: now,
                lastAddress = address ?: known?.lastAddress,
                lastSeenAt = now,
                lastSuccessAt = if (success) now else known?.lastSuccessAt,
            ),
        )
    }

    /** A session's outcome; a broken connection is `interrupted`. */
    private suspend fun session(block: suspend () -> SyncCounts): Result<SyncCounts> {
        running.value = true
        return try {
            Result.success(block())
        } catch (e: SyncException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(SyncException(SyncException.INTERRUPTED, e))
        } finally {
            running.value = false
        }
    }

    private fun unreachable(cause: Throwable? = null): Nothing =
        throw SyncException(PEER_UNREACHABLE, cause)

    companion object {
        const val PEER_UNREACHABLE = "peer_unreachable"

        /** An automatic sync with the same phone at most this often. */
        const val AUTO_INTERVAL_MILLIS = 15 * 60 * 1000L
        const val HISTORY_SIZE = 500
        private const val CONNECT_TIMEOUT = 5_000
        private const val READ_TIMEOUT = 30_000
    }
}
