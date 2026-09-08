package org.thanosapollo.nema.xmpp.httpupload

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.thanosapollo.nema.account.AccountTransportPolicy
import org.thanosapollo.nema.account.isOnionHost
import org.thanosapollo.nema.account.orbotAddress
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Account/session owned HTTP boundary; redirects never reconsider the owning account's policy. */
internal class AccountHttpTransfer internal constructor(
    private val policy: AccountTransportPolicy,
    proxyAddress: InetSocketAddress = orbotAddress(),
    private val configureTls: (OkHttpClient.Builder.() -> Unit)? = null,
) : AutoCloseable {
    private val calls = mutableSetOf<Call>()
    private var closed = false
    private fun builder() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .apply { configureTls?.invoke(this) }
    private val direct = builder().proxy(Proxy.NO_PROXY).build()
    // OkHttp's SOCKS RouteSelector uses createUnresolved; fail if a library change invokes DNS.
    private val tor = builder().proxy(Proxy(Proxy.Type.SOCKS, proxyAddress))
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                throw UnknownHostException("Local DNS forbidden for Tor")
        }).build()

    init { require(proxyAddress.address?.isLoopbackAddress == true) }

    suspend fun fetch(url: String, maxBytes: Long = MAX_ATTACHMENT_BYTES): ByteArray? {
        var current = httpsAttachmentUrl(url) ?: return null
        var viaTor = policy == AccountTransportPolicy.TOR
        repeat(5) {
            val parsed = current.toHttpUrl()
            viaTor = viaTor || isOnionHost(parsed.host)
            val (step, bytes) = fetchStep(Request.Builder().url(parsed).get().build(), viaTor, current, maxBytes)
            when (step) {
                is HttpsFetchStep.Follow -> current = step.url
                HttpsFetchStep.Reject -> return null
                HttpsFetchStep.ReadBody -> return bytes
            }
        }
        return null
    }

    private suspend fun fetchStep(request: Request, viaTor: Boolean, current: String, maxBytes: Long): Pair<HttpsFetchStep, ByteArray?> =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val call = newCall(request, viaTor)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    synchronized(calls) { calls.remove(call) }
                    continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    val result = runCatching {
                        response.use {
                            val body = response.body
                            val step = httpsFetchStep(current, response.code, response.header("Location"), body?.contentLength() ?: -1, maxBytes)
                            step to if (step == HttpsFetchStep.ReadBody) body?.byteStream()?.use { it.readAtMost(maxBytes) } else null
                        }
                    }
                    synchronized(calls) { calls.remove(call) }
                    continuation.resumeWith(result)
                }
            })
        }

    fun put(url: String, headers: Map<String, String>, bytes: ByteArray, mime: String? = null): Boolean {
        val safe = httpsAttachmentUrl(url) ?: return false
        val parsed = safe.toHttpUrl()
        val request = Request.Builder().url(parsed).put(bytes.toRequestBody(mime?.toMediaTypeOrNull()))
        // XEP-0363 upload slot header allowlist. Never forward arbitrary Host/Proxy-Authorization.
        headers.forEach { (name, value) ->
            require(name.lowercase() in setOf("authorization", "cookie", "expires"))
            request.header(name, value)
        }
        val call = newCall(request.build(), policy == AccountTransportPolicy.TOR || isOnionHost(parsed.host))
        return try {
            // Do not replay signed PUT headers/body to redirects (including cross-origin redirects).
            call.execute().use { it.code in 200..299 }
        } finally { synchronized(calls) { calls.remove(call) } }
    }

    private fun newCall(request: Request, viaTor: Boolean): Call = synchronized(calls) {
        if (closed) throw IOException("Account transfer retired")
        (if (viaTor) tor else direct).newCall(request).also { calls.add(it) }
    }

    override fun close() {
        val owned = synchronized(calls) { closed = true; calls.toList().also { calls.clear() } }
        owned.forEach(Call::cancel)
        direct.connectionPool.evictAll()
        tor.connectionPool.evictAll()
    }
}
