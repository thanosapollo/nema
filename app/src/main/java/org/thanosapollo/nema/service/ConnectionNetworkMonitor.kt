package org.thanosapollo.nema.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Observe only usable default-network transitions, not every capability emission.
 *
 * A transition is a new usable network or a change in the transports it carries. An always-on
 * VPN stays the app's default network, validated, while airplane mode removes and restores the
 * network underneath it; only its carried transports reveal that restoration.
 */
internal class ConnectionNetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val wake: () -> Unit,
) : AutoCloseable {
    private val gate = Any()
    private var registered = false
    private var closed = false
    private var usable: UsableNetwork? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(gate) {
                if (closed) return
                val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!available) {
                    if (usable?.network == network) usable = null
                    return
                }
                val next = UsableNetwork(network, TRANSPORTS.filterTo(mutableSetOf(), capabilities::hasTransport))
                if (usable != next) {
                    usable = next
                    wake()
                }
            }
        }
        override fun onLost(network: Network) {
            synchronized(gate) { if (usable?.network == network) usable = null }
        }
    }

    fun start() = synchronized(gate) {
        if (!closed && !registered) {
            registered = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
        }
    }

    override fun close() = synchronized(gate) {
        closed = true
        usable = null
        if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
    }
}

private data class UsableNetwork(val network: Network, val transports: Set<Int>)

private val TRANSPORTS = listOf(
    NetworkCapabilities.TRANSPORT_CELLULAR,
    NetworkCapabilities.TRANSPORT_WIFI,
    NetworkCapabilities.TRANSPORT_BLUETOOTH,
    NetworkCapabilities.TRANSPORT_ETHERNET,
    NetworkCapabilities.TRANSPORT_VPN,
    NetworkCapabilities.TRANSPORT_WIFI_AWARE,
    NetworkCapabilities.TRANSPORT_LOWPAN,
    NetworkCapabilities.TRANSPORT_USB,
)
