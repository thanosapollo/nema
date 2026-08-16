package org.thanosapollo.nema.xmpp.httpupload

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpFileUploadTest {
    @Test
    fun namedAttachmentUsesNameNotUrl() {
        assertEquals(
            "notes.txt",
            attachmentCaption("notes.txt", "https://example.org/abc", null),
        )
    }

    @Test
    fun captionAddsSizeWhenKnown() {
        assertEquals(
            "notes.txt · 42 B",
            attachmentCaption("notes.txt", "https://example.org/abc", 42),
        )
    }
}
