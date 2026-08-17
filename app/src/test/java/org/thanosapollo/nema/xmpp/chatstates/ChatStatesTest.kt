package org.thanosapollo.nema.xmpp.chatstates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatStatesTest {
    @Test
    fun parsesKnownActivitiesAndRejectsUnknown() {
        assertEquals(ChatActivity.COMPOSING, chatActivityNamed("composing"))
        assertEquals(ChatActivity.PAUSED, chatActivityNamed("paused"))
        assertEquals("composing", ChatActivity.COMPOSING.elementName())
        assertNull(chatActivityNamed("markable"))
    }

    @Test
    fun composerSetIsOrderedAndIdempotent() {
        val one = applyComposer(emptyList(), "debacle", composing = true)
        assertEquals(listOf("debacle"), one)
        assertEquals(listOf("debacle"), applyComposer(one, "debacle", composing = true))
        assertEquals(listOf("debacle", "wgreenhouse"), applyComposer(one, "wgreenhouse", composing = true))
        assertEquals(listOf("wgreenhouse"), applyComposer(listOf("debacle", "wgreenhouse"), "debacle", composing = false))
    }

    @Test
    fun typingCopyMatchesEmacsJabber() {
        assertEquals("Talos is typing...", typingLabel(listOf("peer"), directName = "Talos"))
        assertEquals("debacle is typing...", typingLabel(listOf("debacle")))
        assertEquals("debacle, wgreenhouse are typing...", typingLabel(listOf("debacle", "wgreenhouse")))
        assertNull(typingLabel(emptyList()))
        assertNull(typingLabel(emptyList(), directName = "Talos"))
    }
}
