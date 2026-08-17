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

fun attachmentCaption(name: String?, url: String?, size: Long?): String {
    val label = attachmentPreview(name, url)
    return if (size == null || size < 0) label else "$label · $size B"
}

fun isInlineImage(mime: String?, name: String?): Boolean {
    val type = mime?.substringBefore(';')?.trim()?.lowercase()
    if (type == "image/svg+xml") return false
    if (type?.startsWith("image/") == true) return true
    val ext = name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
    return ext in IMAGE_EXTENSIONS
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

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
