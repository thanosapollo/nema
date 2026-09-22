package org.thanosapollo.nema.ui.chat

import org.thanosapollo.nema.chat.TimelineMessage

/** Snapshot-owned lookup: layout changes must visit visible rows, not the entire history. */
internal class TimelineReadIndex(messages: List<TimelineMessage>) {
    val orderedIds: List<String> = messages.map { it.id }
    private val membership = orderedIds.toHashSet()
    private val markers = messages.withIndex().filter {
        val message = it.value
        !message.outgoing && !message.groupChat && message.markable && !message.markerTargetId.isNullOrEmpty()
    }.associateBy { it.value.id }
    val markerSignature: Int = messages.fold(0) { signature, message ->
        if (message.markable) {
            31 * (31 * signature + message.id.hashCode()) + (message.markerTargetId?.hashCode() ?: 0)
        } else signature
    }

    fun contains(id: String): Boolean = id in membership

    fun displayedMarkerCandidates(
        visibleMessageIds: Set<String>,
        enabled: Boolean,
        resumed: Boolean,
        venue: ConversationVenue,
    ): List<TimelineMessage> {
        if (!enabled || !resumed || venue is ConversationVenue.Room) return emptyList()
        // Preserve chronological dispatch (including duplicate wire-target suppression),
        // independently of the reverse-layout order of the visible keys.
        return visibleMessageIds.mapNotNull(markers::get).sortedBy { it.index }.map { it.value }
    }
}
