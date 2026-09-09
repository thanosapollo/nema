package org.thanosapollo.nema.ui.chat

import org.junit.Assert.*
import org.junit.Test
import org.thanosapollo.nema.chat.ChatRoute
import org.thanosapollo.nema.chat.ChatRouteOccurrence
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.DeliveryPresentation
import org.thanosapollo.nema.chat.TimelineMessage
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

class ConversationEffectPolicyTest {
    @Test fun sendViewportBelongsToExactAccountPeerLineageAndVisit() {
        val thread = ThreadRef(ThreadId.require("child"), ThreadId.require("parent"))
        val key = DirectConversationKey("account", "peer@example.org", thread)
        val owner = ConversationEffectOwner(key, ChatRouteOccurrence(ChatRoute(key.canonicalBarePeer, thread), 7))
        assertTrue(ownsSendViewport(owner, owner.copy()))
        assertFalse(ownsSendViewport(null, owner))
        listOf(
            owner.copy(key = key.copy(accountId = "other-account")),
            owner.copy(key = key.copy(canonicalBarePeer = "other@example.org")),
            owner.copy(key = key.copy(thread = null)),
            owner.copy(key = key.copy(thread = thread.copy(parentId = ThreadId.require("different-parent")))),
            owner.copy(occurrence = owner.occurrence.copy(generation = 8)),
            owner.copy(occurrence = ChatRouteOccurrence(null, 8)),
        ).forEach { successor -> assertFalse(ownsSendViewport(owner, successor)) }
    }

    @Test fun latestIntentIsNotAnOldMessageBookmark() {
        val anchor = TimelineViewportAnchor("row-2", 0, 0)
        assertEquals(TimelineScrollTarget(0, 0), restoredTimelineTarget(rows, anchor, false))
        assertEquals(TimelineScrollTarget(0, 0), restoredTimelineTarget(rows, anchor, true))
        assertEquals(TimelineViewportAnchor("row-2", 0, 0), anchor)
    }

    @Test fun detachedAnchorKeepsIdentityAndPixelsWithTypingAndMissingRows() {
        val anchor = TimelineViewportAnchor("row-2", 17, 0)
        assertEquals(TimelineScrollTarget(2, 17), restoredTimelineTarget(rows, anchor, false))
        assertEquals(TimelineScrollTarget(3, 17), restoredTimelineTarget(rows, anchor, true))
        val missing = TimelineViewportAnchor("deleted", 13, 99)
        assertEquals(TimelineScrollTarget(4, 13), restoredTimelineTarget(rows, missing, false))
        assertEquals(TimelineScrollTarget(5, 13), restoredTimelineTarget(rows, missing, true))
        assertEquals(0, timelineMessageIndex(0, true, rows.size))
        assertEquals(2, timelineMessageIndex(3, true, rows.size))
        assertEquals(0, timelineMessageIndex(3, true, 0))
    }

    private val rows = (0..4).map { index ->
        TimelineMessage("row-$index", "peer@example.org", "row-$index", false,
            DeliveryPresentation.SENT, null, null)
    }
}
