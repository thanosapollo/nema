package org.thanosapollo.nema.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DirectChatStateTest {
    @Test
    fun timelineEmissionsAreComparedAsSnapshots() {
        val messages = timelineSnapshot()
        val state = DirectChatState(accountId = "account", messages = messages)
        val sameSnapshot = state.copy()
        val replacementSnapshot = state.copy(messages = timelineSnapshot())

        assertEquals(state, sameSnapshot)
        assertEquals(state.hashCode(), sameSnapshot.hashCode())
        assertNotEquals(state, replacementSnapshot)
    }

    private fun timelineSnapshot(): List<TimelineMessage> = object : AbstractList<TimelineMessage>() {
        private val values = listOf(
            TimelineMessage(
                id = "message",
                senderJid = "peer@example.org",
                body = "body",
                outgoing = false,
                delivery = null,
                retryUncertainKey = null,
                thread = null,
            ),
        )

        override val size: Int = values.size

        override fun get(index: Int): TimelineMessage = values[index]

        override fun equals(other: Any?): Boolean = error("Timeline snapshot was structurally compared")

        override fun hashCode(): Int = error("Timeline snapshot was structurally hashed")
    }
}
