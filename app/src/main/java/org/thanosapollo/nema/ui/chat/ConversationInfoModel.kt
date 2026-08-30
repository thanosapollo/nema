package org.thanosapollo.nema.ui.chat

internal sealed interface ConversationInfo {
    val address: String

    data class DirectContact(
        override val address: String,
        val localNickname: String?,
        val remoteProfileName: String?,
    ) : ConversationInfo

    data class Room(
        override val address: String,
        val subject: String?,
        val occupantCount: Int,
    ) : ConversationInfo
}

internal fun ConversationVenue.toConversationInfo(
    address: String,
    localNickname: String?,
    remoteProfileName: String?,
): ConversationInfo = when (this) {
    ConversationVenue.Direct -> ConversationInfo.DirectContact(
        address = address,
        localNickname = localNickname,
        remoteProfileName = remoteProfileName,
    )
    is ConversationVenue.Room -> ConversationInfo.Room(
        address = address,
        subject = subject,
        occupantCount = occupantCount,
    )
}
