package io.eksoda.core.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isTrue
import io.eksoda.core.data.FakeAndroidKeyStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** The database key, wrapped by a key of the (stand-in) Android Keystore. */
@RunWith(AndroidJUnit4::class)
class KeyManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyFile = File(context.noBackupFilesDir, "database.key")
    private lateinit var keys: KeyManager

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        keyFile.delete()
        keys = KeyManager(context)
    }

    @After
    fun tearDown() {
        keyFile.delete()
        FakeAndroidKeyStore.uninstall()
    }

    @Test
    fun `the first start makes a key, and every later start reads the same one`() {
        val first = keys.passphrase(databaseExists = false)
        assertThat(first.size).isEqualTo(32)
        assertThat(keyFile.exists()).isTrue()
        assertThat(FakeAndroidKeyStore.aliases()).isEqualTo(setOf("eksoda.database.wrapping"))
        // The file holds the key wrapped, never the key itself.
        assertThat(keyFile.readBytes().toList().windowed(32).none { it == first.toList() })
            .isTrue()

        assertThat(keys.passphrase(databaseExists = true).toList()).isEqualTo(first.toList())
        assertThat(KeyManager(context).passphrase(databaseExists = true).toList())
            .isEqualTo(first.toList())
    }

    @Test
    fun `a database without its key file is never given a new key`() {
        assertFailure { keys.passphrase(databaseExists = true) }
            .isInstanceOf(IllegalStateException::class)
        assertThat(keyFile.exists()).isFalse()
    }

    @Test
    fun `a key the Keystore no longer holds cannot be unwrapped`() {
        keys.passphrase(databaseExists = false)
        FakeAndroidKeyStore.lose("eksoda.database.wrapping")
        assertFailure { keys.passphrase(databaseExists = true) }
            .isInstanceOf(DatabaseKeyUnavailable::class)
    }

    @Test
    fun `a changed key file cannot be unwrapped`() {
        keys.passphrase(databaseExists = false)
        val bytes = keyFile.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        keyFile.writeBytes(bytes)
        assertFailure { keys.passphrase(databaseExists = true) }
            .isInstanceOf(DatabaseKeyUnavailable::class)
    }

    @Test
    fun `deleting all data deletes the key, and the next start makes another`() {
        val old = keys.passphrase(databaseExists = false)
        keys.deleteKey()
        assertThat(keyFile.exists()).isFalse()
        assertThat(FakeAndroidKeyStore.aliases()).isEqualTo(emptySet())
        assertThat(keys.passphrase(databaseExists = false).toList()).isNotEqualTo(old.toList())
    }

    @Test
    fun `SQLCipher gets the key as a raw hex key`() {
        val key = ByteArray(32) { it.toByte() }
        val raw = sqlCipherRawKey(key).toString(Charsets.US_ASCII)
        val hex = key.joinToString("") { "%02x".format(Locale.ROOT, it) }
        assertThat(raw).isEqualTo("x'$hex'")
        assertFailure { sqlCipherRawKey(ByteArray(16)) }
            .isInstanceOf(IllegalArgumentException::class)
    }
}
