package org.thanosapollo.nema.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.chat.ConversationSummary

class ConversationPresentationTest {
    @Test
    fun connectedStatusStaysQuiet() {
        assertNull(quietConnectionStatus("Connected"))
        assertEquals("Connecting", quietConnectionStatus("Connecting"))
        assertEquals("Sign-in failed", quietConnectionStatus("Sign-in failed"))
    }

    @Test
    fun previewUsesFirstLineAndHidesPayload() {
        assertEquals("hello there", conversationPreview("hello there"))
        assertEquals("first", conversationPreview("  first  \nsecond"))
        assertEquals("Message", conversationPreview("<message><body>raw</body></message>"))
        assertEquals("<3 thanks", conversationPreview("<3 thanks"))
        val long = "a".repeat(90)
        assertEquals("a".repeat(80) + "…", conversationPreview(long))
    }

    @Test
    fun searchMatchesLabelOrJid() {
        val conversation = ConversationSummary(
            peerJid = "alice@example.org",
            preview = "later",
            localSequence = 1,
            displayName = "Alice",
        )
        assertTrue(conversationMatches(conversation, ""))
        assertTrue(conversationMatches(conversation, "ali"))
        assertTrue(conversationMatches(conversation, "EXAMPLE"))
        assertFalse(conversationMatches(conversation, "bob"))
    }

    @Test
    fun unreadBadgeCapsAtNinetyNine() {
        assertNull(unreadBadgeLabel(0))
        assertEquals("3", unreadBadgeLabel(3))
        assertEquals("99+", unreadBadgeLabel(100))
    }
}
