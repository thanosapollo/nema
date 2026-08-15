package org.thanosapollo.nema.xmpp.vcard

/** Pure XEP-0054 display identity extracted for UI/cache. */
data class ParsedVCardIdentity(
    val displayName: String?,
    val photoBytes: ByteArray?,
    val photoMime: String?,
    val photoSha1: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedVCardIdentity) return false
        return displayName == other.displayName &&
            photoMime == other.photoMime &&
            photoSha1 == other.photoSha1 &&
            photoBytes.contentEquals(other.photoBytes)
    }

    override fun hashCode(): Int {
        var result = displayName?.hashCode() ?: 0
        result = 31 * result + (photoMime?.hashCode() ?: 0)
        result = 31 * result + (photoSha1?.hashCode() ?: 0)
        result = 31 * result + (photoBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class RemoteVCardPayload(
    val formattedName: String?,
    val nickname: String?,
    val photoBytes: ByteArray?,
    val photoMime: String?,
    val photoSha1: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteVCardPayload) return false
        return formattedName == other.formattedName &&
            nickname == other.nickname &&
            photoMime == other.photoMime &&
            photoSha1 == other.photoSha1 &&
            photoBytes.contentEquals(other.photoBytes)
    }

    override fun hashCode(): Int {
        var result = formattedName?.hashCode() ?: 0
        result = 31 * result + (nickname?.hashCode() ?: 0)
        result = 31 * result + (photoMime?.hashCode() ?: 0)
        result = 31 * result + (photoSha1?.hashCode() ?: 0)
        result = 31 * result + (photoBytes?.contentHashCode() ?: 0)
        return result
    }
}

fun peerDisplayLabel(peerJid: String, displayName: String?, localNickname: String? = null): String {
    val local = localNickname?.trim().orEmpty()
    val remote = displayName?.trim().orEmpty()
    return local.ifEmpty { remote }.ifEmpty { peerJid }
}

fun parseVCardIdentity(
    payload: RemoteVCardPayload,
    maxPhotoBytes: Int = MAX_VCARD_PHOTO_BYTES,
): ParsedVCardIdentity {
    val displayName = firstNonBlank(payload.formattedName, payload.nickname)
    val photo = payload.photoBytes?.takeIf { it.isNotEmpty() && it.size <= maxPhotoBytes }
    return ParsedVCardIdentity(
        displayName = displayName,
        photoBytes = photo,
        photoMime = photo?.let { payload.photoMime?.trim()?.takeIf(String::isNotEmpty) },
        photoSha1 = photo?.let { payload.photoSha1?.trim()?.takeIf(String::isNotEmpty) },
    )
}

private fun firstNonBlank(vararg values: String?): String? {
    for (value in values) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isNotEmpty()) return trimmed
    }
    return null
}

const val MAX_VCARD_PHOTO_BYTES: Int = 256 * 1024
const val VCARD_SUCCESS_TTL_MS: Long = 24L * 60L * 60L * 1000L
const val VCARD_FAILURE_TTL_MS: Long = 60L * 60L * 1000L
