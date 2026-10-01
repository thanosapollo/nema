package org.thanosapollo.nema.xmpp.httpupload

data class UploadedFile(
    val url: String,
    val name: String,
    val mime: String?,
    val size: Long,
)

data class LocalUploadRequest(
    val name: String,
    val mime: String?,
    val bytes: ByteArray,
) {
    val size: Long get() = bytes.size.toLong()

    internal fun requireValidSize(): Unit = requireUploadSize(size)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocalUploadRequest) return false
        return name == other.name && mime == other.mime && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (mime?.hashCode() ?: 0)
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

fun attachmentPreview(name: String?, url: String?): String {
    val labeled = name?.trim().orEmpty()
    if (labeled.isNotEmpty()) return labeled
    return url?.substringAfterLast('/')?.substringBefore('?').orEmpty().ifEmpty { "File" }
}

fun attachmentCaption(name: String?, url: String?, size: Long?, mime: String? = null): String {
    val label = attachmentPreview(name, url)
    val type = resolvedAttachmentMime(mime, name, url)
    val withType = if (type.isNullOrEmpty()) label else "$label · $type"
    return if (size == null || size < 0) withType else "$withType · $size B"
}

fun isInlineImage(mime: String?, name: String?, url: String? = null): Boolean {
    val type = resolvedAttachmentMime(mime, name, url)?.substringBefore(';')?.trim()?.lowercase()
    if (type == "image/svg+xml") return false
    return type?.startsWith("image/") == true
}

fun resolvedAttachmentMime(mime: String?, name: String?, url: String?): String? {
    val declared = mime?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
    if (declared != null) return declared
    guessMimeFromExtension(extractRelevantExtension(name))?.let { return it }
    guessMimeFromExtension(extractRelevantExtension(url))?.let { return it }
    val stem = attachmentPreview(name, url)
    return if (IMAGE_STEM.matches(stem)) "image/jpeg" else null
}

fun slotFilename(name: String, mime: String?): String {
    val cleaned = name.trim().substringAfterLast('/').replace(':', '_').ifEmpty { "file" }
    if (guessMimeFromExtension(extractRelevantExtension(cleaned)) != null) return cleaned
    val ext = guessExtensionFromMime(mime) ?: return cleaned
    val base = cleaned.substringBeforeLast('.').ifEmpty { "file" }
    return "$base.$ext"
}

internal fun extractRelevantExtension(path: String?): String? {
    val filename = path?.substringAfterLast('/')?.substringBefore('#')?.substringBefore('?').orEmpty()
    val dot = filename.lastIndexOf('.')
    if (dot <= 0 || dot == filename.lastIndex) return null
    return filename.substring(dot + 1).lowercase().takeIf(String::isNotEmpty)
}

private fun guessMimeFromExtension(extension: String?): String? = when (extension) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "txt" -> "text/plain"
    "pdf" -> "application/pdf"
    else -> null
}

private fun guessExtensionFromMime(mime: String?): String? = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/bmp" -> "bmp"
    "text/plain" -> "txt"
    "application/pdf" -> "pdf"
    else -> null
}

fun attachmentActionLabel(image: Boolean, downloaded: Boolean, name: String?): String {
    val labeled = name?.trim().orEmpty()
    val verb = if (downloaded) "Open" else "Download"
    if (labeled.isNotEmpty()) return "$verb $labeled"
    return if (image) "$verb image" else "$verb file"
}

fun attachmentBodyCaption(body: String, attachmentUrl: String?): String? {
    val caption = body.trim()
    if (caption.isEmpty()) return null
    if (attachmentUrl != null && caption == attachmentUrl.trim()) return null
    return caption
}

private val IMAGE_STEM = Regex("image[_-]?\\d+", RegexOption.IGNORE_CASE)
