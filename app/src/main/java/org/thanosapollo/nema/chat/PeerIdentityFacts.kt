package org.thanosapollo.nema.chat

import org.thanosapollo.nema.storage.PeerEntity

data class PeerIdentityFacts(
    val localNickname: String? = null,
    val remoteProfileName: String? = null,
    val photoBytes: ByteArray? = null,
    val photoMime: String? = null,
    val room: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerIdentityFacts) return false
        return localNickname == other.localNickname &&
            remoteProfileName == other.remoteProfileName &&
            photoMime == other.photoMime &&
            room == other.room &&
            photoBytes.contentEquals(other.photoBytes)
    }

    override fun hashCode(): Int {
        var result = localNickname?.hashCode() ?: 0
        result = 31 * result + (remoteProfileName?.hashCode() ?: 0)
        result = 31 * result + (photoMime?.hashCode() ?: 0)
        result = 31 * result + room.hashCode()
        result = 31 * result + (photoBytes?.contentHashCode() ?: 0)
        return result
    }
}

internal fun PeerEntity.toIdentityFacts() = PeerIdentityFacts(
    localNickname = localNickname,
    remoteProfileName = displayName,
    photoBytes = photoBytes,
    photoMime = photoMime,
    room = room,
)
