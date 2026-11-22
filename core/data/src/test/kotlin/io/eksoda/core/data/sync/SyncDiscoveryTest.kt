package io.eksoda.core.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress

/** Finding this phone's Wi-Fi address and the household's phones, with Robolectric's NSD. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SyncDiscoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val discovery = SyncDiscovery(context)
    private val nsd get() = shadowOf(context.getSystemService(NsdManager::class.java))
    private val connectivity
        get() = shadowOf(context.getSystemService(ConnectivityManager::class.java))

    /** What [flow] emits, collected in the background as it comes. */
    private fun <T> TestScope.collect(flow: Flow<T>): List<T> {
        val values = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.toList(values) }
        return values
    }

    @Test
    fun `sync listens on the phone's private IPv4 address on Wi-Fi or Ethernet only`() = runTest {
        val addresses = collect(discovery.localAddress())
        val callback = connectivity.networkCallbacks.single()
        val network = ShadowNetwork.newInstance(1)

        callback.onCapabilitiesChanged(network, transport(NetworkCapabilities.TRANSPORT_WIFI))
        // IPv6 and public addresses are passed over.
        callback.onLinkPropertiesChanged(network, link("2001:db8::1", "8.8.4.4", "192.168.1.20"))
        callback.onCapabilitiesChanged(network, transport(NetworkCapabilities.TRANSPORT_CELLULAR))
        callback.onCapabilitiesChanged(network, transport(NetworkCapabilities.TRANSPORT_ETHERNET))
        callback.onLost(network)

        val wifi = ip("192.168.1.20")
        assertThat(addresses).isEqualTo(listOf(null, wifi, null, wifi, null))
    }

    @Test
    fun `a network with only public addresses is not listened on`() = runTest {
        val addresses = collect(discovery.localAddress())
        val callback = connectivity.networkCallbacks.single()
        val network = ShadowNetwork.newInstance(1)
        callback.onCapabilitiesChanged(network, transport(NetworkCapabilities.TRANSPORT_WIFI))
        callback.onLinkPropertiesChanged(network, link("8.8.4.4"))
        assertThat(addresses).isEqualTo(listOf(null))
    }

    @Test
    fun `the network callback goes when nobody needs the address`() = runTest {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.localAddress().collect {}
        }
        assertThat(connectivity.networkCallbacks.size).isEqualTo(1)
        job.cancel()
        assertThat(connectivity.networkCallbacks.size).isEqualTo(0)
    }

    @Test
    fun `a phone announces its id and household tag until it stops`() = runTest {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.announce(41234, "0123456789abcdef", "tag-1")
        }
        val name = service("Eksoda-01234567")
        val listener = checkNotNull(nsd.getRegistrationListener(name))
        val info = checkNotNull(nsd.getRegisteredServiceInfo(listener))
        assertThat(info.port).isEqualTo(41234)
        assertThat(info.attribute("d")).isEqualTo("0123456789abcdef")
        assertThat(info.attribute("h")).isEqualTo("tag-1")
        // What NSD reports back changes nothing.
        listener.onServiceRegistered(info)
        listener.onRegistrationFailed(info, NsdManager.FAILURE_INTERNAL_ERROR)

        job.cancel()
        assertThat(nsd.getRegistrationListener(name)).isNull()
        listener.onServiceUnregistered(info)
        listener.onUnregistrationFailed(info, NsdManager.FAILURE_INTERNAL_ERROR)
    }

    @Test
    fun `only the household's other phones are found, at a local address`() = runTest {
        val peers = collect(discovery.peers("tag-1", "phone-a"))
        val listener = discoveryListeners().single()
        listener.onDiscoveryStarted(TYPE)

        // Prefers the IPv4 address: a link-local IPv6 one needs its interface to be dialed.
        resolve(listener, "b", announced("phone-b", "tag-1", "fe80::1", "192.168.1.30"))
        resolve(listener, "c", announced("phone-c", "tag-2", "192.168.1.31"))
        resolve(listener, "self", announced("phone-a", "tag-1", "192.168.1.32"))
        resolve(listener, "public", announced("phone-d", "tag-1", "8.8.4.4"))
        resolve(listener, "no-id", announced(null, "tag-1", "192.168.1.33"))
        resolve(listener, "failed", resolved = null)
        val phoneB = PeerAddress("phone-b", ip("192.168.1.30"), 41234)
        assertThat(peers).isEqualTo(listOf(emptyMap(), mapOf("phone-b" to phoneB)))

        // A phone that leaves the network is gone; one never found changes nothing.
        listener.onServiceLost(service("Eksoda-c"))
        listener.onServiceLost(service("Eksoda-b"))
        assertThat(peers).isEqualTo(listOf(emptyMap(), mapOf("phone-b" to phoneB), emptyMap()))

        listener.onStartDiscoveryFailed(TYPE, NsdManager.FAILURE_INTERNAL_ERROR)
        listener.onStopDiscoveryFailed(TYPE, NsdManager.FAILURE_INTERNAL_ERROR)
        listener.onDiscoveryStopped(TYPE)
    }

    @Test
    fun `a phone with only a link-local IPv6 address is found there`() = runTest {
        val peers = collect(discovery.peers("tag-1", "phone-a"))
        val listener = discoveryListeners().single()
        resolve(listener, "b", announced("phone-b", "tag-1", "fe80::1"))
        assertThat(peers.last()["phone-b"]?.host).isEqualTo(ip("fe80::1"))
    }

    @Test
    fun `discovery stops when nobody looks any more`() = runTest {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            discovery.peers("tag-1", "phone-a").collect {}
        }
        assertThat(discoveryListeners().size).isEqualTo(1)
        job.cancel()
        assertThat(discoveryListeners().size).isEqualTo(0)
    }

    /** NSD finds the service "Eksoda-[name]", and resolves it to [resolved] (null: fails). */
    private fun resolve(
        listener: NsdManager.DiscoveryListener,
        name: String,
        resolved: NsdServiceInfo?,
    ) {
        val found = service("Eksoda-$name")
        listener.onServiceFound(found)
        val resolver = checkNotNull(nsd.getResolveListeners(found)).single()
        if (resolved == null) {
            resolver.onResolveFailed(found, NsdManager.FAILURE_INTERNAL_ERROR)
        } else {
            resolver.onServiceResolved(resolved)
        }
    }

    private fun discoveryListeners(): List<NsdManager.DiscoveryListener> =
        nsd.getDiscoveryListeners(TYPE).orEmpty()

    private fun service(name: String) = NsdServiceInfo().apply {
        serviceName = name
        serviceType = TYPE
    }

    private fun announced(device: String?, tag: String, vararg hosts: String) =
        NsdServiceInfo().apply {
            serviceName = "Eksoda-$device"
            serviceType = TYPE
            port = 41234
            device?.let { setAttribute("d", it) }
            setAttribute("h", tag)
            hostAddresses = hosts.map(::ip)
        }

    private fun NsdServiceInfo.attribute(key: String): String? =
        attributes[key]?.toString(Charsets.UTF_8)

    private fun transport(type: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(type) }

    /** Link properties with [addresses]; adding one is not public API, hence the reflection. */
    private fun link(vararg addresses: String): LinkProperties = LinkProperties().apply {
        addresses.forEach { text ->
            val address = ReflectionHelpers.callConstructor(
                LinkAddress::class.java,
                ClassParameter.from(InetAddress::class.java, ip(text)),
                ClassParameter.from(Int::class.javaPrimitiveType, PREFIX),
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                this,
                "addLinkAddress",
                ClassParameter.from(LinkAddress::class.java, address),
            )
        }
    }

    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    private companion object {
        const val TYPE = "_eksoda._tcp"
        const val PREFIX = 24
    }
}
