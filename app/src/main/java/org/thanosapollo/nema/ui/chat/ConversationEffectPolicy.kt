package org.thanosapollo.nema.ui.chat

import org.thanosapollo.nema.chat.ChatRouteOccurrence
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.TimelineMessage

/** A destination value alone cannot authorize an effect in a later visit to that destination. */
internal data class ConversationEffectOwner(
    val key: DirectConversationKey,
    val occurrence: ChatRouteOccurrence,
)

internal fun ownsSendViewport(
    origin: ConversationEffectOwner?,
    current: ConversationEffectOwner,
): Boolean = origin == current

internal data class TimelineScrollTarget(val index: Int, val offset: Int)

/** At the exact bottom the user's intent is Latest, not a bookmark to yesterday's last row. */
internal fun restoredTimelineTarget(
    messages: List<TimelineMessage>,
    anchor: TimelineViewportAnchor,
    typingPresent: Boolean,
): TimelineScrollTarget = if (anchor.fallbackIndex == 0 && anchor.offset == 0) {
    TimelineScrollTarget(0, 0)
} else {
    TimelineScrollTarget(timelineListIndex(restoredTimelineIndex(messages, anchor), typingPresent), anchor.offset)
}

data class TimelineViewportAnchor(
    val messageId: String,
    val offset: Int,
    val fallbackIndex: Int,
)

internal fun restoredTimelineIndex(
    messages: List<TimelineMessage>,
    anchor: TimelineViewportAnchor,
): Int {
    require(messages.isNotEmpty()) { "Cannot restore an empty timeline" }
    val exact = messages.asReversed().indexOfFirst { it.id == anchor.messageId }
    return if (exact >= 0) exact else anchor.fallbackIndex.coerceIn(0, messages.lastIndex)
}

internal fun timelineMessageIndex(listIndex: Int, typingPresent: Boolean, messageCount: Int): Int {
    if (messageCount <= 0) return 0
    val offset = if (typingPresent) 1 else 0
    return (listIndex - offset).coerceIn(0, messageCount - 1)
}

internal fun timelineListIndex(messageIndex: Int, typingPresent: Boolean): Int {
    if (!typingPresent) return messageIndex
    return if (messageIndex == 0) 0 else messageIndex + 1
}
