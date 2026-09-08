package org.thanosapollo.nema.xmpp.smack

import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import org.thanosapollo.nema.account.NetworkEndpoint
import org.thanosapollo.nema.account.orbotAddress

/** Smack 4.4.8 resolves before selecting ProxyInfo. Pair this with a literal hostAddress to
 * bypass that lookup; ignore the synthetic endpoint and retain only the captured destination.
 * Every overload fails closed. Session revocation closes even a pending SOCKS handshake.
 */
internal class TorSocketFactory(
    private val destination: NetworkEndpoint,
    private val proxy: InetSocketAddress = orbotAddress(),
    private val handshakeTimeoutMs: Int = 15_000,
) : SocketFactory(), AutoCloseable {
    private val sockets = mutableSetOf<Socket>()
    private var closed = false
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
        object : Socket(java.net.Proxy.NO_PROXY) {
            override fun connect(endpoint: SocketAddress) = connect(endpoint, handshakeTimeoutMs)

            override fun connect(endpoint: SocketAddress, timeout: Int) {
                try {
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
                    val output = getOutputStream()
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
        }.also { sockets.add(it) }
    }

    override fun close() {
        val owned = synchronized(sockets) {
            closed = true
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
