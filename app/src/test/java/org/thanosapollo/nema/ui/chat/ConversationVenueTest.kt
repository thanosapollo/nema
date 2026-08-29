package org.thanosapollo.nema.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.thread.MessageKind

class ConversationVenueTest {
    @Test
    fun directStateMapsToChatVenue() {
        val venue = DirectChatState(
            accountId = "account@example.org",
            selectedPeer = "peer@example.org",
        ).conversationVenue()

        assertEquals(ConversationVenue.Direct, venue)
        assertEquals(MessageKind.CHAT, venue.messageKind)
    }

    @Test
    fun roomStateMapsSubjectOccupantsAndGroupchatVenue() {
        val venue = DirectChatState(
            accountId = "account@example.org",
            selectedPeer = "room@conference.example.org",
            selectedPeerGroupChat = true,
            selectedRoomSubject = "Kotlin",
            selectedRoomOccupantCount = 42,
        ).conversationVenue()

        assertEquals(
            ConversationVenue.Room(subject = "Kotlin", occupantCount = 42),
            venue,
        )
        assertEquals(MessageKind.GROUPCHAT, venue.messageKind)
        assertEquals("Kotlin", (venue as ConversationVenue.Room).subject)
        assertEquals(42, venue.occupantCount)
    }

    @Test
    fun venueKindProducesExactDraftWireEvidence() {
        val composer = ComposerState(
            key = DirectConversationKey("account@example.org", "peer@example.org"),
            body = "hello",
            revision = 1,
            failureRevision = null,
        )

        assertFalse(composer.toDraftSnapshot(ConversationVenue.Direct).groupChat)
        assertTrue(composer.toDraftSnapshot(ConversationVenue.Room(null, 0)).groupChat)
    }
}
