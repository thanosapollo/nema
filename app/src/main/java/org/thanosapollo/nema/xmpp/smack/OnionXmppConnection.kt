package org.thanosapollo.nema.xmpp.smack

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.packet.Mechanisms
import org.jivesoftware.smack.packet.Nonza
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.StartTls
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jxmpp.jid.parts.Resourcepart
import org.thanosapollo.nema.session.SessionAttemptIdentity

/** Smack's SocketFuture returns the exact factory socket. One factory, one physical attempt,
 * no ProxyInfo and a literal hostAddress make that binding explicit without reflective access.
 * Tor authenticates the onion under the trusted-local-Orbot boundary; SOCKS itself does not.
 */
internal class OnionXmppConnection(
    configuration: XMPPTCPConnectionConfiguration,
    private val route: TorSocketFactory,
) : XMPPTCPConnection(configuration) {
    private class Negotiation(val identity: SessionAttemptIdentity) {
        val features = CountDownLatch(1)
        @Volatile var tlsAttempted = false
        @Volatile var tlsEstablished = false
        @Volatile var absentStartTls = false
    }
    @Volatile private var negotiation: Negotiation? = null

    // Smack may deliver its close callback before connect() returns the TLS exception.
    // That callback must not race the terminal result with a retryable NETWORK event.
    val hasIncompleteTlsNegotiation: Boolean
        get() = negotiation?.let { it.tlsAttempted && !it.tlsEstablished } == true

    init {
        require(configuration.socketFactory === route && route.onionEligible)
        require(configuration.securityMode == ConnectionConfiguration.SecurityMode.ifpossible)
        require(configuration.proxyInfo == null && configuration.hostAddress?.isLoopbackAddress == true)
    }

    fun beginAttempt(identity: SessionAttemptIdentity) {
        route.beginAttempt(identity)
        negotiation = Negotiation(identity)
    }

    fun permitsTransport(identity: SessionAttemptIdentity): Boolean {
        val current = negotiation ?: return false
        return current.identity == identity && route.hasOnionProof(identity) &&
            (isSecureConnection || (current.absentStartTls && !current.tlsAttempted))
    }

    private fun requireTransport() {
        if (negotiation?.let { permitsTransport(it.identity) } != true) {
            throw IOException("Onion transport authorization unavailable")
        }
    }

    override fun connectInternal() {
        try {
            check(negotiation != null) { "Onion attempt not started" }
            super.connectInternal()
            // Smack wakes connect() before afterFeaturesReceived on an absent-STARTTLS stream.
            // Wait for positive feature processing, never infer permission from !isSecureConnection.
            if (negotiation?.features?.await(replyTimeout, TimeUnit.MILLISECONDS) != true) {
                throw IOException("Onion stream negotiation timed out")
            }
            requireTransport()
        } catch (failure: Exception) {
            route.invalidateAttempt()
            // Smack reports a TLS <failure/> as generic SmackMessageException. Do not let the
            // controller treat it as retryable NETWORK and then accept absent TLS on a retry.
            if (hasIncompleteTlsNegotiation) throw javax.net.ssl.SSLException("Onion TLS negotiation failed", failure)
            throw failure
        }
    }

    override fun afterFeaturesReceived() {
        val current = negotiation ?: throw IOException("Onion attempt not started")
        if (hasFeature(StartTls.ELEMENT, StartTls.NAMESPACE)) current.tlsAttempted = true
        super.afterFeaturesReceived()
        if (hasFeature(Mechanisms.ELEMENT, Mechanisms.NAMESPACE) &&
            !hasFeature(StartTls.ELEMENT, StartTls.NAMESPACE)) {
            current.tlsEstablished = isSecureConnection
            current.absentStartTls = !current.tlsAttempted && !isSecureConnection
            current.features.countDown()
        }
    }

    override fun loginInternal(username: String?, password: String?, resource: Resourcepart?) {
        try {
            requireTransport()
            super.loginInternal(username, password, resource)
            requireTransport()
        } catch (failure: Exception) {
            route.invalidateAttempt()
            throw failure
        }
    }

    override fun sendNonza(nonza: Nonza) {
        if (nonza is StartTls) negotiation?.tlsAttempted = true
        if (nonza.namespace == "urn:ietf:params:xml:ns:xmpp-sasl") requireTransport()
        super.sendNonza(nonza)
    }

    override fun sendStanzaInternal(stanza: Stanza) {
        requireTransport()
        super.sendStanzaInternal(stanza)
    }

    override fun shutdown() {
        route.invalidateAttempt()
        super.shutdown()
    }

    override fun instantShutdown() {
        route.invalidateAttempt()
        negotiation?.features?.countDown()
        super.instantShutdown()
    }
}
