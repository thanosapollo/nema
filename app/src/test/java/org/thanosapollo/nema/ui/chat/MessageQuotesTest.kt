package org.thanosapollo.nema.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageQuotesTest {
    @Test
    fun plainBodyStaysOneSegment() {
        assertEquals(listOf(BodySegment.Plain("hello")), parseQuotedBody("hello"))
    }

    @Test
    fun leadingQuoteSplitsFromAnswer() {
        assertEquals(
            listOf(BodySegment.Quote(1, "manually quoted"), BodySegment.Plain("plain answer")),
            parseQuotedBody("> manually quoted\nplain answer"),
        )
    }

    @Test
    fun midBodyAndNestedQuotesAreKept() {
        assertEquals(
            listOf(
                BodySegment.Plain("intro"),
                BodySegment.Quote(2, "nested"),
                BodySegment.Quote(1, "outer"),
                BodySegment.Plain("outro"),
            ),
            parseQuotedBody("intro\n>> nested\n> outer\n\noutro"),
        )
    }

    @Test
    fun spacedNestedMarkersStripEveryPrefix() {
        assertEquals(
            listOf(BodySegment.Quote(2, "old quote"), BodySegment.Plain("answer")),
            parseQuotedBody("> > old quote\nanswer"),
        )
    }
}
