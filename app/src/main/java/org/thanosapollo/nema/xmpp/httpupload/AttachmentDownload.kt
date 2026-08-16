package org.thanosapollo.nema.xmpp.httpupload

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

fun httpsAttachmentUrl(url: String): String? {
    val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (!parsed.scheme.equals("https", ignoreCase = true)) return null
    if (!parsed.rawUserInfo.isNullOrEmpty()) return null
    if (parsed.host.isNullOrBlank()) return null
    return parsed.toString()
}

fun attachmentCacheKey(url: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(url.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

fun attachmentCacheFile(dir: File, url: String): File = File(dir, attachmentCacheKey(url))

fun cachedAttachment(dir: File, url: String): File? =
    attachmentCacheFile(dir, url).takeIf { it.isFile && it.length() > 0L }

fun storeAttachment(dir: File, url: String, bytes: ByteArray): File? {
    dir.mkdirs()
    val target = attachmentCacheFile(dir, url)
    val tmp = File(dir, "${target.name}.part")
    return runCatching {
        tmp.writeBytes(bytes)
        if (tmp.renameTo(target)) return@runCatching target
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) return@runCatching null
        target
    }.getOrNull().also { if (it == null) tmp.delete() }
}

fun persistFetchedAttachment(
    dir: File,
    url: String,
    maxBytes: Long = MAX_ATTACHMENT_BYTES,
    fetch: (String) -> ByteArray?,
): File? = runCatching {
    val safe = httpsAttachmentUrl(url) ?: return@runCatching null
    cachedAttachment(dir, safe)?.let { return@runCatching it }
    val bytes = fetch(safe) ?: return@runCatching null
    if (bytes.isEmpty() || bytes.size.toLong() > maxBytes) return@runCatching null
    storeAttachment(dir, safe, bytes)
}.getOrNull()

internal sealed class HttpsFetchStep {
    data class Follow(val url: String) : HttpsFetchStep()
    data object ReadBody : HttpsFetchStep()
    data object Reject : HttpsFetchStep()
}

internal fun httpsFetchStep(
    current: String,
    status: Int,
    location: String?,
    contentLength: Long,
    maxBytes: Long,
): HttpsFetchStep {
    if (status in 300..399) {
        val next = location?.let { httpsAttachmentUrl(URI(current).resolve(it).toString()) }
        return if (next != null) HttpsFetchStep.Follow(next) else HttpsFetchStep.Reject
    }
    if (status !in 200..299) return HttpsFetchStep.Reject
    if (contentLength > maxBytes) return HttpsFetchStep.Reject
    return HttpsFetchStep.ReadBody
}

fun fetchHttpsBytes(url: String, maxBytes: Long = MAX_ATTACHMENT_BYTES): ByteArray? = runCatching {
    var current = httpsAttachmentUrl(url) ?: return@runCatching null
    repeat(5) {
        val connection = (URI(current).toURL().openConnection() as? HttpsURLConnection)
            ?: return@runCatching null
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        try {
            when (val step = httpsFetchStep(current, connection.responseCode, connection.getHeaderField("Location"), connection.contentLengthLong, maxBytes)) {
                is HttpsFetchStep.Follow -> current = step.url
                HttpsFetchStep.Reject -> return@runCatching null
                HttpsFetchStep.ReadBody -> return@runCatching connection.inputStream.use { it.readAtMost(maxBytes) }
            }
        } finally {
            connection.disconnect()
        }
    }
    null
}.getOrNull()

private fun InputStream.readAtMost(maxBytes: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

fun openCachedAttachment(context: Context, file: File, mime: String?): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime?.trim()?.takeIf(String::isNotEmpty) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching { context.startActivity(intent) }.isSuccess
}
