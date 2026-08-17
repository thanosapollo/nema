package org.thanosapollo.nema.xmpp.chatstates

const val CHAT_STATES_NAMESPACE = "http://jabber.org/protocol/chatstates"

enum class ChatActivity {
    COMPOSING,
    PAUSED,
    ACTIVE,
    INACTIVE,
    GONE,
}

fun chatActivityNamed(element: String): ChatActivity? = when (element) {
    "composing" -> ChatActivity.COMPOSING
    "paused" -> ChatActivity.PAUSED
    "active" -> ChatActivity.ACTIVE
    "inactive" -> ChatActivity.INACTIVE
    "gone" -> ChatActivity.GONE
    else -> null
}

fun ChatActivity.elementName(): String = when (this) {
    ChatActivity.COMPOSING -> "composing"
    ChatActivity.PAUSED -> "paused"
    ChatActivity.ACTIVE -> "active"
    ChatActivity.INACTIVE -> "inactive"
    ChatActivity.GONE -> "gone"
}

fun applyComposer(composers: List<String>, actor: String, composing: Boolean): List<String> {
    val nick = actor.trim()
    if (nick.isEmpty()) return composers
    return if (composing) {
        if (nick in composers) composers else composers + nick
    } else {
        composers.filterNot { it == nick }
    }
}

fun typingLabel(composers: List<String>, directName: String? = null): String? = when {
    composers.isEmpty() -> null
    directName != null -> "$directName is typing..."
    composers.size == 1 -> "${composers.single()} is typing..."
    else -> "${composers.joinToString(", ")} are typing..."
}

const val OUTBOUND_COMPOSING_PAUSE_MS = 5_000L

fun nextOutboundChatState(
    last: ChatActivity?,
    composingNow: Boolean,
    pauseDue: Boolean = false,
    sent: Boolean = false,
): ChatActivity? = when {
    sent -> ChatActivity.ACTIVE.takeIf { last != ChatActivity.ACTIVE }
    composingNow && last != ChatActivity.COMPOSING -> ChatActivity.COMPOSING
    !composingNow && last != null && last != ChatActivity.ACTIVE -> ChatActivity.ACTIVE
    pauseDue && last == ChatActivity.COMPOSING -> ChatActivity.PAUSED
    else -> null
}
