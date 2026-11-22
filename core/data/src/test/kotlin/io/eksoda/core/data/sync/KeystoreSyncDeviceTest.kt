package io.eksoda.core.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.eksoda.core.data.FakeAndroidKeyStore
import io.eksoda.engine.sync.HouseholdKey
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** This phone's household key, wrapped by a key of the (stand-in) Android Keystore. */
@RunWith(AndroidJUnit4::class)
class KeystoreSyncDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyFile = File(context.noBackupFilesDir, "sync.key")
    private val idFile = File(context.noBackupFilesDir, "sync.device")

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        keyFile.delete()
        idFile.delete()
    }

    @After
    fun tearDown() {
        keyFile.delete()
        idFile.delete()
        FakeAndroidKeyStore.uninstall()
    }

    @Test
    fun `a phone holds no key until sync is first turned on`() {
        val device = KeystoreSyncDevice(context)
        assertThat(device.keyVersion.value).isNull()
        assertThat(device.key()).isNull()

        val key = device.keyOrCreate()
        assertThat(key.size).isEqualTo(HouseholdKey.BYTES)
        assertThat(device.keyVersion.value).isEqualTo(1)
        // Made once: asking again gives the same key, at the same version.
        assertThat(device.keyOrCreate().toList()).isEqualTo(key.toList())
        assertThat(device.key()?.toList()).isEqualTo(key.toList())
        assertThat(device.keyVersion.value).isEqualTo(1)
    }

    @Test
    fun `the key outlives the app, wrapped`() {
        val key = KeystoreSyncDevice(context).keyOrCreate()
        val again = KeystoreSyncDevice(context)
        assertThat(again.keyVersion.value).isEqualTo(0)
        assertThat(again.key()?.toList()).isEqualTo(key.toList())
        assertThat(keyFile.readBytes().toList().windowed(key.size).none { it == key.toList() })
            .isTrue()
    }

    @Test
    fun `joining another household replaces the key and changes its version`() {
        val device = KeystoreSyncDevice(context)
        device.keyOrCreate()
        assertFailure { device.replaceKey(ByteArray(3)) }
            .isInstanceOf(IllegalArgumentException::class)

        val household = HouseholdKey.create()
        device.replaceKey(household)
        assertThat(device.key()?.toList()).isEqualTo(household.toList())
        assertThat(device.keyVersion.value).isEqualTo(2)
    }

    @Test
    fun `leaving forgets the key and its Keystore entry`() {
        val device = KeystoreSyncDevice(context)
        device.keyOrCreate()
        device.forgetKey()
        assertThat(device.key()).isNull()
        assertThat(device.keyVersion.value).isNull()
        assertThat(keyFile.exists()).isFalse()
        assertThat(FakeAndroidKeyStore.aliases()).isEqualTo(emptySet())
    }

    @Test
    fun `a key whose Keystore entry is gone is forgotten, and joining again brings one`() {
        val device = KeystoreSyncDevice(context)
        device.keyOrCreate()
        FakeAndroidKeyStore.lose("eksoda.sync.wrapping")
        assertThat(device.key()).isNull()
        assertThat(device.keyVersion.value).isNull()
        assertThat(keyFile.exists()).isFalse()
        assertThat(device.keyOrCreate().size).isEqualTo(HouseholdKey.BYTES)
    }

    @Test
    fun `a changed key file is forgotten`() {
        val device = KeystoreSyncDevice(context)
        device.keyOrCreate()
        val bytes = keyFile.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        keyFile.writeBytes(bytes)
        assertThat(device.key()).isNull()
        assertThat(device.keyVersion.value).isNull()
    }

    @Test
    fun `the device id is made once per install and the name is never blank`() {
        val id = KeystoreSyncDevice(context).deviceId
        assertThat(id).isNotEmpty()
        assertThat(KeystoreSyncDevice(context).deviceId).isEqualTo(id)
        assertThat(KeystoreSyncDevice(context).deviceName.isNotBlank()).isTrue()
        // An empty id file is not an id.
        idFile.writeText("  ")
        assertThat(KeystoreSyncDevice(context).deviceId.isNotBlank()).isTrue()
        assertThat(KeystoreSyncDevice(context).deviceId).isNotEqualTo(id)
    }
}
