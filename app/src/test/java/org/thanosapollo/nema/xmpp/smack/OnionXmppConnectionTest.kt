package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.filter.StanzaTypeFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.*
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.xmpp.transport.*

internal const val VALID_ONION = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
private val localAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
private val fixtureAccount = AccountId.require("onion-wire-fixture")
private val initialOwner = SessionIdentity(fixtureAccount, ConnectionGeneration.require(1))
private fun attempt(number: Long = 1) = SessionAttemptIdentity(fixtureAccount,
    ConnectionGeneration.require(number), ConnectionAttempt.require(number), LifecycleEpoch.require(1))
private fun account() = AccountConfiguration.create(fixtureAccount, "fixture@$VALID_ONION", "fixture", null, VALID_ONION, null)
private fun route(proxy: SocksFixture, timeout: Int = 2_000) = TorSocketFactory(
    NetworkEndpoint.create(VALID_ONION, 5222), proxy.address, timeout, initialOwner, VALID_ONION)
private fun connection(route: TorSocketFactory) = OnionXmppConnection(
    SmackSessionConnectionFactory.configurationFor(account(), route), route).apply {
        replyTimeout = 3_000
        setUseStreamManagement(false)
        setUseStreamManagementResumption(false)
    }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OnionXmppConnectionTest {
    @Test fun repeatedPhysicalReconnectsRetainExactOnionProof() {
        repeat(32) { actualSessionAuthenticatesAndExchangesMessageWithoutStartTlsThenReconnects() }
    }

    @Test fun actualSessionAuthenticatesAndExchangesMessageWithoutStartTlsThenReconnects() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val credentials = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()
        SocksFixture { socket, _ ->
            try { serveXmpp(socket, credentials, messages) } catch (failure: Exception) {
                println("ONION_PEER_FAILURE ${failure.javaClass.name} ${failure.stackTrace.toList()}")
                throw failure
            }
        }.use { proxy ->
            lateinit var route: TorSocketFactory
            lateinit var connection: OnionXmppConnection
            val echoed = java.util.concurrent.LinkedBlockingQueue<String>()
            val session = SmackSessionConnectionFactory { "fixture-only".toCharArray() }.createSession(account(), initialOwner, {}) { owner ->
                route = TorSocketFactory(NetworkEndpoint.create(VALID_ONION, 5222), proxy.address, 2_000, owner, VALID_ONION)
                connection = connection(route)
                connection.addAsyncStanzaListener({ stanza ->
                    if ((stanza as Message).body == "fixture-message") echoed.add("fixture-message")
                }, StanzaTypeFilter.MESSAGE)
                route to connection
            }
            try {
                session.connect("fixture-only".toCharArray(), attempt())
                assertTrue(session.isUsable)
                assertTrue(session.onionWithoutTls)
                assertFalse(connection.isSecureConnection)
                assertTrue(route.hasOnionProof(attempt()))
                session.send(OutgoingMessageEnvelope(fixtureAccount, attempt().generation, 1, "op1", "origin1",
                    "peer@$VALID_ONION", "fixture-message", null)) {}
                assertEquals("fixture-message", echoed.poll(3, TimeUnit.SECONDS))
                // Physical replacement, not a logical relabel of the existing route.
                val oldNative = connection
                val oldRoute = route
                connection.disconnect()
                assertFalse(route.hasOnionProof(attempt()))
                session.updateAttempt(attempt(2))
                assertFalse(session.isUsable)
                session.reconnect(attempt(2))
                assertNotSame(oldNative, connection)
                assertNotSame(oldRoute, route)
                assertFalse(oldRoute.hasOnionProof(attempt()))
                assertFalse(oldRoute.hasOnionProof(attempt(2)))
                assertTrue(session.isUsable)
                assertFalse(route.hasOnionProof(attempt()))
                assertTrue(route.hasOnionProof(attempt(2)))
                session.send(OutgoingMessageEnvelope(fixtureAccount, attempt(2).generation, 1, "op2", "origin2",
                    "peer@$VALID_ONION", "fixture-message", null)) {}
                assertEquals("fixture-message", echoed.poll(3, TimeUnit.SECONDS))
                assertEquals(listOf("\u0000fixture\u0000fixture-only", "\u0000fixture\u0000fixture-only"), credentials.toList())
                assertEquals(2, messages.size)
                assertEquals(listOf(VALID_ONION to 5222, VALID_ONION to 5222), proxy.destinations.toList())
                session.revoke()
                assertFalse(route.hasOnionProof(attempt(2)))
                assertFalse(session.isUsable)
                assertFalse(session.onionWithoutTls)
            } finally { session.revoke(); session.disconnect() }
        }
    }

    @Test fun directTlsReplacementUsesFreshNativeOwnerAndFreshCredential() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val domain = "example.org"
        val config = AccountConfiguration.create(fixtureAccount, "fixture@$domain", "fixture", null, domain, null)
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(domain).build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(cert).addTrustedCertificate(cert.certificate).build()
        val credentials = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()
        val peers = CopyOnWriteArrayList<Socket>()
        val native = CopyOnWriteArrayList<org.jivesoftware.smack.tcp.XMPPTCPConnection>()
        val echoes = java.util.concurrent.LinkedBlockingQueue<String>()
        val acquired = CopyOnWriteArrayList<CharArray>()
        val server = java.net.ServerSocket(0, 16, localAddress)
        val executor = Executors.newCachedThreadPool()
        executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                peers += socket
                executor.submit {
                    socket.use {
                        try {
                            socket.soTimeout = 3_000
                            readThrough(socket, ">", "<stream:stream")
                            send(socket, stream(domain) + "<stream:features><starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/></stream:features>")
                            check(element(socket).contains("starttls"))
                            send(socket, "<proceed xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>")
                            (certificates.sslSocketFactory().createSocket(socket, domain, server.localPort, true) as SSLSocket).use { tls ->
                                tls.useClientMode = false
                                tls.startHandshake()
                                serveXmpp(tls, credentials, messages, domain)
                            }
                        } catch (failure: Exception) {
                            println("DIRECT_PEER_FAILURE ${failure.javaClass.name} ${failure.stackTrace.toList()}")
                        }
                    }
                }
            }
        }
        val session = SmackSessionConnectionFactory { owner ->
            assertEquals(attempt(2), owner)
            "fixture-only".toCharArray().also(acquired::add)
        }.createSession(config, initialOwner, {}) {
            val production = SmackSessionConnectionFactory.configurationFor(config)
            val connection = org.jivesoftware.smack.tcp.XMPPTCPConnection(
                org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration.builder()
                    .setXmppDomain(domain).setUsernameAndPassword("fixture", null)
                    .setSecurityMode(production.securityMode).setHostnameVerifier(production.hostnameVerifier)
                    .setHostAddress(localAddress).setPort(server.localPort)
                    .setCustomX509TrustManager(certificates.trustManager).build()).apply { replyTimeout = 3_000 }
            connection.addAsyncStanzaListener({ stanza ->
                if ((stanza as Message).body == "fixture-message") echoes.add("echo")
            }, StanzaTypeFilter.MESSAGE)
            native += connection
            null to connection
        }
        try {
            session.connect("fixture-only".toCharArray(), attempt())
            for (number in 1L..2L) {
                if (number == 2L) session.reconnect(attempt(2))
                val connection = native.last()
                assertTrue(session.isUsable && connection.isSecureConnection)
                assertFalse(session.onionWithoutTls)
                for (flag in listOf("useSm", "useSmResumption")) {
                    assertFalse(org.jivesoftware.smack.tcp.XMPPTCPConnection::class.java
                        .getDeclaredField(flag).apply { isAccessible = true }.getBoolean(connection))
                }
                assertFalse(connection.isSmEnabled)
                assertFalse(connection.isDisconnectedButSmResumptionPossible)
                assertEquals(org.jivesoftware.smackx.receipts.DeliveryReceiptManager.AutoReceiptMode.disabled,
                    org.jivesoftware.smackx.receipts.DeliveryReceiptManager.getInstanceFor(connection).autoReceiptMode)
                session.send(OutgoingMessageEnvelope(fixtureAccount, attempt(number).generation, 1, "direct-$number", "origin-$number",
                    "peer@$domain", "fixture-message", null)) {}
                assertEquals("echo", echoes.poll(3, TimeUnit.SECONDS))
            }
            assertNotSame(native.first(), native.last())
            assertFalse(native.first().isConnected)
            assertEquals(2, credentials.size)
            assertTrue(acquired.single().all { it == '\u0000' })
        } finally {
            session.revoke(); session.disconnect()
            server.close(); peers.forEach { runCatching { it.close() } }
            executor.shutdownNow(); assertTrue(executor.awaitTermination(4, TimeUnit.SECONDS))
        }
    }

    @Test fun offeredTlsWithUntrustedCertificateFailsWithoutAuthOrPlaintextRetry() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val tlsRequested = CountDownLatch(1)
        val credentials = CopyOnWriteArrayList<String>()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(VALID_ONION).build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(cert).build()
        SocksFixture { socket, _ ->
            readThrough(socket, ">", "<stream:stream")
            send(socket, stream() + "<stream:features><starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>" + mechanisms() + "</stream:features>")
            val first = element(socket)
            if (first.contains("<auth")) credentials += first
            check(first.contains("starttls"))
            tlsRequested.countDown()
            send(socket, "<proceed xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>")
            (certificates.sslSocketFactory().createSocket(socket, VALID_ONION, 5222, true) as SSLSocket).use {
                it.useClientMode = false
                it.startHandshake()
                credentials += element(it)
            }
        }.use { proxy ->
            route(proxy).use { route ->
                val connection = connection(route)
                connection.beginAttempt(attempt())
                try {
                    val failure = runCatching { connection.connect(); connection.login("fixture", "fixture-only") }.exceptionOrNull()
                    assertNotNull(failure)
                    assertTrue(tlsRequested.await(1, TimeUnit.SECONDS))
                    assertEquals(SessionFailureReason.TLS_CERTIFICATE, classifySmackFailure(failure as Exception))
                    assertFalse(connection.isAuthenticated)
                    assertFalse(route.hasOnionProof(attempt()))
                    assertFalse(connection.permitsTransport(attempt()))
                    assertEquals(1, proxy.destinations.size)
                    assertTrue(credentials.isEmpty())
                } finally { connection.instantShutdown() }
            }
        }
    }

    @Test fun offeredTrustedTlsAuthenticatesButWrongServiceCertificateNeverDoes() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        for (certificateDomain in listOf(VALID_ONION, "wrong.invalid")) {
            val cert = HeldCertificate.Builder().addSubjectAlternativeName(certificateDomain).build()
            val certificates = HandshakeCertificates.Builder().heldCertificate(cert).addTrustedCertificate(cert.certificate).build()
            val credentials = CopyOnWriteArrayList<String>()
            val messages = CopyOnWriteArrayList<String>()
            SocksFixture { socket, _ ->
                readThrough(socket, ">", "<stream:stream")
                send(socket, stream() + "<stream:features><starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/></stream:features>")
                check(element(socket).contains("starttls"))
                send(socket, "<proceed xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>")
                (certificates.sslSocketFactory().createSocket(socket, VALID_ONION, 5222, true) as SSLSocket).use {
                    it.useClientMode = false
                    it.startHandshake()
                    serveXmpp(it, credentials, messages)
                }
            }.use { proxy ->
                route(proxy).use { route ->
                    val production = SmackSessionConnectionFactory.configurationFor(account(), route)
                    // Preserve production security mode and domain verifier; add only a private fixture CA.
                    val config = org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration.builder()
                        .setXmppDomain(VALID_ONION).setUsernameAndPassword("fixture", null)
                        .setSecurityMode(production.securityMode).setHostnameVerifier(production.hostnameVerifier)
                        .setHostAddress(production.hostAddress).setSocketFactory(production.socketFactory)
                        .setCustomX509TrustManager(certificates.trustManager).build()
                    val connection = OnionXmppConnection(config, route).apply {
                        replyTimeout = 3_000; setUseStreamManagement(false); setUseStreamManagementResumption(false)
                    }
                    try {
                        connection.beginAttempt(attempt())
                        val result = runCatching { connection.connect(); connection.login("fixture", "fixture-only") }
                        if (certificateDomain == VALID_ONION) {
                            result.getOrThrow()
                            assertTrue(connection.isSecureConnection)
                            assertFalse(connection.hasIncompleteTlsNegotiation)
                            assertTrue(connection.isAuthenticated)
                            assertTrue(connection.permitsTransport(attempt()))
                            assertEquals(1, credentials.size)
                        } else {
                            assertTrue(result.isFailure)
                            assertFalse(connection.isAuthenticated)
                            assertFalse(connection.permitsTransport(attempt()))
                            assertTrue(credentials.isEmpty())
                        }
                        assertEquals(1, proxy.destinations.size)
                    } finally { connection.instantShutdown() }
                }
            }
        }
    }

    @Test fun revocationBetweenConnectAndLoginSendsNoCredentials() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val auth = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)
        SocksFixture { socket, _ ->
            readThrough(socket, ">", "<stream:stream")
            send(socket, stream() + "<stream:features>" + mechanisms() + "</stream:features>")
            try {
                val next = element(socket)
                if (next.contains("<auth")) auth += next
            } finally { closed.countDown() }
        }.use { proxy ->
            val route = route(proxy)
            val connection = connection(route)
            try {
                connection.beginAttempt(attempt())
                connection.connect()
                assertTrue(connection.permitsTransport(attempt()))
                route.close()
                assertTrue(runCatching { connection.login("fixture", "fixture-only") }.isFailure)
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                assertTrue(auth.isEmpty())
                assertFalse(connection.isAuthenticated)
            } finally { route.close(); connection.instantShutdown() }
        }
    }

    @Test fun offeredTlsFailureCannotBeResurrectedByPlaintextFeatures() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val auth = CopyOnWriteArrayList<String>()
        SocksFixture { socket, _ ->
            readThrough(socket, ">", "<stream:stream")
            send(socket, stream() + "<stream:features><starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/></stream:features>")
            check(element(socket).contains("starttls"))
            send(socket, "<failure xmlns='urn:ietf:params:xml:ns:xmpp-tls'/><stream:features>" + mechanisms() + "</stream:features>")
            val next = element(socket)
            if (next.contains("<auth")) auth += next
        }.use { proxy ->
            route(proxy).use { route ->
                val connection = connection(route)
                val losses = CopyOnWriteArrayList<SessionFailureReason>()
                val session = SmackSessionConnection(connection, "fixture", "fixture@$VALID_ONION", { event ->
                    if (event is SessionEvent.ConnectionLost) losses += event.reason
                }, torSockets = route)
                try {
                    val failure = runCatching { session.connect("fixture-only".toCharArray(), attempt()) }.exceptionOrNull()
                    assertTrue(failure is SessionFailure)
                    assertEquals(SessionFailureReason.TLS_CERTIFICATE, (failure as SessionFailure).reason)
                    assertTrue(connection.hasIncompleteTlsNegotiation)
                    assertFalse("No retryable callback may race the terminal TLS result: $losses",
                        losses.contains(SessionFailureReason.NETWORK))
                    assertFalse(connection.permitsTransport(attempt()))
                    assertFalse(route.hasOnionProof(attempt()))
                    assertTrue(auth.isEmpty())
                    assertEquals(1, proxy.destinations.size)
                } finally { connection.instantShutdown() }
            }
        }
    }

    @Test fun proofRequiresCompleteHandshakeExactAttemptAndLiveSocket() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        SocksFixture(beforeGreeting = { entered.countDown(); release.await(3, TimeUnit.SECONDS) }) { _, _ -> }.use { proxy ->
            route(proxy).use { route ->
                route.beginAttempt(attempt())
                val socket = route.createSocket()
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val pending = executor.submit<Boolean> {
                        runCatching { socket.connect(InetSocketAddress(localAddress, 5222)); true }.getOrDefault(false)
                    }
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    assertFalse(route.hasOnionProof(attempt()))
                    route.beginAttempt(attempt(2))
                    release.countDown()
                    assertFalse(pending.get(2, TimeUnit.SECONDS))
                    assertFalse(route.hasOnionProof(attempt()))
                    assertFalse(route.hasOnionProof(attempt(2)))
                    assertTrue(runCatching { route.beginAttempt(attempt()) }.isFailure)
                    route.createSocket().use {
                        it.connect(InetSocketAddress(localAddress, 5222))
                        assertTrue(route.hasOnionProof(attempt(2)))
                        assertFalse(route.hasOnionProof(attempt(2).copy(accountId = AccountId.require("other"))))
                        assertFalse(route.hasOnionProof(attempt(2).copy(epoch = LifecycleEpoch.require(2))))
                        val output = it.outputStream
                        route.invalidateAttempt()
                        assertTrue(runCatching { output.write(1) }.isFailure)
                    }
                    assertFalse(route.hasOnionProof(attempt(2)))
                } finally { release.countDown(); executor.shutdownNow() }
            }
        }
    }

    @Test fun revocationWhileAuthenticationWaitsClosesRouteAndCannotPublishUsable() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val auth = CountDownLatch(1)
        val closed = CountDownLatch(1)
        SocksFixture { socket, _ ->
            readThrough(socket, ">", "<stream:stream")
            send(socket, stream() + "<stream:features>" + mechanisms() + "</stream:features>")
            check(element(socket).contains("<auth"))
            auth.countDown()
            try { while (socket.inputStream.read() != -1) {} } finally { closed.countDown() }
        }.use { proxy ->
            val route = route(proxy)
            val connection = connection(route)
            val session = SmackSessionConnection(connection, "fixture", "fixture@$VALID_ONION", {}, torSockets = route)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<Boolean> { runBlocking { runCatching {
                    session.connect("fixture-only".toCharArray(), attempt())
                }.isFailure } }
                assertTrue(auth.await(3, TimeUnit.SECONDS))
                session.revoke()
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                assertTrue(pending.get(5, TimeUnit.SECONDS))
                assertFalse(session.isUsable)
                assertFalse(route.hasOnionProof(attempt()))
            } finally { session.revoke(); session.disconnect(); executor.shutdownNow() }
        }
    }

    @Test fun optInActualTorHiddenServiceAuthenticatedMessageRoundtrip() = runBlocking {
        val domain = System.getenv("NEMA_ONION_PROOF_DOMAIN")
        org.junit.Assume.assumeTrue("No explicitly owned disposable hidden service", domain != null)
        require(canonicalOnionIdentity(requireNotNull(domain)) == domain)
        val localPort = requireNotNull(System.getenv("NEMA_ONION_PROOF_SERVER_PORT")).toInt()
        val proxyPort = requireNotNull(System.getenv("NEMA_ONION_PROOF_SOCKS_PORT")).toInt()
        val password = requireNotNull(System.getenv("NEMA_ONION_PROOF_PASSWORD"))
        require(localPort in 1024..65535 && proxyPort in 1024..65535 && password.length >= 24)
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val credentials = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()
        java.net.ServerSocket(localPort, 1, localAddress).use { server ->
            server.soTimeout = 90_000
            val executor = Executors.newSingleThreadExecutor()
            val served = executor.submit {
                repeat(2) {
                    server.accept().use { socket -> socket.soTimeout = 90_000; serveXmpp(socket, credentials, messages, domain, password) }
                }
            }
            val config = AccountConfiguration.create(fixtureAccount, "fixture@$domain", "fixture", null, domain, null)
            lateinit var route: TorSocketFactory
            lateinit var connection: OnionXmppConnection
            val echoed = java.util.concurrent.LinkedBlockingQueue<String>()
            val session = SmackSessionConnectionFactory { password.toCharArray() }.createSession(config, initialOwner, {}) { owner ->
                route = TorSocketFactory(NetworkEndpoint.create(domain, 5222), InetSocketAddress(localAddress, proxyPort),
                    90_000, owner, domain)
                connection = OnionXmppConnection(SmackSessionConnectionFactory.configurationFor(config, route), route).apply {
                    replyTimeout = 90_000; setUseStreamManagement(false); setUseStreamManagementResumption(false)
                }
                connection.addAsyncStanzaListener({ stanza -> if ((stanza as Message).body == "fixture-message") echoed.add("fixture-message") }, StanzaTypeFilter.MESSAGE)
                route to connection
            }
            try {
                session.connect(password.toCharArray(), attempt())
                assertTrue(session.isUsable && session.onionWithoutTls)
                session.send(OutgoingMessageEnvelope(fixtureAccount, attempt().generation, 1, "live-op", "live-origin",
                    "peer@$domain", "fixture-message", null)) {}
                assertEquals("fixture-message", echoed.poll(30, TimeUnit.SECONDS))
                assertEquals(1, credentials.size)
                assertEquals(1, messages.size)
                connection.disconnect()
                assertFalse(route.hasOnionProof(attempt()))
                session.updateAttempt(attempt(2))
                assertFalse(session.isUsable)
                session.reconnect(attempt(2))
                assertTrue(session.isUsable && session.onionWithoutTls)
                assertTrue(route.hasOnionProof(attempt(2)))
                assertFalse(route.hasOnionProof(attempt()))
                session.send(OutgoingMessageEnvelope(fixtureAccount, attempt(2).generation, 1, "live-op2", "live-origin2",
                    "peer@$domain", "fixture-message", null)) {}
                assertEquals("fixture-message", echoed.poll(30, TimeUnit.SECONDS))
                assertEquals(2, credentials.size)
                assertEquals(2, messages.size)
                session.revoke()
                assertFalse(route.hasOnionProof(attempt(2)))
                assertFalse(session.isUsable)
                println("LIVE_TOR_ONION_SMACK_AUTH_MESSAGE_RECONNECT_PASS")
            } finally {
                session.revoke(); session.disconnect(); server.close(); executor.shutdownNow()
                runCatching { served.get(3, TimeUnit.SECONDS) }
                check(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun malformedStalledAndAbsentProxyCannotMintProof() {
        for (reply in listOf(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0), byteArrayOf(4, 0), byteArrayOf(),
            byteArrayOf(5, 0, 0, 3, 0), byteArrayOf(5, 0, 0, 1, 127, 0, 0))) {
            SocksFixture(reply = reply) { _, _ -> }.use { proxy ->
                route(proxy, 150).use { route ->
                    route.beginAttempt(attempt())
                    assertTrue(runCatching { route.createSocket().connect(InetSocketAddress(localAddress, 5222)) }.isFailure)
                    assertFalse(route.hasOnionProof(attempt()))
                }
            }
        }
        val absent = java.net.ServerSocket(0, 1, localAddress).use { InetSocketAddress(localAddress, it.localPort) }
        TorSocketFactory(NetworkEndpoint.create(VALID_ONION, 5222), absent, 150, initialOwner, VALID_ONION).use { route ->
            route.beginAttempt(attempt())
            assertTrue(runCatching { route.createSocket().connect(InetSocketAddress(localAddress, 5222)) }.isFailure)
            assertFalse(route.hasOnionProof(attempt()))
        }
    }
}

private fun stream(domain: String = VALID_ONION) = "<stream:stream xmlns='jabber:client' xmlns:stream='http://etherx.jabber.org/streams' from='$domain' id='fixture' version='1.0'>"
private fun mechanisms() = "<mechanisms xmlns='urn:ietf:params:xml:ns:xmpp-sasl'><mechanism>PLAIN</mechanism></mechanisms>"
private fun send(socket: Socket, text: String) { socket.outputStream.write(text.toByteArray()); socket.outputStream.flush() }
private fun element(socket: Socket): String {
    val opening = readThrough(socket, ">")
    if (opening.isEmpty() || opening.endsWith("/>") || opening.startsWith("</")) return opening
    val name = opening.substringAfter('<').takeWhile { it != ' ' && it != '>' }
    return opening + readThrough(socket, "</$name>")
}
internal fun serveXmpp(socket: Socket, credentials: MutableList<String>, messages: MutableList<String>,
    domain: String = VALID_ONION, password: String = "fixture-only", onPing: () -> Boolean = { true }) {
    readThrough(socket, ">", "<stream:stream")
    send(socket, stream(domain) + "<stream:features>" + mechanisms() + "</stream:features>")
    val auth = element(socket)
    check(auth.contains("<auth"))
    val decoded = String(Base64.getDecoder().decode(auth.substringAfter('>').substringBefore('<')))
    check(decoded == "\u0000fixture\u0000$password") { "Fixture authentication rejected" }
    credentials += decoded
    send(socket, "<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>")
    readThrough(socket, ">", "<stream:stream")
    send(socket, stream(domain) + "<stream:features><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'/></stream:features>")
    while (true) {
        val stanza = element(socket)
        if (stanza.isEmpty() || stanza.startsWith("</stream")) return
        if (stanza.startsWith("<iq")) {
            if (stanza.contains("urn:xmpp:ping") && !onPing()) continue
            val id = Regex("\\bid=['\"]([^'\"]+)['\"]").find(stanza)!!.groupValues[1]
            val payload = when {
                stanza.contains("<bind") -> "<bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'><jid>fixture@$domain/resource</jid></bind>"
                stanza.contains("jabber:iq:roster") -> "<query xmlns='jabber:iq:roster'/>"
                stanza.contains("disco#items") -> "<query xmlns='http://jabber.org/protocol/disco#items'/>"
                stanza.contains("disco#info") -> "<query xmlns='http://jabber.org/protocol/disco#info'/>"
                else -> ""
            }
            send(socket, "<iq type='result' id='$id' from='$domain' to='fixture@$domain/resource'>$payload</iq>")
        } else if (stanza.startsWith("<message")) {
            messages += stanza
            send(socket, "<message from='peer@$domain/resource' to='fixture@$domain/resource' type='chat'><body>fixture-message</body></message>")
        }
    }
}
