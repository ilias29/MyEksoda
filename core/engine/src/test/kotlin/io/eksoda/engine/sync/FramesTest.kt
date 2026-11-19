package io.eksoda.engine.sync

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException

class FramesTest {
    private val key = HouseholdKey.create()

    @Test
    fun `HKDF gives RFC 5869's first test vector`() {
        val okm = SyncCrypto.hkdf(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(13) { it.toByte() },
            info = ByteArray(10) { (0xf0 + it).toByte() },
            length = 42,
        )

        assertThat(okm.toHexString()).isEqualTo(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        )
    }

    @Test
    fun `a hello opens with the same key and role only`() {
        val hello = Hello(1, "d-1", "Pixel 8", 7, ByteArray(Hello.NONCE_BYTES) { 1 })
        val bytes = written { Hello.write(it, key, Hello.CLIENT, hello) }

        val read = Hello.read(reading(bytes), key, Hello.CLIENT)
        assertThat(read.deviceId).isEqualTo("d-1")
        assertThat(read.deviceName).isEqualTo("Pixel 8")
        assertThat(read.schema).isEqualTo(7)
        assertThat(read.nonce.toList()).isEqualTo(hello.nonce.toList())

        assertCode(SyncException.NOT_PAIRED) { Hello.read(reading(bytes), key, Hello.SERVER) }
        val other = HouseholdKey.create()
        assertCode(SyncException.NOT_PAIRED) { Hello.read(reading(bytes), other, Hello.CLIENT) }
        val refused = written { Hello.refuse(it) }
        assertCode(SyncException.NOT_PAIRED) { Hello.read(reading(refused), key, Hello.CLIENT) }
    }

    @Test
    fun `a hello that opens but is not one is damaged`() {
        val nonce = ByteArray(SyncCrypto.NONCE_BYTES)
        val aad = "eksoda-sync-hello-v1:client".toByteArray()
        val sealed = SyncCrypto.seal(key, nonce, aad, "{}".toByteArray())
        val bytes = written { it.write(nonce + sealed) }

        assertCode(SyncException.DAMAGED) { Hello.read(reading(bytes), key, Hello.CLIENT) }
    }

    @Test
    fun `session frames open in order only`() {
        val session = SecureChannel.sessionKey(key, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val bytes = written { frames ->
            val client = SecureChannel(frames, session, SecureChannel.FROM_CLIENT)
            client.send(1, "first".toByteArray())
            client.send(2, "second".toByteArray())
        }

        val server = SecureChannel(reading(bytes), session, SecureChannel.FROM_SERVER)
        val (type, body) = server.receive()
        assertThat(type).isEqualTo(1.toByte())
        assertThat(body.toString(Charsets.UTF_8)).isEqualTo("first")
        assertThat(server.receive().second.toString(Charsets.UTF_8)).isEqualTo("second")

        // The second frame alone is out of order: the first counter does not open it.
        val second = reading(bytes).also { it.read() }.read()
        val alone = written { it.write(second) }
        val fresh = SecureChannel(reading(alone), session, SecureChannel.FROM_SERVER)
        assertCode(SyncException.DAMAGED) { fresh.receive() }
    }

    @Test
    fun `an oversized frame is damaged and a cut one ends the stream`() {
        val huge = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(Int.MAX_VALUE) }
        assertCode(SyncException.DAMAGED) { reading(huge.toByteArray()).read() }

        val cut = written { it.write(ByteArray(10)) }.copyOf(8)
        assertFailure { reading(cut).read() }.isInstanceOf(EOFException::class)
    }

    private fun written(block: (Frames) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        block(Frames(ByteArrayInputStream(ByteArray(0)), out))
        return out.toByteArray()
    }

    private fun reading(bytes: ByteArray) =
        Frames(ByteArrayInputStream(bytes), ByteArrayOutputStream())

    private fun assertCode(code: String, block: () -> Unit) {
        assertFailure(block)
            .isInstanceOf(SyncException::class)
            .prop(SyncException::code)
            .isEqualTo(code)
    }
}
