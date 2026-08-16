package org.thanosapollo.nema.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageLinksTest {
    @Test
    fun plainWordsStayUnlinked() {
        assertEquals(listOf(TextRun.Plain("see the docs later")), parseHttpLinks("see the docs later"))
    }

    @Test
    fun httpsUrlBecomesALink() {
        assertEquals(
            listOf(
                TextRun.Plain("read "),
                TextRun.Url("https://example.org/a"),
                TextRun.Plain(" please"),
            ),
            parseHttpLinks("read https://example.org/a please"),
        )
    }

    @Test
    fun trailingPunctuationStaysOutsideTheLink() {
        assertEquals(
            listOf(TextRun.Url("http://example.org"), TextRun.Plain(".")),
            parseHttpLinks("http://example.org."),
        )
    }

    @Test
    fun annotatedBodyMarksHttpsAsALink() {
        val annotated = linkedMessageBody("see https://example.org")
        assertEquals(1, annotated.getLinkAnnotations(0, annotated.length).size)
        assertEquals("see https://example.org", annotated.text)
    }
}
