package org.thanosapollo.nema.ui

import org.thanosapollo.nema.chat.ConversationSummary

internal const val CONNECTED_STATUS = "Connected"

fun quietConnectionStatus(status: String): String? =
    status.takeUnless { it == CONNECTED_STATUS }

fun conversationPreview(body: String): String {
    val line = body.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    if (PAYLOAD_PREFIX.containsMatchIn(line)) return "Message"
    return if (line.length <= PREVIEW_LIMIT) line else line.take(PREVIEW_LIMIT).trimEnd() + "…"
}

fun conversationMatches(conversation: ConversationSummary, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return conversation.displayLabel.contains(needle, ignoreCase = true) ||
        conversation.peerJid.contains(needle, ignoreCase = true)
}

fun unreadBadgeLabel(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

private val PAYLOAD_PREFIX = Regex("^<[A-Za-z!?/]")
private const val PREVIEW_LIMIT = 80
