package io.eksoda.engine.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Base64

/** Length-prefixed frames: a uint32 (big endian) size, then that many bytes. */
internal class Frames(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(BufferedInputStream(input))
    private val output = DataOutputStream(BufferedOutputStream(output))

    fun write(bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    /**
     * A frame of at most [max] bytes; a larger size is refused before anything is allocated.
     * Throws `EOFException` when the other phone closed the connection.
     */
    fun read(max: Int = MAX_FRAME): ByteArray {
        val size = input.readInt()
        if (size < 0 || size > max) throw SyncException(SyncException.DAMAGED)
        return ByteArray(size).also(input::readFully)
    }

    companion object {
        /** A compressed snapshot; far above any household's. */
        const val MAX_FRAME = 128 * 1024 * 1024

        /** A hello, read before the other phone is known: small, so a stranger costs nothing. */
        const val MAX_HELLO = 4 * 1024
    }
}

/**
 * Who a phone is, sent first and sealed with the household key, so only a phone holding the
 * key can read it or answer. [nonce] (32 random bytes) makes every session's key new.
 */
internal class Hello(
    val protocol: Int,
    val deviceId: String,
    val deviceName: String,
    val schema: Int,
    val nonce: ByteArray,
) {
    fun encode(): ByteArray = buildJsonObject {
        put("protocol", protocol)
        put("device_id", deviceId)
        put("device_name", deviceName)
        put("schema", schema)
        put("nonce", Base64.getEncoder().encodeToString(nonce))
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val NONCE_BYTES = 32

        /** Phones are the client (it connected) or the server; each seals its hello as itself. */
        const val CLIENT = "client"
        const val SERVER = "server"

        fun write(frames: Frames, key: ByteArray, role: String, hello: Hello) {
            val nonce = SyncCrypto.randomBytes(SyncCrypto.NONCE_BYTES)
            frames.write(nonce + SyncCrypto.seal(key, nonce, aad(role), hello.encode()))
        }

        /**
         * Throws [SyncException.NOT_PAIRED] when the hello was not sealed with this key (or the
         * other phone said so with an empty frame), [SyncException.BUSY] when the other phone
         * answered that it is syncing with another one.
         */
        fun read(frames: Frames, key: ByteArray, role: String): Hello {
            val bytes = frames.read(Frames.MAX_HELLO)
            if (bytes.size == BUSY_FRAME.size) throw SyncException(SyncException.BUSY)
            if (bytes.size <= SyncCrypto.NONCE_BYTES) throw SyncException(SyncException.NOT_PAIRED)
            val nonce = bytes.copyOf(SyncCrypto.NONCE_BYTES)
            val sealed = bytes.copyOfRange(SyncCrypto.NONCE_BYTES, bytes.size)
            val plain = try {
                SyncCrypto.open(key, nonce, aad(role), sealed)
            } catch (e: GeneralSecurityException) {
                throw SyncException(SyncException.NOT_PAIRED, e)
            }
            return decode(plain)
        }

        /** Tells a phone whose hello did not open that it is not of this household. */
        fun refuse(frames: Frames) = frames.write(ByteArray(0))

        /** Tells a household phone that this one is syncing with another: try again later. */
        fun busy(frames: Frames) = frames.write(BUSY_FRAME)

        private val BUSY_FRAME = byteArrayOf(1)

        private fun decode(bytes: ByteArray): Hello = try {
            val json = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            Hello(
                protocol = json.getValue("protocol").jsonPrimitive.int,
                deviceId = json.getValue("device_id").jsonPrimitive.content,
                deviceName = json.getValue("device_name").jsonPrimitive.content,
                schema = json.getValue("schema").jsonPrimitive.int,
                nonce = Base64.getDecoder().decode(json.getValue("nonce").jsonPrimitive.content),
            )
        } catch (e: SerializationException) {
            throw SyncException(SyncException.DAMAGED, e)
        } catch (e: IllegalArgumentException) {
            throw SyncException(SyncException.DAMAGED, e)
        } catch (e: NoSuchElementException) {
            throw SyncException(SyncException.DAMAGED, e)
        }

        private fun aad(role: String) = "eksoda-sync-hello-v1:$role".toByteArray()
    }
}

/**
 * Frames sealed with the session's key. Each direction numbers its frames from 0 and the number
 * is the nonce, so a frame repeated, dropped or reordered does not open.
 */
internal class SecureChannel(
    private val frames: Frames,
    private val key: ByteArray,
    private val sending: Int,
) {
    private var sent = 0L
    private var received = 0L

    fun send(type: Byte, body: ByteArray) {
        frames.write(SyncCrypto.seal(key, nonce(sending, sent++), NO_AAD, byteArrayOf(type) + body))
    }

    /** (type, body); throws [SyncException.DAMAGED] for a frame that does not open. */
    fun receive(): Pair<Byte, ByteArray> {
        val plain = try {
            SyncCrypto.open(key, nonce(1 - sending, received++), NO_AAD, frames.read())
        } catch (e: GeneralSecurityException) {
            throw SyncException(SyncException.DAMAGED, e)
        }
        if (plain.isEmpty()) throw SyncException(SyncException.DAMAGED)
        return plain[0] to plain.copyOfRange(1, plain.size)
    }

    companion object {
        const val FROM_CLIENT = 0
        const val FROM_SERVER = 1
        private val NO_AAD = ByteArray(0)

        /** HKDF of the household key with both hellos' nonces: new for every session. */
        fun sessionKey(key: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
            SyncCrypto.hkdf(
                ikm = key,
                salt = clientNonce + serverNonce,
                info = "eksoda-sync-session-v1".toByteArray(),
                length = HouseholdKey.BYTES,
            )

        private fun nonce(direction: Int, counter: Long): ByteArray =
            ByteBuffer.allocate(SyncCrypto.NONCE_BYTES).putInt(direction).putLong(counter).array()
    }
}
