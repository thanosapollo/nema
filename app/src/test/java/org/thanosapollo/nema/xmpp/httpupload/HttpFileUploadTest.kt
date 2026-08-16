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

    @Test
    fun imageMimeIsInlineExceptSvg() {
        assertEquals(true, isInlineImage("image/jpeg", "photo.bin"))
        assertEquals(true, isInlineImage(null, "photo.PNG"))
        assertEquals(false, isInlineImage("image/svg+xml", "icon.svg"))
        assertEquals(false, isInlineImage("application/pdf", "doc.pdf"))
    }

    @Test
    fun downloadLabelUsesNameAndState() {
        assertEquals("Download notes.txt", attachmentActionLabel(image = false, downloaded = false, name = "notes.txt"))
        assertEquals("Open notes.txt", attachmentActionLabel(image = false, downloaded = true, name = "notes.txt"))
        assertEquals("Download file", attachmentActionLabel(image = false, downloaded = false, name = null))
        assertEquals("Download image", attachmentActionLabel(image = true, downloaded = false, name = " "))
    }
}
