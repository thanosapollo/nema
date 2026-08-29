package org.thanosapollo.nema.ui.chat

import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.thread.MessageKind

internal sealed interface ConversationVenue {
    val messageKind: MessageKind

    data object Direct : ConversationVenue {
        override val messageKind = MessageKind.CHAT
    }

    data class Room(
        val subject: String?,
        val occupantCount: Int,
    ) : ConversationVenue {
        override val messageKind = MessageKind.GROUPCHAT
    }
}

internal fun DirectChatState.conversationVenue(): ConversationVenue =
    if (selectedPeerGroupChat) {
        ConversationVenue.Room(
            subject = selectedRoomSubject,
            occupantCount = selectedRoomOccupantCount,
        )
    } else {
        ConversationVenue.Direct
    }
