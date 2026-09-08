package org.thanosapollo.nema.xmpp.smack

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.*
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.xmpp.httpupload.AccountHttpTransfer
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration

private const val ONION = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion"
private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/** Real loopback sockets and actual Smack/OkHttp, not a mocked fetch/socket API. No Internet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TorNetworkBoundaryTest {
    private fun account(domain: String = ONION, host: String? = null, port: Int = 5222) =
        AccountConfiguration.create(AccountId.require("tor-fixture"), "fixture@$domain", "fixture", null,
            domain, host?.let { NetworkEndpoint.create(it, port) })

    @Test fun accountAndEndpointPolicyIsImmutableAndTlsAlwaysRequired() {
        for (config in listOf(account(), account(host = "clearnet.invalid"), account("example.invalid", ONION))) {
            assertEquals(AccountTransportPolicy.TOR, AccountTransportPolicy.forAccount(config))
            val smack = SmackSessionConnectionFactory.configurationFor(config)
            assertEquals(ConnectionConfiguration.SecurityMode.required, smack.securityMode)
            assertEquals(LOOPBACK, smack.hostAddress)
            assertTrue(smack.socketFactory is TorSocketFactory)
        }
        assertEquals(AccountTransportPolicy.DIRECT, AccountTransportPolicy.forAccount(account("example.invalid")))
        assertEquals(AccountTransportPolicy.TOR, AccountTransportPolicy.forAccount(account("EXAMPLE.ONION.")))
    }

    @Test fun actualSmackPassesOnionHostnameToSocksAndRejectsAbsentTls() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        SocksFixture { socket, _ ->
            readThrough(socket, ">", "<stream:stream")
            socket.getOutputStream().write((streamOpen() + "<stream:features><mechanisms xmlns='urn:ietf:params:xml:ns:xmpp-sasl'><mechanism>PLAIN</mechanism></mechanisms></stream:features>").toByteArray())
            socket.getOutputStream().flush()
            readThrough(socket, ">")
        }.use { proxy ->
            val sockets = TorSocketFactory(NetworkEndpoint.create(ONION, 5222), proxy.address)
            val connection = XMPPTCPConnection(SmackSessionConnectionFactory.configurationFor(account(), sockets))
            connection.replyTimeout = 2_000
            try {
                val failure = runCatching { connection.connect() }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(SessionFailureReason.TLS_CERTIFICATE, classifySmackFailure(failure as Exception))
                assertEquals(listOf(ONION to 5222), proxy.destinations.toList())
                assertFalse(connection.isAuthenticated)
            } finally { sockets.close(); runCatching { connection.disconnect() } }
        }
    }

    @Test fun actualSmackRequestsOfferedStartTlsAndDoesNotDowngradeBadCertificate() {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val startTls = CountDownLatch(1)
        SocksFixture { socket, _ ->
            readThrough(socket, ">", "<stream:stream")
            socket.getOutputStream().write((streamOpen() + "<stream:features><starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/></stream:features>").toByteArray())
            socket.getOutputStream().flush()
            assertTrue(readThrough(socket, ">", "starttls").contains("starttls"))
            startTls.countDown()
            socket.getOutputStream().write("<proceed xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>".toByteArray())
            socket.getOutputStream().flush()
            tls(socket).use { it.startHandshake() }
        }.use { proxy ->
            val sockets = TorSocketFactory(NetworkEndpoint.create(ONION, 5222), proxy.address)
            val connection = XMPPTCPConnection(SmackSessionConnectionFactory.configurationFor(account(), sockets))
            connection.replyTimeout = 3_000
            try {
                val failure = runCatching { connection.connect() }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(startTls.await(1, TimeUnit.SECONDS))
                assertEquals(SessionFailureReason.TLS_CERTIFICATE, classifySmackFailure(failure as Exception))
                assertEquals(1, proxy.destinations.size)
                assertFalse(connection.isAuthenticated)
            } finally { sockets.close(); runCatching { connection.disconnect() } }
        }
    }

    @Test fun absentProxyReportsTorFailureAndNeverTouchesDirectEndpoint() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        ServerSocket(0, 1, LOOPBACK).use { direct ->
            val absent = ServerSocket(0, 1, LOOPBACK).use { InetSocketAddress(LOOPBACK, it.localPort) }
            val config = account(host = "127.0.0.1", port = direct.localPort)
            val sockets = TorSocketFactory(config.networkEndpoint!!, absent)
            val session = SmackSessionConnection(XMPPTCPConnection(SmackSessionConnectionFactory.configurationFor(config, sockets)),
                "fixture", "fixture@$ONION", {}, torSockets = sockets)
            try {
                val failure = runCatching { session.connect(charArrayOf('x'), SessionAttemptIdentity(AccountId.require("tor-fixture"), ConnectionGeneration.require(1), ConnectionAttempt.require(1), LifecycleEpoch.require(1))) }.exceptionOrNull()
                assertTrue(failure is SessionFailure)
                assertEquals(SessionFailureReason.TOR_UNAVAILABLE, (failure as SessionFailure).reason)
                direct.soTimeout = 150
                assertTrue(runCatching { direct.accept().close() }.isFailure)
            } finally { session.revoke(); session.disconnect() }
        }
    }

    @Test fun failedSocksReplyAndHandshakeTimeoutCloseWithoutFallback() {
        for (reply in listOf(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0), byteArrayOf(4, 0), byteArrayOf())) {
            SocksFixture(reply = reply) { _, _ -> }.use { proxy ->
                TorSocketFactory(NetworkEndpoint.create(ONION, 5222), proxy.address, 150).use { factory ->
                    val socket = factory.createSocket()
                    assertTrue(runCatching { socket.connect(InetSocketAddress(LOOPBACK, 9), 500) }.isFailure)
                    assertTrue(socket.isClosed)
                    assertTrue(factory.routeFailed)
                }
            }
        }
    }

    @Test fun retiringOwnerClosesBlockedHandshakeAndNewOwnerCanRetry() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        SocksFixture(beforeGreeting = { entered.countDown(); release.await(3, TimeUnit.SECONDS) }) { _, _ -> }.use { proxy ->
            val factory = TorSocketFactory(NetworkEndpoint.create(ONION, 5222), proxy.address)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<Boolean> {
                    runCatching { factory.createSocket().connect(InetSocketAddress(LOOPBACK, 9)) }.isFailure
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                factory.close()
                assertTrue(result.get(1, TimeUnit.SECONDS))
                assertTrue(runCatching { factory.createSocket() }.isFailure)
            } finally { release.countDown(); factory.close(); executor.shutdownNow() }
        }
        SocksFixture { _, _ -> }.use { proxy ->
            TorSocketFactory(NetworkEndpoint.create(ONION, 5222), proxy.address).use { replacement ->
                replacement.createSocket().use { it.connect(InetSocketAddress(LOOPBACK, 9)); assertTrue(it.isConnected) }
                assertEquals(listOf(ONION to 5222), proxy.destinations.toList())
            }
        }
    }

    @Test fun accountHttpUsesRemoteDnsForClearnetAndRedirectAndUpload() = runBlocking {
        val methods = CopyOnWriteArrayList<String>()
        val uploads = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        SocksFixture { socket, target ->
            tls(socket).use { secure ->
                val request = readThrough(secure, "\r\n\r\n")
                methods += request.substringBefore("\r\n")
                if (request.startsWith("PUT")) {
                    val length = Regex("(?i)content-length: (\\d+)").find(request)!!.groupValues[1].toInt()
                    val body = ByteArray(length)
                    DataInputStream(secure.inputStream).readFully(body)
                    uploads += request to body
                }
                val reply = if (target.first == "first.invalid") "HTTP/1.1 302 Found\r\nLocation: https://second.invalid/file\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    else "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                secure.outputStream.write(reply.toByteArray()); secure.outputStream.flush()
            }
        }.use { proxy ->
            transfer(proxy).use { http ->
                assertArrayEquals("ok".toByteArray(), http.fetch("https://first.invalid/file"))
                assertTrue(http.put("https://second.invalid/upload", mapOf("Authorization" to "fixture-only",
                    "Cookie" to "upload=fixture", "Expires" to "Wed, 09 Sep 2026 00:00:00 GMT"), "bytes".toByteArray(), "application/octet-stream"))
                for (header in listOf("Host", "Proxy-Authorization")) {
                    assertTrue(runCatching { http.put("https://second.invalid/upload", mapOf(header to "forbidden"), byteArrayOf(1)) }.isFailure)
                }
            }
            assertEquals(listOf("first.invalid", "second.invalid", "second.invalid"), proxy.destinations.map { it.first })
            assertEquals(listOf("GET /file HTTP/1.1", "GET /file HTTP/1.1", "PUT /upload HTTP/1.1"), methods.toList())
            assertEquals(1, uploads.size)
            val (request, body) = uploads.single()
            assertArrayEquals("bytes".toByteArray(), body)
            val headers = request.split("\r\n").drop(1).filter { it.contains(':') }
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(": ") }
            assertEquals("fixture-only", headers["authorization"])
            assertEquals("upload=fixture", headers["cookie"])
            assertEquals("Wed, 09 Sep 2026 00:00:00 GMT", headers["expires"])
            assertEquals("application/octet-stream", headers["content-type"])
            assertNull(headers["proxy-authorization"])
        }
    }

    @Test fun httpRejectsTlsFailureDowngradeAndPutRedirect() = runBlocking {
        SocksFixture { socket, _ ->
            tls(socket).use { secure ->
                readThrough(secure, "\r\n\r\n")
                secure.outputStream.write("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1/leak\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                secure.outputStream.flush()
            }
        }.use { proxy ->
            transfer(proxy).use { http ->
                assertNull(http.fetch("https://second.invalid/file"))
                assertFalse(http.put("https://second.invalid/file", emptyMap(), byteArrayOf()))
            }
            AccountHttpTransfer(AccountTransportPolicy.TOR, proxy.address).use { untrusted ->
                assertTrue(runCatching { untrusted.fetch("https://second.invalid/file") }.isFailure)
            }
            assertEquals(3, proxy.destinations.size)
        }
    }

    @Test fun httpAbsentProxyAndRetiredAccountCannotUseDirectNetwork() = runBlocking {
        ServerSocket(0, 1, LOOPBACK).use { direct ->
            val absent = ServerSocket(0, 1, LOOPBACK).use { InetSocketAddress(LOOPBACK, it.localPort) }
            AccountHttpTransfer(AccountTransportPolicy.TOR, absent).use { http ->
                assertTrue(runCatching { http.fetch("https://127.0.0.1:${direct.localPort}/") }.isFailure)
                assertTrue(runCatching { http.put("https://127.0.0.1:${direct.localPort}/", emptyMap(), byteArrayOf()) }.isFailure)
                http.close()
                assertTrue(runCatching { http.fetch("https://127.0.0.1:${direct.localPort}/") }.isFailure)
                direct.soTimeout = 150
                assertTrue(runCatching { direct.accept().close() }.isFailure)
            }
        }
    }

    @Test fun cancellingHttpBodyReadClosesSocketAndRetiredUploadOwnerCannotPublish() = runBlocking {
        val reading = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        SocksFixture { socket, _ ->
            tls(socket).use { secure ->
                readThrough(secure, "\r\n\r\n")
                secure.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nx".toByteArray())
                secure.outputStream.flush()
                reading.countDown()
                try { secure.inputStream.read() } finally { peerClosed.countDown() }
            }
        }.use { proxy ->
            transfer(proxy).use { http ->
                val fetch = launch(Dispatchers.IO) { http.fetch("https://second.invalid/file") }
                assertTrue(reading.await(2, TimeUnit.SECONDS))
                withTimeout(2_000) { fetch.cancelAndJoin() }
                assertTrue(peerClosed.await(2, TimeUnit.SECONDS))
            }
        }
        val uploading = CountDownLatch(1)
        SocksFixture { socket, _ ->
            tls(socket).use { secure ->
                readThrough(secure, "\r\n\r\n")
                uploading.countDown()
                while (secure.inputStream.read() != -1) { /* Retain response until client revokes. */ }
            }
        }.use { proxy ->
            val http = transfer(proxy)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val upload = executor.submit<Boolean> {
                    runCatching { http.put("https://second.invalid/file", emptyMap(), byteArrayOf(1)) }.isFailure
                }
                assertTrue(uploading.await(2, TimeUnit.SECONDS))
                http.close()
                assertTrue(upload.get(2, TimeUnit.SECONDS))
                assertTrue(runCatching { http.put("https://second.invalid/file", emptyMap(), byteArrayOf()) }.isFailure)
            } finally { http.close(); executor.shutdownNow() }
        }
    }

    @Test fun sessionRevocationClosesOwnedHttpDownload() = runBlocking {
        SmackAndroid.initialize(ApplicationProvider.getApplicationContext())
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        SocksFixture { socket, _ ->
            tls(socket).use { secure ->
                readThrough(secure, "\r\n\r\n")
                secure.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nx".toByteArray())
                secure.outputStream.flush()
                reading.countDown()
                try { secure.inputStream.read() } finally { closed.countDown() }
            }
        }.use { proxy ->
            val http = transfer(proxy)
            val session = SmackSessionConnection(
                XMPPTCPConnection(SmackSessionConnectionFactory.configurationFor(account())),
                "fixture", "fixture@$ONION", {}, httpTransfer = http,
            )
            try {
                val result = async(Dispatchers.IO) { runCatching { http.fetch("https://second.invalid/file") } }
                assertTrue(reading.await(3, TimeUnit.SECONDS))
                session.revoke()
                assertTrue(withTimeout(2_000) { result.await() }.isFailure)
                assertTrue(closed.await(2, TimeUnit.SECONDS))
                assertTrue(runCatching { http.fetch("https://second.invalid/file") }.isFailure)
            } finally { session.revoke(); session.disconnect() }
        }
    }

    @Test fun optInActualOrbotAndPublicOnionHttps() = runBlocking {
        val port = System.getenv("NEMA_ORBOT_PROOF_PORT")?.toIntOrNull()
        org.junit.Assume.assumeTrue("No explicitly owned device SOCKS forward supplied", port != null)
        val ownedPort = requireNotNull(port)
        require(ownedPort in 1024..65535)
        // Fixed public endpoints only; no account credentials, exit IPs, or response bodies logged.
        AccountHttpTransfer(AccountTransportPolicy.forAccount(account()), InetSocketAddress(LOOPBACK, ownedPort)).use { http ->
            val check = http.fetch("https://check.torproject.org/api/ip", 4_096)!!.decodeToString()
            assertTrue(org.json.JSONObject(check).getBoolean("IsTor"))
            println("LIVE_ORBOT_TOR_CHECK_PASS")
            val onion = http.fetch("https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/", 512 * 1024)
            assertNotNull(onion)
            assertTrue(onion!!.decodeToString().contains("DuckDuckGo"))
            println("LIVE_ORBOT_ONION_HTTPS_PASS")
        }
    }

    private fun transfer(proxy: SocksFixture) = AccountHttpTransfer(AccountTransportPolicy.TOR, proxy.address) {
        sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager)
    }

    private fun tls(socket: Socket): SSLSocket = (certificates.sslSocketFactory()
        .createSocket(socket, "second.invalid", socket.port, true) as SSLSocket).apply { useClientMode = false; soTimeout = 3_000 }

    companion object {
        private val certificate = HeldCertificate.Builder().commonName("fixture")
            .addSubjectAlternativeName("first.invalid").addSubjectAlternativeName("second.invalid")
            .addSubjectAlternativeName(ONION).build()
        private val certificates = HandshakeCertificates.Builder().heldCertificate(certificate)
            .addTrustedCertificate(certificate.certificate).build()
    }
}

private fun streamOpen() = "<stream:stream xmlns='jabber:client' xmlns:stream='http://etherx.jabber.org/streams' from='$ONION' id='fixture' version='1.0'>"

internal fun readThrough(socket: Socket, ending: String, contains: String = ""): String {
    val result = StringBuilder()
    while (result.length < 16_384) {
        val value = socket.inputStream.read()
        if (value < 0) break
        result.append(value.toChar())
        if (result.endsWith(ending) && result.contains(contains)) break
    }
    return result.toString()
}

internal class SocksFixture(
    private val reply: ByteArray = byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0),
    private val beforeGreeting: () -> Unit = {},
    private val serve: (Socket, Pair<String, Int>) -> Unit,
) : AutoCloseable {
    private val server = ServerSocket(0, 16, LOOPBACK)
    val address = InetSocketAddress(LOOPBACK, server.localPort)
    val destinations = CopyOnWriteArrayList<Pair<String, Int>>()
    private val clients = CopyOnWriteArrayList<Socket>()
    private val executor = Executors.newCachedThreadPool()
    init {
        executor.submit {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                clients += client
                executor.submit {
                    client.use { socket ->
                        runCatching {
                            socket.soTimeout = 3_000
                            beforeGreeting()
                            val input = DataInputStream(socket.inputStream)
                            check(input.readUnsignedByte() == 5)
                            val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                            socket.outputStream.write(byteArrayOf(5, 0)); socket.outputStream.flush()
                            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
                            check(input.readUnsignedByte() == 3) { "Destination must be unresolved DOMAIN, never an IP address" }
                            val host = ByteArray(input.readUnsignedByte()); input.readFully(host)
                            val target = host.toString(Charsets.US_ASCII) to input.readUnsignedShort()
                            destinations += target
                            socket.outputStream.write(reply); socket.outputStream.flush()
                            if (reply.isEmpty()) input.read() else serve(socket, target)
                        }
                    }
                }
            }
        }
    }
    override fun close() {
        server.close(); clients.forEach { runCatching { it.close() } }; executor.shutdownNow()
        check(executor.awaitTermination(4, TimeUnit.SECONDS))
    }
}
