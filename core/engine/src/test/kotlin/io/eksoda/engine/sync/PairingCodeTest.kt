package io.eksoda.engine.sync

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.startsWith
import org.junit.jupiter.api.Test

class PairingCodeTest {
    private val key = ByteArray(HouseholdKey.BYTES) { it.toByte() }

    @Test
    fun `a code reads back with its key, phone and address`() {
        val code = PairingCode(key, "d-1", "Pixel 8 Pro & co", "192.168.1.20:41234")

        val text = code.encode()
        val read = PairingCode.decode(text)

        assertThat(text).startsWith("eksoda-pair:1?k=")
        assertThat(read).isNotNull()
        assertThat(read!!.key.toList()).isEqualTo(key.toList())
        assertThat(read.deviceId).isEqualTo("d-1")
        assertThat(read.deviceName).isEqualTo("Pixel 8 Pro & co")
        assertThat(read.address).isEqualTo("192.168.1.20:41234")
    }

    @Test
    fun `a code without an address has none`() {
        val read = PairingCode.decode(PairingCode(key, "d-1", "Galaxy").encode())

        assertThat(read?.address).isNull()
    }

    @Test
    fun `text that is not a household code is not read`() {
        val valid = PairingCode(key, "d-1", "Galaxy").encode()
        listOf(
            "https://e-invoicing.gr/receipt/1",
            "eksoda-pair:2?k=AAAA&d=x",
            "eksoda-pair:1?d=d-1",
            "eksoda-pair:1?k=AAAA&d=d-1",
            "eksoda-pair:1?k=***&d=d-1",
            valid.substringBefore("&d="),
            valid.replace("&d=d-1", "&d=%20"),
        ).forEach { assertThat(PairingCode.decode(it)).isNull() }
    }

    @Test
    fun `a household key has 32 bytes, and its tag hides it`() {
        val first = HouseholdKey.create()
        val second = HouseholdKey.create()

        assertThat(first.size).isEqualTo(32)
        assertThat(first.toList()).isNotEqualTo(second.toList())
        assertThat(HouseholdKey.tag(first)).isEqualTo(HouseholdKey.tag(first.copyOf()))
        assertThat(HouseholdKey.tag(first).length).isEqualTo(16)
        assertThat(HouseholdKey.tag(first)).isNotEqualTo(HouseholdKey.tag(second))
        assertFailure { PairingCode(ByteArray(16), "d", "n") }
            .isInstanceOf(IllegalArgumentException::class)
    }
}
