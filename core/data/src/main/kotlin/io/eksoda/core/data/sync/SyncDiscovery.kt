package io.eksoda.core.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.eksoda.engine.sync.LocalNetwork
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Finds the household's phones on the local network with NSD (mDNS / DNS-SD, part of Android,
 * no Google Play services). Each phone announces `_eksoda._tcp` with its device id and the
 * household tag (`HouseholdKey.tag`), so phones of other households are skipped without
 * connecting. Nothing here leaves the local network.
 */
@Singleton
class SyncDiscovery @Inject constructor(@ApplicationContext context: Context) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val connectivity: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

    /**
     * This phone's private IPv4 address on Wi-Fi or Ethernet, as the default network changes;
     * null on mobile data or with no network. Sync listens only there: never on a carrier's
     * network, whose addresses can look private too.
     */
    fun localAddress(): Flow<Inet4Address?> = callbackFlow {
        val manager = connectivity ?: run {
            send(null)
            awaitClose()
            return@callbackFlow
        }
        var capabilities: NetworkCapabilities? = null
        var link: LinkProperties? = null
        val update = { trySend(wifiAddress(capabilities, link)) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                capabilities = caps
                update()
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                link = properties
                update()
            }

            override fun onLost(network: Network) {
                capabilities = null
                link = null
                update()
            }
        }
        send(null)
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun wifiAddress(caps: NetworkCapabilities?, link: LinkProperties?): Inet4Address? {
        val local = caps != null &&
            (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
        if (!local) return null
        return link?.linkAddresses?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull(LocalNetwork::isLocal)
    }

    /** Announces this phone until cancelled. */
    suspend fun announce(port: Int, deviceId: String, tag: String) {
        val manager = nsd ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "Eksoda-${deviceId.take(SHORT_ID)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(DEVICE, deviceId)
            setAttribute(TAG, tag)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            awaitCancellation()
        } finally {
            runCatching { manager.unregisterService(listener) }
        }
    }

    /** The household's other phones on this network now, by device id, until cancelled. */
    fun peers(tag: String, selfId: String): Flow<Map<String, PeerAddress>> = callbackFlow {
        val manager = nsd ?: run {
            send(emptyMap())
            awaitClose()
            return@callbackFlow
        }
        // Service name → the phone it announces; resolved one at a time (older Androids
        // refuse a second resolve while one runs).
        val found = ConcurrentHashMap<String, PeerAddress>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val emit = { trySend(found.values.associateBy { it.deviceId.orEmpty() }) }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) emit()
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
        }
        launch {
            for (info in toResolve) {
                val peer = resolve(manager, info)?.let { household(it, tag, selfId) }
                if (peer != null) {
                    found[info.serviceName] = peer
                    emit()
                }
            }
        }
        send(emptyMap())
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
    }

    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(resolved)
                }

                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            // Deprecated on Android 14 for ServiceInfoCallback; it still works on every version.
            @Suppress("DEPRECATION")
            manager.resolveService(info, listener)
        }

    /** A phone of this household on the local network, or null. */
    private fun household(info: NsdServiceInfo, tag: String, selfId: String): PeerAddress? {
        val attributes = info.attributes
        val device = attributes[DEVICE]?.toString(Charsets.UTF_8) ?: return null
        val itsTag = attributes[TAG]?.toString(Charsets.UTF_8)
        if (device == selfId || itsTag != tag) return null
        // IPv4 first: a link-local IPv6 address needs its interface to be dialed.
        val host = addresses(info).filter(LocalNetwork::isLocal)
            .minByOrNull { if (it is Inet4Address) 0 else 1 }
            ?: return null
        return PeerAddress(device, host, info.port)
    }

    private fun addresses(info: NsdServiceInfo): List<InetAddress> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }

    private companion object {
        const val SERVICE_TYPE = "_eksoda._tcp"
        const val DEVICE = "d"
        const val TAG = "h"
        const val SHORT_ID = 8
    }
}
