package org.thanosapollo.nema.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Observe only usable default-network transitions, not every capability emission. */
internal class ConnectionNetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val wake: () -> Unit,
) : AutoCloseable {
    private val gate = Any()
    private var registered = false
    private var closed = false
    private var usable: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(gate) {
                if (closed) return
                val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!available) {
                    if (usable == network) usable = null
                } else if (usable != network) {
                    usable = network
                    wake()
                }
            }
        }
        override fun onLost(network: Network) {
            synchronized(gate) { if (usable == network) usable = null }
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
