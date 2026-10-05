package org.thanosapollo.nema.service

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConnectionNetworkMonitorTest {
    @Test fun usableDefaultNetworkTransitionsWakeOnceAndClosedCallbacksDoNothing() {
        val connectivity = ApplicationProvider.getApplicationContext<Application>().getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        var wakes = 0
        val monitor = ConnectionNetworkMonitor(connectivity) { wakes++ }
        monitor.start()
        monitor.start()
        assertEquals(1, shadow.networkCallbacks.size)
        val callback = shadow.networkCallbacks.single()
        val first = org.robolectric.shadows.ShadowNetwork.newInstance(123)
        val second = org.robolectric.shadows.ShadowNetwork.newInstance(456)
        val captive = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(captive).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val usable = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(usable).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(usable).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        callback.onAvailable(first)
        callback.onCapabilitiesChanged(first, captive)
        assertEquals(0, wakes)
        repeat(20) { callback.onCapabilitiesChanged(first, usable) }
        assertEquals(1, wakes)
        callback.onCapabilitiesChanged(second, usable)
        callback.onLost(first)
        callback.onCapabilitiesChanged(second, usable)
        assertEquals(2, wakes)
        callback.onCapabilitiesChanged(second, captive)
        callback.onCapabilitiesChanged(second, usable)
        assertEquals(3, wakes)
        callback.onLost(second)
        callback.onCapabilitiesChanged(second, usable)
        assertEquals(4, wakes)
        monitor.close()
        assertTrue(shadow.networkCallbacks.isEmpty())
        callback.onCapabilitiesChanged(first, usable)
        monitor.start()
        assertEquals(4, wakes)
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun alwaysOnVpnUnderlyingRestorationWakesWithoutDefaultNetworkIdentityChange() {
        // Device: an always-on VPN stays the app's default network and VALIDATED across airplane
        // mode; only the transports it carries change when the underlying network returns.
        val connectivity = ApplicationProvider.getApplicationContext<Application>().getSystemService(ConnectivityManager::class.java)
        var wakes = 0
        val monitor = ConnectionNetworkMonitor(connectivity) { wakes++ }
        monitor.start()
        val callback = shadowOf(connectivity).networkCallbacks.single()
        val vpn = org.robolectric.shadows.ShadowNetwork.newInstance(130)
        fun caps(vararg transports: Int, downKbps: Int = 8_781) =
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also { nc ->
                shadowOf(nc).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                shadowOf(nc).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                shadowOf(nc).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
                transports.forEach { shadowOf(nc).addTransportType(it) }
                shadowOf(nc).setLinkDownstreamBandwidthKbps(downKbps)
            }
        val cellular = intArrayOf(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_VPN)
        callback.onAvailable(vpn)
        callback.onCapabilitiesChanged(vpn, caps(*cellular))
        assertEquals(1, wakes)
        // Ordinary bandwidth churn on the same carried network is not a transition.
        repeat(5) { callback.onCapabilitiesChanged(vpn, caps(*cellular, downKbps = 1_000 + it)) }
        assertEquals(1, wakes)
        // Airplane mode: the VPN keeps its identity and validation but carries no underlying transport.
        callback.onCapabilitiesChanged(vpn, caps(NetworkCapabilities.TRANSPORT_VPN))
        repeat(3) { callback.onCapabilitiesChanged(vpn, caps(NetworkCapabilities.TRANSPORT_VPN)) }
        val beforeRestore = wakes
        // Mobile data returns underneath the same VPN network: recovery must be woken.
        callback.onCapabilitiesChanged(vpn, caps(*cellular))
        assertEquals(beforeRestore + 1, wakes)
        repeat(5) { callback.onCapabilitiesChanged(vpn, caps(*cellular)) }
        assertEquals(beforeRestore + 1, wakes)
        // A Wi-Fi handover underneath the VPN is also a usable-network transition.
        callback.onCapabilitiesChanged(vpn, caps(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_VPN))
        assertEquals(beforeRestore + 2, wakes)
        monitor.close()
    }
}
