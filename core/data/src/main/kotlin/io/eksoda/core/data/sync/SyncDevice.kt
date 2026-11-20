package io.eksoda.core.data.sync

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import io.eksoda.engine.sync.HouseholdKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** This phone in household sync: its id and name, and the household key it holds. */
interface SyncDevice {
    /** Random, made once per install; other phones know this phone by it. */
    val deviceId: String

    /** What other phones show for this one: the device model. */
    val deviceName: String

    /**
     * Null while this phone holds no household key, else a number that changes whenever the
     * key does (joining another household), so whoever uses the key knows to start over.
     * Does not unwrap the key.
     */
    val keyVersion: StateFlow<Int?>

    /** The household key, or null when this phone has none (or it can no longer be unwrapped). */
    fun key(): ByteArray?

    /** The key, made now when this phone is the first of its household to turn sync on. */
    fun keyOrCreate(): ByteArray

    /** Joining a household: its key replaces this phone's. */
    fun replaceKey(key: ByteArray)

    /** Leaving household sync. */
    fun forgetKey()
}

/**
 * The household key wrapped with an AES-256-GCM key of the Android Keystore, like the database
 * key (KeyManager) but under its own alias and file, in `noBackupFilesDir`: a backup or a new
 * phone never carries it, joining again does.
 */
@Singleton
class KeystoreSyncDevice @Inject constructor(@ApplicationContext context: Context) : SyncDevice {
    private val keyFile = File(context.noBackupFilesDir, KEY_FILE)
    private val idFile = File(context.noBackupFilesDir, ID_FILE)
    private val state by lazy { MutableStateFlow(if (keyFile.exists()) 0 else null) }

    override val deviceId: String by lazy {
        synchronized(this) {
            idFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString().also { write(idFile, it.toByteArray()) }
        }
    }

    override val deviceName: String = Build.MODEL.orEmpty().ifBlank { "Android" }

    override val keyVersion: StateFlow<Int?> get() = state.asStateFlow()

    @Synchronized
    override fun key(): ByteArray? {
        if (!keyFile.exists()) return null
        return try {
            val bytes = keyFile.readBytes()
            val wrapping = existingWrappingKey() ?: run {
                // The Keystore lost the wrapping key: this key cannot be read ever again.
                forgetKey()
                return null
            }
            val nonce = GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES)
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, wrapping, nonce)
                doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)
            }
        } catch (ignored: GeneralSecurityException) {
            // The Keystore was reset: the key is gone for good, and joining again brings it back.
            forgetKey()
            null
        }
    }

    @Synchronized
    override fun keyOrCreate(): ByteArray = key() ?: HouseholdKey.create().also(::store)

    @Synchronized
    override fun replaceKey(key: ByteArray) {
        require(key.size == HouseholdKey.BYTES) { "a household key is ${HouseholdKey.BYTES} bytes" }
        store(key)
    }

    @Synchronized
    override fun forgetKey() {
        keyFile.delete()
        keyStore().deleteEntry(KEY_ALIAS)
        state.value = null
    }

    private fun store(key: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingWrappingKey() ?: newWrappingKey())
        write(keyFile, cipher.iv + cipher.doFinal(key))
        state.value = (state.value ?: 0) + 1
    }

    private fun write(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(file)) { "could not store ${file.name}" }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingWrappingKey(): SecretKey? =
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun newWrappingKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_BITS)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "eksoda.sync.wrapping"
        const val KEY_FILE = "sync.key"
        const val ID_FILE = "sync.device"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val AES_KEY_BITS = 256
    }
}
