package io.eksoda.engine.sync

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** What a merge changed on a phone, for its sync history. */
public data class SyncCounts(val inserted: Int, val updated: Int, val deleted: Int)

/** The other phone of a session, as its hello named it. */
public data class SyncPeerInfo(val deviceId: String, val deviceName: String)

/** This phone during a session. */
public interface SyncLocal {
    public val deviceId: String
    public val deviceName: String

    /** The database schema version: phones with different ones do not sync. */
    public val schema: Int

    /** This phone's data as the other phone gets it ([Snapshot.forPeer]). */
    public suspend fun snapshot(): Snapshot

    /** Merges the other phone's data into this phone's; throws [SyncException]. */
    public suspend fun merge(remote: Snapshot): SyncCounts
}

/**
 * One household sync between two phones over a connection (docs/design/household-sync.md,
 * "Protocol"). The phone that connected is the client:
 *
 * 1. Both send a hello sealed with the household key; a phone without the key cannot read it,
 *    and the server answers one it cannot open with an empty frame (`not_paired`).
 * 2. The session key is HKDF of the household key and both hellos' nonces.
 * 3. The client sends its snapshot. The server merges it and sends its own snapshot, now with
 *    the client's data in it (or an error frame). The client merges that.
 *
 * Both phones end with the same data. Calls block on the streams: run them on an IO thread.
 * Failures are [SyncException]s; a broken connection is [SyncException.INTERRUPTED].
 */
public object SyncSession {
    public const val PROTOCOL: Int = 1
    private const val SNAPSHOT: Byte = 1
    private const val ERROR: Byte = 2
    private const val MAX_ERROR_CODE = 64

    public suspend fun connect(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        local: SyncLocal,
        onPeer: suspend (SyncPeerInfo) -> Unit = {},
    ): Pair<SyncPeerInfo, SyncCounts> = interruptible {
        val frames = Frames(input, output)
        val mine = hello(local)
        Hello.write(frames, key, Hello.CLIENT, mine)
        val theirs = Hello.read(frames, key, Hello.SERVER)
        val peer = checked(theirs, local).also { onPeer(it) }
        val channel = SecureChannel(
            frames,
            SecureChannel.sessionKey(key, mine.nonce, theirs.nonce),
            SecureChannel.FROM_CLIENT,
        )
        channel.send(SNAPSHOT, SnapshotCodec.encode(local.snapshot()))
        peer to local.merge(receiveSnapshot(channel))
    }

    public suspend fun accept(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        local: SyncLocal,
        onPeer: suspend (SyncPeerInfo) -> Unit = {},
    ): Pair<SyncPeerInfo, SyncCounts> = interruptible {
        val frames = Frames(input, output)
        val theirs = try {
            Hello.read(frames, key, Hello.CLIENT)
        } catch (e: SyncException) {
            if (e.code == SyncException.NOT_PAIRED) Hello.refuse(frames)
            throw e
        }
        val mine = hello(local)
        Hello.write(frames, key, Hello.SERVER, mine)
        val peer = checked(theirs, local).also { onPeer(it) }
        val channel = SecureChannel(
            frames,
            SecureChannel.sessionKey(key, theirs.nonce, mine.nonce),
            SecureChannel.FROM_SERVER,
        )
        val remote = receiveSnapshot(channel)
        val counts = try {
            local.merge(remote)
        } catch (e: SyncException) {
            channel.send(ERROR, e.code.toByteArray())
            throw e
        }
        channel.send(SNAPSHOT, SnapshotCodec.encode(local.snapshot()))
        peer to counts
    }

    private fun hello(local: SyncLocal) = Hello(
        protocol = PROTOCOL,
        deviceId = local.deviceId,
        deviceName = local.deviceName,
        schema = local.schema,
        nonce = SyncCrypto.randomBytes(Hello.NONCE_BYTES),
    )

    /** Both phones check, so both stop with the same reason. */
    private fun checked(hello: Hello, local: SyncLocal): SyncPeerInfo {
        if (hello.protocol != PROTOCOL || hello.schema != local.schema) {
            throw SyncException(SyncException.SCHEMA_MISMATCH)
        }
        if (hello.nonce.size != Hello.NONCE_BYTES) throw SyncException(SyncException.DAMAGED)
        return SyncPeerInfo(hello.deviceId, hello.deviceName)
    }

    private fun receiveSnapshot(channel: SecureChannel): Snapshot {
        val (type, body) = channel.receive()
        return when (type) {
            SNAPSHOT -> SnapshotCodec.decode(body)

            // The other phone's reason, as one of the codes the UI knows (or a generic one).
            ERROR -> throw SyncException(body.toString(Charsets.UTF_8).take(MAX_ERROR_CODE))

            else -> throw SyncException(SyncException.DAMAGED)
        }
    }

    private suspend fun <T> interruptible(block: suspend () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw SyncException(SyncException.INTERRUPTED, e)
    }
}

/** Household sync talks only to phones on the local network, never to the internet. */
public object LocalNetwork {
    private const val ULA_MASK = 0xfe
    private const val ULA_PREFIX = 0xfc

    /**
     * Private IPv4 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10) and IPv6
     * unique local (fc00::/7) addresses. Never loopback, the wildcard or a public address.
     */
    public fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> address.isSiteLocalAddress || address.isLinkLocalAddress

        is Inet6Address ->
            address.isLinkLocalAddress ||
                (address.address[0].toInt() and ULA_MASK) == ULA_PREFIX

        else -> false
    }
}
