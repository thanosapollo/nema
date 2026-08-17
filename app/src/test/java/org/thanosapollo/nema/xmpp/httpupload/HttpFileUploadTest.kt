package org.thanosapollo.nema.xmpp.httpupload

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpFileUploadTest {
    @Test
    fun namedAttachmentUsesNameNotUrl() {
        assertEquals(
            "notes.txt · text/plain",
            attachmentCaption("notes.txt", "https://example.org/abc", null),
        )
    }

    @Test
    fun captionAddsSizeWhenKnown() {
        assertEquals(
            "notes.txt · text/plain · 42 B",
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
    fun extensionlessUploadKeepsMimeAndSlotName() {
        assertEquals("image_1603.jpg", slotFilename("image:1603", "image/jpeg"))
        assertEquals("notes.txt", slotFilename("notes.txt", "text/plain"))
        assertEquals("image/jpeg", resolvedAttachmentMime(null, null, "https://chat.example/upload/hash/image_1603"))
        assertEquals("image/png", resolvedAttachmentMime(null, "pic.png", "https://chat.example/upload/hash/KmdayI"))
        assertEquals("image/jpeg", resolvedAttachmentMime("image/jpeg", "image_1603", "https://chat.example/x"))
        assertEquals(true, isInlineImage(null, null, "https://chat.example/upload/hash/image_1603"))
        assertEquals(
            "image_1603 · image/jpeg",
            attachmentCaption(null, "https://chat.example/upload/hash/image_1603", null, "image/jpeg"),
        )
    }

    @Test
    fun downloadLabelUsesNameAndState() {
        assertEquals("Download notes.txt", attachmentActionLabel(image = false, downloaded = false, name = "notes.txt"))
        assertEquals("Open notes.txt", attachmentActionLabel(image = false, downloaded = true, name = "notes.txt"))
        assertEquals("Download file", attachmentActionLabel(image = false, downloaded = false, name = null))
        assertEquals("Download image", attachmentActionLabel(image = true, downloaded = false, name = " "))
    }

    @Test
    fun attachmentCaptionHidesUrlOnlyBody() {
        assertEquals(null, attachmentBodyCaption("https://example.org/pic.png", "https://example.org/pic.png"))
        assertEquals(null, attachmentBodyCaption("  https://example.org/pic.png\n", "https://example.org/pic.png"))
        assertEquals(null, attachmentBodyCaption("", "https://example.org/pic.png"))
        assertEquals("look at this", attachmentBodyCaption("look at this", "https://example.org/pic.png"))
        assertEquals("hello", attachmentBodyCaption("hello", null))
    }

    @Test
    fun onlyHttpsAttachmentUrlsAreFetched() {
        assertEquals(
            "https://example.org/abc",
            httpsAttachmentUrl("https://example.org/abc"),
        )
        assertEquals(null, httpsAttachmentUrl("http://example.org/abc"))
        assertEquals(null, httpsAttachmentUrl("https://user:pass@example.org/abc"))
        assertEquals(null, httpsAttachmentUrl("javascript:alert(1)"))
    }

    @Test
    fun cacheRoundTripStoresFetchedHttpsBytes() {
        val dir = createTempDir(prefix = "nema-attach")
        try {
            val url = "https://example.org/abc"
            assertEquals(null, cachedAttachment(dir, url))
            val stored = persistFetchedAttachment(dir, url) { "hello".toByteArray() }
            assertEquals("hello", stored?.readText())
            assertEquals(stored, cachedAttachment(dir, url))
            val reused = persistFetchedAttachment(dir, url) { error("must not refetch") }
            assertEquals(stored, reused)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistRejectsEmptyOrOversizedPayload() {
        val dir = createTempDir(prefix = "nema-attach")
        try {
            assertEquals(null, persistFetchedAttachment(dir, "https://example.org/a") { ByteArray(0) })
            assertEquals(
                null,
                persistFetchedAttachment(dir, "https://example.org/b", maxBytes = 4) { "hello".toByteArray() },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun persistSwallowsFetchExceptions() {
        val dir = createTempDir(prefix = "nema-attach")
        try {
            assertEquals(
                null,
                persistFetchedAttachment(dir, "https://example.org/boom") { error("dns") },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun httpsFetchStepFollowsHttpsRedirectsAndRejectsUnsafeOrFailedResponses() {
        val current = "https://example.org/start"
        assertEquals(
            HttpsFetchStep.Follow("https://example.org/next"),
            httpsFetchStep(current, 302, "https://example.org/next", -1, 100),
        )
        assertEquals(
            HttpsFetchStep.Follow("https://example.org/rel"),
            httpsFetchStep(current, 301, "/rel", -1, 100),
        )
        assertEquals(HttpsFetchStep.Reject, httpsFetchStep(current, 302, "http://evil.example/x", -1, 100))
        assertEquals(HttpsFetchStep.Reject, httpsFetchStep(current, 302, null, -1, 100))
        assertEquals(HttpsFetchStep.Reject, httpsFetchStep(current, 404, null, 10, 100))
        assertEquals(HttpsFetchStep.Reject, httpsFetchStep(current, 200, null, 101, 100))
        assertEquals(HttpsFetchStep.ReadBody, httpsFetchStep(current, 200, null, -1, 100))
        assertEquals(HttpsFetchStep.ReadBody, httpsFetchStep(current, 200, null, 50, 100))
    }

    @Test
    fun inlineImageOnlyInDirectChats() {
        assertEquals(true, shouldRenderInlineImage(groupChat = false, mime = "image/png", name = "a.png"))
        assertEquals(false, shouldRenderInlineImage(groupChat = true, mime = "image/png", name = "a.png"))
        assertEquals(false, shouldRenderInlineImage(groupChat = false, mime = "application/pdf", name = "a.pdf"))
        assertEquals(null, decodeInlineImage(File("/tmp/nema-missing-inline.png")))
    }
}
