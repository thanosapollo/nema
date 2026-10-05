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
}
