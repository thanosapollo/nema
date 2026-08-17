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

    @Test
    fun outboundComposingPausesAndClearsWithoutResend() {
        assertEquals(ChatActivity.COMPOSING, nextOutboundChatState(null, composingNow = true))
        assertNull(nextOutboundChatState(ChatActivity.COMPOSING, composingNow = true))
        assertEquals(
            ChatActivity.PAUSED,
            nextOutboundChatState(ChatActivity.COMPOSING, composingNow = true, pauseDue = true),
        )
        assertEquals(ChatActivity.COMPOSING, nextOutboundChatState(ChatActivity.PAUSED, composingNow = true))
        assertEquals(ChatActivity.ACTIVE, nextOutboundChatState(ChatActivity.COMPOSING, composingNow = false))
        assertNull(nextOutboundChatState(null, composingNow = false))
        assertEquals(ChatActivity.ACTIVE, nextOutboundChatState(ChatActivity.COMPOSING, composingNow = true, sent = true))
    }

    @Test
    fun composingPauseUsesLastKeystrokeNotFirstEmit() {
        val hub = OutboundChatStateHub()
        assertEquals(ChatActivity.COMPOSING, hub.onDraft("peer@example.org", composingNow = true, nowMs = 0))
        assertNull(hub.onDraft("peer@example.org", composingNow = true, nowMs = 4_000))
        assertEquals(emptyList<Pair<String, ChatActivity>>(), hub.duePauses(5_000))
        assertEquals(listOf("peer@example.org" to ChatActivity.PAUSED), hub.duePauses(9_000))
        assertEquals(emptyList<Pair<String, ChatActivity>>(), hub.duePauses(9_000))
        assertEquals(ChatActivity.COMPOSING, hub.onDraft("peer@example.org", composingNow = true, nowMs = 9_500))
    }
}
