package io.eksoda.core.data.sync

import io.eksoda.engine.sync.HouseholdKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A phone in household sync with its key in memory; [key] null: it holds none yet. */
internal class FakeSyncDevice(
    override val deviceId: String,
    override val deviceName: String,
    key: ByteArray?,
) : SyncDevice {
    private var current: ByteArray? = key?.copyOf()
    private val version = MutableStateFlow(if (key == null) null else 1)

    override val keyVersion: StateFlow<Int?> = version

    @Synchronized
    override fun key(): ByteArray? = current?.copyOf()

    @Synchronized
    override fun keyOrCreate(): ByteArray = key() ?: HouseholdKey.create().also(::replaceKey)

    @Synchronized
    override fun replaceKey(key: ByteArray) {
        // As strict as KeystoreSyncDevice, so a malformed key fails here as on a phone.
        require(key.size == HouseholdKey.BYTES) {
            "a household key is ${HouseholdKey.BYTES} bytes"
        }
        current = key.copyOf()
        version.value = (version.value ?: 0) + 1
    }

    @Synchronized
    override fun forgetKey() {
        current = null
        version.value = null
    }
}
