package io.eksoda.core.data.sync

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import java.net.InetAddress

/** Which addresses household sync may use, and how a phone's address is stored. */
class SyncAddressTest {
    @Test
    fun `the address rule lets the local network through, nothing else`() {
        val policy = SyncAddressPolicy()
        val allowed = listOf("192.168.1.20", "10.0.0.2", "172.16.0.1", "169.254.1.1", "fe80::1")
        val refused = listOf("127.0.0.1", "8.8.8.8", "::1", "2001:db8::1")
        assertThat(allowed.map { policy.allows(InetAddress.getByName(it)) }.distinct())
            .containsExactly(true)
        assertThat(refused.map { policy.allows(InetAddress.getByName(it)) }.distinct())
            .containsExactly(false)
    }

    @Test
    fun `addresses are written and read back as stored`() {
        val v4 = PeerAddress.parse("d", "192.168.1.20:41234")
        val v6 = PeerAddress.parse("d", "[fe80::1]:41234")
        val scoped = PeerAddress.parse("d", "[fe80::1%1]:41234")

        assertThat(v4?.text()).isEqualTo("192.168.1.20:41234")
        assertThat(v6?.text()).isEqualTo("[fe80:0:0:0:0:0:0:1]:41234")
        // A link-local address keeps its interface, or it cannot be dialed again.
        assertThat(scoped?.text()).isEqualTo("[fe80:0:0:0:0:0:0:1%1]:41234")
        assertThat(PeerAddress.parse("d", "phone.local:41234")).isNull()
        assertThat(PeerAddress.parse("d", "192.168.1.20:99999")).isNull()
        assertThat(PeerAddress.parse("d", "192.168.1.20")).isNull()
    }
}
