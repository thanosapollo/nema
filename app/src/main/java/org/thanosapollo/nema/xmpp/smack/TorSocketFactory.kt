package org.thanosapollo.nema.xmpp.smack

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import org.thanosapollo.nema.account.NetworkEndpoint
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.account.canonicalOnionIdentity
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.account.orbotAddress

/** Smack 4.4.8 resolves before selecting ProxyInfo. Pair this with a literal hostAddress to
 * bypass that lookup; ignore the synthetic endpoint and retain only the captured destination.
 * Every overload fails closed. Session revocation closes even a pending SOCKS handshake.
 */
internal class TorSocketFactory(
    private val destination: NetworkEndpoint,
    private val proxy: InetSocketAddress = orbotAddress(),
    private val handshakeTimeoutMs: Int = 15_000,
    private val owner: SessionIdentity? = null,
    private val serviceDomain: String? = null,
) : SocketFactory(), AutoCloseable {
    private val sockets = mutableSetOf<Socket>()
    private var closed = false
    private class Attempt(val identity: SessionAttemptIdentity) {
        var socket: Socket? = null
        var established = false
    }
    private var attempt: Attempt? = null
    val onionEligible: Boolean = owner != null && serviceDomain?.let(::canonicalOnionIdentity)?.let {
        it == canonicalOnionIdentity(destination.host)
    } == true

    fun matches(configuration: AccountConfiguration): Boolean =
        destination == (configuration.networkEndpoint ?: NetworkEndpoint.create(configuration.serviceDomain.value, 5222)) &&
            (!onionEligible || (owner?.accountId == configuration.id && serviceDomain == configuration.serviceDomain.value))

    /** Each physical connect consumes one attempt; an older async handshake cannot mint new proof. */
    fun beginAttempt(identity: SessionAttemptIdentity) {
        val old = synchronized(sockets) {
            if (closed) throw IOException("Tor session retired")
            owner?.let { require(identity.accountId == it.accountId && identity.generation.value >= it.generation.value) }
            attempt?.let {
                require(identity.epoch == it.identity.epoch && identity.attempt.value > it.identity.attempt.value &&
                    identity.generation.value >= it.identity.generation.value)
            }
            sockets.toList().also {
                attempt = Attempt(identity)
                sockets.clear()
            }
        }
        old.forEach { runCatching { it.close() } }
    }

    fun hasOnionProof(identity: SessionAttemptIdentity): Boolean = synchronized(sockets) {
        val current = attempt
        onionEligible && !closed && current?.identity == identity && current.established &&
            current.socket?.let { it in sockets && it.isConnected && !it.isClosed } == true
    }

    fun invalidateAttempt() {
        val old = synchronized(sockets) {
            attempt?.established = false
            sockets.toList().also { sockets.clear() }
        }
        old.forEach { runCatching { it.close() } }
    }
    @Volatile var routeFailed: Boolean = false
        private set

    init {
        require(proxy.address?.isLoopbackAddress == true)
        require(handshakeTimeoutMs > 0)
        require(destination.host.all { it.code in 33..126 } && destination.host.length <= 255)
    }

    override fun createSocket(): Socket = synchronized(sockets) {
        if (closed) throw IOException("Tor session retired")
        routeFailed = false
        val captured = attempt
        if (onionEligible && (captured == null || captured.socket != null)) throw IOException("Tor attempt unavailable")
        object : Socket(java.net.Proxy.NO_PROXY) {
            private fun requireCurrent() = synchronized(sockets) {
                if (closed || this !in sockets || (captured != null && attempt !== captured)) {
                    throw IOException("Tor attempt retired")
                }
            }

            // Check physical writes too: Smack can have SASL/stanzas already queued at revocation.
            override fun getOutputStream(): OutputStream {
                val raw = super.getOutputStream()
                return object : OutputStream() {
                    override fun write(value: Int) { requireCurrent(); raw.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        requireCurrent(); raw.write(bytes, offset, length)
                    }
                    override fun flush() { requireCurrent(); raw.flush() }
                }
            }
            override fun connect(endpoint: SocketAddress) = connect(endpoint, handshakeTimeoutMs)

            override fun connect(endpoint: SocketAddress, timeout: Int) {
                try {
                    requireCurrent()
                    // No local lookup of destination, no endpoint/ProxySelector fallback.
                    super.connect(proxy, minOf(timeout.takeIf { it > 0 } ?: handshakeTimeoutMs, handshakeTimeoutMs))
                    soTimeout = handshakeTimeoutMs
                    val input = DataInputStream(getInputStream())
                    val deadline = System.nanoTime() + handshakeTimeoutMs * 1_000_000L
                    fun readByte(): Int {
                        val remaining = (deadline - System.nanoTime()) / 1_000_000L
                        if (remaining <= 0) throw java.net.SocketTimeoutException("Tor handshake timed out")
                        soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        return input.readUnsignedByte()
                    }
                    val output = super.getOutputStream()
                    output.write(byteArrayOf(5, 1, 0))
                    output.flush()
                    if (readByte() != 5 || readByte() != 0) {
                        throw IOException("Tor SOCKS authentication unavailable")
                    }
                    val host = destination.host.toByteArray(Charsets.US_ASCII)
                    output.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()))
                    output.write(host)
                    output.write(byteArrayOf((destination.port ushr 8).toByte(), destination.port.toByte()))
                    output.flush()
                    if (readByte() != 5 || readByte() != 0 || readByte() != 0) {
                        throw IOException("Tor SOCKS route unavailable")
                    }
                    val addressLength = when (readByte()) {
                        1 -> 4
                        4 -> 16
                        3 -> readByte().also { if (it == 0) throw IOException("Invalid SOCKS address") }
                        else -> throw IOException("Invalid SOCKS address")
                    }
                    repeat(addressLength + 2) { readByte() }
                    soTimeout = 0
                    synchronized(sockets) {
                        requireCurrent()
                        if (captured != null) captured.established = true
                    }
                } catch (failure: IOException) {
                    routeFailed = true
                    close()
                    // Never retain endpoint/credential-bearing exception text in UI or diagnostics.
                    throw IOException("Tor route unavailable", failure)
                }
            }

            override fun close() {
                try { super.close() } finally { synchronized(sockets) { sockets.remove(this) } }
            }
        }.also { sockets.add(it); if (captured != null) captured.socket = it }
    }

    override fun close() {
        val owned = synchronized(sockets) {
            closed = true
            attempt?.established = false
            sockets.toList().also { sockets.clear() }
        }
        owned.forEach { runCatching { it.close() } }
    }

    // Only Smack's unconnected socket API is supported; never resolve names in overloads.
    override fun createSocket(host: String, port: Int): Socket = throw IOException("Unsupported Tor socket call")
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = throw IOException("Unsupported Tor socket call")
    override fun createSocket(host: InetAddress, port: Int): Socket = throw IOException("Unsupported Tor socket call")
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = throw IOException("Unsupported Tor socket call")
}
