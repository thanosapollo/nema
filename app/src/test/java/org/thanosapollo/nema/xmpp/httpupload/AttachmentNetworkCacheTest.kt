package org.thanosapollo.nema.xmpp.httpupload

import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentNetworkCacheTest {
    @Test fun scopedOfflineHitPrecedesRetiredNetworkAndAnotherAccountCannotReuseBytes() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("attachment-route").toFile()
        try {
            val first = File(root, "attachments/${attachmentCacheKey("account-a")}")
            val second = File(root, "attachments/${attachmentCacheKey("account-b")}")
            val url = "https://fixture.invalid/file"
            storeAttachment(first, url, byteArrayOf(1, 2, 3))
            var calls = 0
            val retired: suspend (String) -> ByteArray? = { calls++; throw java.io.IOException("Retired session") }
            assertArrayEquals(byteArrayOf(1, 2, 3), fetchAndCacheAttachment(first, url, retired)!!.readBytes())
            assertEquals(0, calls)
            assertTrue(runCatching { fetchAndCacheAttachment(second, url, retired) }.isFailure)
            assertEquals(1, calls)
            assertNull(cachedAttachment(second, url))
        } finally { root.deleteRecursively() }
    }

    @Test fun cancelledFetchCannotPublishEvenIfProducerReturnsBytes() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("attachment-cancel").toFile()
        val url = "https://fixture.invalid/file"
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fetch = launch {
                fetchAndCacheAttachment(root, url) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    byteArrayOf(1, 2, 3)
                }
            }
            started.await()
            fetch.cancel()
            release.complete(Unit)
            fetch.join()
            assertTrue(fetch.isCancelled)
            assertNull(cachedAttachment(root, url))
            assertFalse(attachmentCacheFile(root, url).exists())
        } finally { root.deleteRecursively() }
    }
}
