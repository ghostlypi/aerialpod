package org.aerialpod.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities

/**
 * The default network's path, reported to the peer service.
 *
 * `docs/mobile-lan-sync.md` is explicit that **any** path change triggers a
 * dial, with no metered or WiFi-only gate: on cellular without a tunnel the
 * dial fails outright — carriers do not route RFC1918 — so the cost is one TCP
 * SYN and no snapshot, while a WireGuard tunnel is exactly the case where the
 * user does want the sync. A gate would have bought a settings toggle and two
 * capability APIs in exchange for breaking the tunnel case.
 *
 * The service debounces callbacks by 2 s, because one WiFi association fires
 * several in a row. This class does the other half of that job: it reports only
 * changes that could plausibly alter reachability, rather than every
 * `onCapabilitiesChanged` — which fires on things like signal strength and
 * would otherwise keep the debounce window permanently open.
 */
class NetworkMonitor(
    context: Context,
    private val onPathChanged: () -> Unit,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private var lastNetwork: Network? = null
    private var lastValidated: Boolean? = null
    private var lastAddresses: List<String> = emptyList()

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            if (lastNetwork != network) {
                lastNetwork = network
                report()
            }
        }

        override fun onLost(network: Network) {
            lastNetwork = null
            lastValidated = null
            lastAddresses = emptyList()
            // Still worth reporting: it resets the service's backoff, so the
            // next network that arrives dials immediately instead of waiting
            // out a long retry from the one that just died.
            report()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated != lastValidated) {
                lastValidated = validated
                if (validated) report()
            }
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            // DHCP completing is what actually makes a LAN address reachable,
            // and it arrives here rather than in onAvailable.
            val addresses = properties.linkAddresses.map { it.address.hostAddress.orEmpty() }.sorted()
            if (addresses != lastAddresses) {
                lastAddresses = addresses
                report()
            }
        }
    }

    private var registered = false

    fun start() {
        if (registered) return
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
    }

    fun stop() {
        if (!registered) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun report() = onPathChanged()
}
