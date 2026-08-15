package org.thanosapollo.nema.xmpp.smack

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLossNotifierTest {
    @Test
    fun `graceful and error close notify once while local close notifies zero`() {
        var losses = 0
        var usable = false
        val notifier = ConnectionLossNotifier({ usable }) { losses++ }

        notifier.remoteClosed()
        notifier.remoteClosed()
        assertEquals(1, losses)

        notifier.connected()
        notifier.localDisconnect()
        notifier.remoteClosed()
        assertEquals(1, losses)
    }

    @Test
    fun `delayed old close is ignored after a later transport is usable`() {
        var losses = 0
        var usable = true
        val notifier = ConnectionLossNotifier({ usable }) { losses++ }

        notifier.remoteClosed()
        assertEquals(0, losses)

        usable = false
        notifier.remoteClosed()
        assertEquals(1, losses)
    }

    @Test
    fun `queued duplicate stays suppressed while replacement attempt connects`() {
        var losses = 0
        val notifier = ConnectionLossNotifier({ false }) { losses++ }

        notifier.remoteClosed()
        notifier.attemptStarting()
        notifier.remoteClosed()

        assertEquals(1, losses)
    }

    @Test
    fun `close queued by a directly failed attempt cannot become replacement loss`() {
        var losses = 0
        var usable = false
        val notifier = ConnectionLossNotifier({ usable }) { losses++ }

        notifier.attemptFailed()
        notifier.attemptStarting()
        notifier.remoteClosed()
        assertEquals(0, losses)

        usable = true
        notifier.connected()
        usable = false
        notifier.remoteClosed()
        assertEquals(1, losses)
    }
}