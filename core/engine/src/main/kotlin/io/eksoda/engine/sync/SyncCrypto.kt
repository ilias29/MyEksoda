package io.eksoda.engine.sync

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The household sync key: 32 random bytes every phone of the household holds. */
public object HouseholdKey {
    public const val BYTES: Int = 32
    private const val TAG_BYTES = 8
    private val TAG_INFO = "eksoda-household-tag-v1".toByteArray()

    public fun create(): ByteArray = SyncCrypto.randomBytes(BYTES)

    /**
     * What a phone announces on the network so phones of the same household find each other
     * without connecting: a short HMAC of a constant under the key, which says nothing of the key.
     */
    public fun tag(key: ByteArray): String =
        SyncCrypto.hmac(key, TAG_INFO).copyOf(TAG_BYTES).toHexString()
}

/** HKDF-SHA256 (RFC 5869) and AES-256-GCM, from the JVM's providers. */
internal object SyncCrypto {
    const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val HMAC = "HmacSHA256"
    private const val AES = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
        init(SecretKeySpec(key, HMAC))
        doFinal(data)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val pseudorandom = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(pseudorandom, block + info + counter.toByte())
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    /** Throws `AEADBadTagException` (a `GeneralSecurityException`) for a wrong key or data. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance(AES).apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
}
