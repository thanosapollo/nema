package org.thanosapollo.nema.ui.chat

import org.thanosapollo.nema.chat.DirectConversationKey

/** Bounded presentation cache, owned by the account composition rather than Room. */
internal class TimelineViewportStore(private val capacity: Int = 32) {
    init {
        require(capacity > 0) { "Viewport capacity must be positive" }
    }

    private val anchors = object : LinkedHashMap<DirectConversationKey, TimelineViewportAnchor>(
        capacity,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<DirectConversationKey, TimelineViewportAnchor>?,
        ): Boolean = size > capacity
    }

    val size: Int
        get() = anchors.size

    operator fun get(key: DirectConversationKey): TimelineViewportAnchor? = anchors[key]

    operator fun set(key: DirectConversationKey, anchor: TimelineViewportAnchor) {
        anchors[key] = anchor
    }
}
