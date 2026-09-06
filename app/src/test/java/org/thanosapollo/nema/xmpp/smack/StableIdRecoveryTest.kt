package org.thanosapollo.nema.xmpp.smack

import java.net.SocketTimeoutException
import org.jivesoftware.smack.packet.StanzaBuilder
import org.junit.Assert.*
import org.junit.Test
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.xmpp.transport.TransientArchiveException

class StableIdRecoveryTest {
    private val attempt = SessionAttemptIdentity(
        AccountId.require("owner"), ConnectionGeneration.require(1),
        ConnectionAttempt.require(1), LifecycleEpoch.require(1),
    )

    @Test fun `discovery timeout then successful retry keeps fallback trust and live delivery`() {
        val gate = StableIdDiscoveryGate()
        gate.begin(attempt)
        failDiscovery(gate)
        assertEquals(false, gate.support(attempt))
        val recovered = resolveStableIdGateOnCapabilityFailure(gate, attempt, {}) {
            drainStableIdGate(gate, attempt, supported = true) {}
            "MAM supported"
        }
        assertEquals("MAM supported", recovered)
        assertLive(gate, supported = false)
        // Only an actual new attempt may establish a different trust policy.
        val successor = attempt.copy(generation = ConnectionGeneration.require(2))
        gate.begin(successor)
        drainStableIdGate(gate, successor, supported = true) {}
        drainStableIdGate(gate, attempt, supported = false) {}
        assertEquals(true, gate.support(successor))
        assertTrue(gate.accept(attempt, StanzaBuilder.buildMessage("stale").build()).isEmpty())
        assertTrue(gate.accept(successor, StanzaBuilder.buildMessage("new").build()).single().trustStableIds)
    }

    @Test fun `rediscovery timeout after success preserves trusted live gate`() {
        val gate = StableIdDiscoveryGate()
        gate.begin(attempt)
        drainStableIdGate(gate, attempt, supported = true) {}
        failDiscovery(gate)
        assertLive(gate, supported = true)
        resolveStableIdGateOnCapabilityFailure(gate, attempt, {}) {
            drainStableIdGate(gate, attempt, supported = true) {}
        }
        assertLive(gate, supported = true)
    }

    private fun failDiscovery(gate: StableIdDiscoveryGate) {
        val failure = SocketTimeoutException("synthetic timeout")
        val caught = runCatching {
            resolveStableIdGateOnCapabilityFailure(gate, attempt, {}) {
                archiveNetworkCall<Nothing> { throw failure }
            }
        }.exceptionOrNull()
        assertTrue("Original transient failure must survive gate settlement: $caught", caught is TransientArchiveException)
        assertSame(failure, caught?.cause)
    }

    private fun assertLive(gate: StableIdDiscoveryGate, supported: Boolean) {
        assertEquals(supported, gate.support(attempt))
        val message = StanzaBuilder.buildMessage("live").build()
        val delivery = gate.accept(attempt, message).single()
        assertSame(message, delivery.message)
        assertEquals(attempt, delivery.attempt)
        assertEquals(supported, delivery.trustStableIds)
    }
}
