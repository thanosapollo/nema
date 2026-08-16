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
