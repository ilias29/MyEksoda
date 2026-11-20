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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where a household phone listens on the local network. */
data class PeerAddress(val deviceId: String?, val host: InetAddress, val port: Int) {
    /**
     * "192.168.1.20:41234", "[fe80::1%wlan0]:41234": as stored in `sync_peer.last_address`.
     * A link-local IPv6 address keeps its scope, without which it cannot be dialed again.
     */
    fun text(): String {
        val literal = host.hostAddress.orEmpty()
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

        /** Digits, dots and colons, and an IPv6 scope (`%wlan0`, `%12`). */
        private val LITERAL = Regex("[0-9a-fA-F.:]+(%[0-9A-Za-z_.-]+)?")
    }
}

/** Which addresses household sync may connect to or accept: the local network only. */
open class SyncAddressPolicy @Inject constructor() {
    open fun allows(address: InetAddress): Boolean = LocalNetwork.isLocal(address)
}

/**
 * Runs household sync sessions (core:engine `SyncSession`), one at a time, and keeps the
 * history: every sync this phone starts gets an entry, and so does every sync a household
 * phone starts with it once it proved it holds the key (a stranger leaves none). A phone that
 * connects while a sync runs is told `busy` at once instead of waiting.
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

    /** When each phone was last tried automatically, whatever came of it. */
    private val autoAttempts = ConcurrentHashMap<String, Long>()

    val syncing: StateFlow<Boolean> = running.asStateFlow()

    private val local = object : SyncLocal {
        override val deviceId get() = device.deviceId
        override val deviceName get() = device.deviceName
        override val schema get() = merger.schema

        override suspend fun snapshot(): Snapshot = merger.snapshotForPeer()

        override suspend fun merge(remote: Snapshot): SyncCounts = merger.merge(remote)
    }

    /**
     * Syncs with the phone at [target]. An automatic sync is tried at most every
     * [AUTO_INTERVAL_MILLIS] per phone, and skipped when the two synced within that time.
     */
    suspend fun connect(target: PeerAddress, startedBy: SyncStart) = guarded {
        val automatic = startedBy == SyncStart.AUTO
        if (automatic && !autoDue(target.deviceId)) return@guarded
        lock.withLock {
            val key = currentKey() ?: return@withLock
            val known = target.deviceId?.let { sync.peer(it) }
            if (automatic && recentlySynced(known)) return@withLock
            val event = started(startedBy, known?.deviceId, known?.name)
            var peer: SyncPeerInfo? = null
            val outcome = session { open(target, key) { peer = it } }
            finished(event, peer, outcome, address = target.text())
        }
    }

    /**
     * Answers a phone that connected to this one; closes [socket]. Until it proves it holds
     * the key it has [HANDSHAKE_DEADLINE_MILLIS], a small hello and no lock.
     */
    suspend fun serve(socket: Socket) = guarded {
        socket.use { _ ->
            if (!policy.allows(socket.inetAddress)) return@guarded
            val key = currentKey() ?: return@guarded
            socket.soTimeout = HANDSHAKE_TIMEOUT
            var event: SyncEventEntity? = null
            var peer: SyncPeerInfo? = null
            var locked = false
            val deadline = launch {
                delay(HANDSHAKE_DEADLINE_MILLIS)
                if (peer == null) runCatching { socket.close() }
            }
            try {
                val outcome = session {
                    SyncSession.accept(
                        socket.inputStream,
                        socket.outputStream,
                        key,
                        local,
                    ) { info ->
                        // Another sync runs: this phone hears `busy` instead of waiting.
                        if (!lock.tryLock()) return@accept false
                        locked = true
                        peer = info
                        socket.soTimeout = SESSION_TIMEOUT
                        event = started(SyncStart.PEER, info.deviceId, info.deviceName)
                        true
                    }.second
                }
                deadline.cancel()
                // Its port here is not the one it listens on: its stored address stays.
                event?.let { entry -> finished(entry, peer, outcome, address = null) }
            } finally {
                deadline.cancel()
                if (locked) lock.unlock()
            }
        }
    }

    /** "Sync now" with no household phone to try: the history says so. */
    suspend fun nobodyToSync() = guarded {
        val event = started(SyncStart.USER, null, null)
        finished(event, null, Result.failure(SyncException(PEER_UNREACHABLE)), address = null)
    }

    /** Entries a killed app left running end as interrupted (at app start). */
    suspend fun closeStaleEntries() = guarded {
        sync.failRunning(SyncException.INTERRUPTED, clock.millis())
    }

    /**
     * The entry points never throw (but cancellation): a sync's own failures are in its
     * history entry, and one that could not even be written there (the database failed) must
     * not crash the app or stop the listener. Such an entry is closed at the next start.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun guarded(block: suspend CoroutineScope.() -> Unit) = withContext(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            running.value = false
        }
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
            socket.soTimeout = HANDSHAKE_TIMEOUT
            SyncSession.connect(socket.inputStream, socket.outputStream, key, local) {
                // The other phone merges before it answers: give it time.
                socket.soTimeout = SESSION_TIMEOUT
                onPeer(it)
            }.second
        }
    }

    /** The key, or null (none, or the Keystore failed in a way it does not report). */
    private fun currentKey(): ByteArray? = runCatching { device.key() }.getOrNull()

    private fun autoDue(deviceId: String?): Boolean {
        val id = deviceId ?: return false
        val now = clock.millis()
        val last = autoAttempts[id]
        if (last != null && now - last < AUTO_INTERVAL_MILLIS) return false
        autoAttempts[id] = now
        return true
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
        ).also {
            sync.insertEvent(it)
            running.value = true
        }

    /**
     * Ends the entry even when the app is leaving (the merge may already be stored): not
     * cancellable, so an entry is never left running.
     */
    private suspend fun finished(
        event: SyncEventEntity,
        peer: SyncPeerInfo?,
        outcome: Result<SyncCounts>,
        address: String?,
    ) = withContext(NonCancellable) {
        try {
            val now = clock.millis()
            val named = event.copy(
                peerDeviceId = peer?.deviceId ?: event.peerDeviceId,
                peerName = peer?.deviceName ?: event.peerName,
                finishedAt = now,
            )
            sync.updateEvent(
                outcome.fold(onSuccess = { named.done(it) }, onFailure = { named.failed(it) }),
            )
            // A phone that answered with the key is of the household: it is (or stays) paired.
            peer?.let { remember(it, address, success = outcome.isSuccess, now = now) }
            sync.pruneEvents(HISTORY_SIZE)
        } finally {
            running.value = false
        }
    }

    private fun SyncEventEntity.done(counts: SyncCounts) = copy(
        result = SyncResult.DONE.code,
        inserted = counts.inserted,
        updated = counts.updated,
        deleted = counts.deleted,
    )

    private fun SyncEventEntity.failed(error: Throwable) = copy(
        result = SyncResult.FAILED.code,
        errorCode = (error as? SyncException)?.code ?: SyncException.INTERNAL,
    )

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

    /**
     * A session's outcome. Nothing a session throws may end the app or the listener: what is
     * not a sync failure or a broken connection is recorded as `internal`.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun session(block: suspend () -> SyncCounts): Result<SyncCounts> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(SyncException(SyncException.INTERRUPTED, e))
    } catch (e: Exception) {
        Result.failure(SyncException(SyncException.INTERNAL, e))
    }

    private fun unreachable(cause: Throwable? = null): Nothing =
        throw SyncException(PEER_UNREACHABLE, cause)

    companion object {
        const val PEER_UNREACHABLE = "peer_unreachable"

        /** An automatic sync with the same phone at most this often. */
        const val AUTO_INTERVAL_MILLIS = 15 * 60 * 1000L
        const val HISTORY_SIZE = 500
        private const val CONNECT_TIMEOUT = 5_000

        /** Silence allowed before the other phone proved it holds the key. */
        private const val HANDSHAKE_TIMEOUT = 10_000
        private const val HANDSHAKE_DEADLINE_MILLIS = 15_000L

        /** Silence allowed once it did: the other phone may be merging a large household. */
        private const val SESSION_TIMEOUT = 120_000
    }
}
