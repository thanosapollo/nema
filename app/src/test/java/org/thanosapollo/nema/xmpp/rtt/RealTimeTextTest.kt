package org.thanosapollo.nema.xmpp.rtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealTimeTextTest {
    @Test
    fun replaysInsertAndEraseExample() {
        assertEquals(
            "Hello there, World",
            applyRttActions(
                "",
                listOf(
                    RttAction.Insert(null, "Helo"),
                    RttAction.Erase(null, null),
                    RttAction.Insert(null, "lo...planet"),
                    RttAction.Erase(null, 6),
                    RttAction.Insert(null, " World"),
                    RttAction.Erase(8, 3),
                    RttAction.Insert(5, " there,"),
                ),
            ),
        )
        assertEquals("hello ", applyRttActions("hello", listOf(RttAction.Insert(null, " "))))
        assertEquals("hello", applyRttActions("hello", listOf(RttAction.Insert(null, ""))))
    }

    @Test
    fun newReplacesAndEditRequiresNextSeq() {
        val started = requireNotNull(applyRtt(null, RttElement(10, RttEvent.NEW, listOf(RttAction.Insert(null, "hi")))))
        val edited = requireNotNull(applyRtt(started, RttElement(11, RttEvent.EDIT, listOf(RttAction.Insert(null, "!")))))
        val gapped = requireNotNull(applyRtt(edited, RttElement(13, RttEvent.EDIT, listOf(RttAction.Insert(null, "?")))))
        val ignored = requireNotNull(applyRtt(gapped, RttElement(14, RttEvent.EDIT, listOf(RttAction.Insert(null, "?")))))
        val reset = requireNotNull(applyRtt(ignored, RttElement(20, RttEvent.RESET, listOf(RttAction.Insert(null, "fresh")))))
        assertEquals("hi", started.text)
        assertEquals("hi!", edited.text)
        assertTrue(gapped.outOfSync)
        assertEquals("hi!", ignored.text)
        assertEquals("fresh", reset.text)
        assertEquals(false, reset.outOfSync)
    }

    @Test
    fun bodyOrCancelClears() {
        val live = requireNotNull(applyRtt(null, RttElement(1, RttEvent.NEW, listOf(RttAction.Insert(null, "draft")))))
        assertNull(applyRtt(live, RttElement(2, RttEvent.CANCEL, emptyList())))
        assertNull(applyRtt(live, RttElement(2, RttEvent.EDIT, emptyList()), hasBody = true))
    }

    @Test
    fun liveLabelPrefersRttText() {
        assertEquals("[typing...] hello", liveTypingLabel(listOf("peer"), "Talos", "hello"))
        assertEquals("[typing...]", liveTypingLabel(emptyList(), "Talos", ""))
        assertEquals("Talos is typing...", liveTypingLabel(listOf("peer"), "Talos", null))
        assertNull(liveTypingLabel(emptyList(), "Talos", null))
    }
}
