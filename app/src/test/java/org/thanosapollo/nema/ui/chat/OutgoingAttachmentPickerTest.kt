package org.thanosapollo.nema.ui.chat

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.RoutePresentationFixture
import org.thanosapollo.nema.createRobolectricComposeRule
import org.thanosapollo.nema.xmpp.httpupload.MAX_ATTACHMENT_BYTES
import org.thanosapollo.nema.xmpp.httpupload.UploadedFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutgoingAttachmentPickerTest {
    @get:Rule val composeRule = createRobolectricComposeRule()

    @Test fun knownOversizeDoesNotReadOrUpload() {
        val source = Source(MAX_ATTACHMENT_BYTES + 1)
        pick(source, declaredSize = MAX_ATTACHMENT_BYTES + 1, accepted = false)
        assertEquals(0L, source.consumed)
        assertEquals(0, source.reads.get())
        assertFalse(source.closed)
    }

    @Test fun unknownLengthReadsOnlyLimitAndSentinel() {
        val source = Source(MAX_ATTACHMENT_BYTES + 8192)
        pick(source, accepted = false)
        assertEquals(MAX_ATTACHMENT_BYTES + 1, source.consumed)
        assertEquals(1, source.lastReadRequest)
        assertTrue(source.closed)
    }

    @Test fun understatedLengthStillReadsOnlyLimitAndSentinel() {
        val source = Source(MAX_ATTACHMENT_BYTES + 8192)
        pick(source, declaredSize = 1L, accepted = false)
        assertEquals(MAX_ATTACHMENT_BYTES + 1, source.consumed)
        assertEquals(1, source.lastReadRequest)
        assertTrue(source.closed)
    }

    @Test fun negativeLengthStillReadsOnlyLimitAndSentinel() {
        val source = Source(MAX_ATTACHMENT_BYTES + 8192)
        pick(source, declaredSize = -1L, accepted = false)
        assertEquals(MAX_ATTACHMENT_BYTES + 1, source.consumed)
        assertEquals(1, source.lastReadRequest)
        assertTrue(source.closed)
    }

    @Test fun unknownExactLimitUploadsAndCloses() {
        val source = Source(MAX_ATTACHMENT_BYTES)
        pick(source, accepted = true)
        assertEquals(MAX_ATTACHMENT_BYTES, source.consumed)
        assertEquals(1, source.lastReadRequest)
        assertTrue(source.closed)
    }

    @Test fun exactLimitUploadsAndCloses() {
        val source = Source(MAX_ATTACHMENT_BYTES)
        pick(source, declaredSize = MAX_ATTACHMENT_BYTES, accepted = true)
        assertEquals(MAX_ATTACHMENT_BYTES, source.consumed)
        assertEquals(1, source.lastReadRequest)
        assertTrue(source.closed)
    }

    @Test fun emptySourceRetainsDraftWithoutUpload() {
        val source = Source(0)
        pick(source, accepted = false)
        assertTrue(source.closed)
    }

    @Test fun streamExceptionRetainsDraftWithoutUpload() {
        val source = Source(8192, failAfterFirst = true)
        pick(source, accepted = false)
        assertTrue(source.closed)
    }

    @Test fun midReadCancellationClosesWithoutAnotherReadOrUpload() {
        val source = Source(8192, holdFirst = true)
        pick(source, accepted = false, cancelRead = true)
        assertEquals(1, source.reads.get())
        assertTrue(source.closed)
    }

    @Test fun cancelledReadIOExceptionIsNotShownAsRejection() {
        val source = Source(8192, holdFirst = true, failHeldRead = true)
        pick(source, accepted = false, cancelRead = true)
        assertEquals(1, source.reads.get())
        assertEquals(0L, source.consumed)
        assertTrue(source.closed)
    }

    private fun pick(source: Source, declaredSize: Long? = null, accepted: Boolean, cancelRead: Boolean = false) {
        ShadowToast.reset()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val uri = Uri.parse("content://bounded-upload/file.bin")
        val provider = SizeProvider(declaredSize)
        provider.attachInfo(context, ProviderInfo().apply { authority = "bounded-upload" })
        ShadowContentResolver.registerProviderInternal("bounded-upload", provider)
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, source)
        val registry = PickerRegistry()
        val uploads = AtomicInteger()
        val saves = AtomicInteger()
        lateinit var owner: ComposerOwner
        RoutePresentationFixture().use { fixture ->
            val presenter = fixture.presenter()
            composeRule.setContent {
                val state by presenter.state.collectAsState()
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    owner = rememberComposerOwner(state.accountId)
                    MaterialTheme {
                        ConversationContent(state = state, composerOwner = owner, connectionStatus = "Connected",
                            onSelectPeer = presenter::selectPeer, onCloseConversation = presenter::closeConversation,
                            onSend = presenter::sendDraft,
                            onDraftChange = { saves.incrementAndGet(); CompletableDeferred(true) },
                            onUploadFile = { name, mime, bytes ->
                                uploads.incrementAndGet()
                                assertEquals(source.length, bytes.size.toLong())
                                assertTrue(bytes.all { it == 7.toByte() })
                                UploadedFile("https://example.org/upload", name, mime, bytes.size.toLong())
                            })
                    }
                }
            }
            runBlocking { presenter.selectPeer(fixture.peer) }
            composeRule.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
            composeRule.waitForIdle()
            val key = DirectConversationKey(fixture.account, fixture.peer)
            val before = owner.composerStates.value.getValue(key)
            assertEquals("stored A", before.body)
            assertNotNull(before.reply)
            composeRule.onNodeWithContentDescription("Attach file").performClick()
            composeRule.runOnIdle { registry.deliver(uri) }
            try {
                if (cancelRead) {
                    assertTrue("read did not enter", source.entered.await(5, TimeUnit.SECONDS))
                    composeRule.runOnIdle {
                        owner.scope.coroutineContext[Job]!!.children.forEach { it.cancel() }
                    }
                }
            } finally { source.release.countDown() }
            // Wait for the actual picker job, not just stream close or Compose idle.
            composeRule.waitUntil(timeoutMillis = 10_000) {
                // Compose's scheduler does not drain Android's paused Main looper:
                // the real rejection Toast switches from the IO continuation to Main.
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                owner.scope.coroutineContext[Job]!!.children.all { it.isCompleted }
            }
            composeRule.runOnIdle {
                provider.cursor?.let { assertTrue("metadata cursor not closed", it.isClosed) }
                if (accepted) {
                    assertEquals(1, uploads.get())
                    assertEquals(1, saves.get())
                    val after = owner.composerStates.value.getValue(key)
                    assertEquals(before.body, after.body)
                    assertEquals(before.reply, after.reply)
                    assertEquals("https://example.org/upload", after.attachmentUrl)
                    assertEquals(source.length, after.attachmentSize)
                } else {
                    // No upload callback means no downstream discovery, slot or HTTP effects.
                    assertEquals(0, uploads.get())
                    assertEquals(0, saves.get())
                    assertEquals(before, owner.composerStates.value.getValue(key))
                    if (!cancelRead) {
                        val expected = when {
                            source.length > MAX_ATTACHMENT_BYTES -> "Attachment is too large (maximum 25 MiB)"
                            source.length == 0L -> "Attachment is empty"
                            else -> "Unable to read attachment"
                        }
                        assertEquals(expected, ShadowToast.getTextOfLatestToast())
                    }
                }
                if (accepted || cancelRead) assertNull(ShadowToast.getTextOfLatestToast())
            }
        }
    }

    private class Source(
        val length: Long,
        val failAfterFirst: Boolean = false,
        val holdFirst: Boolean = false,
        val failHeldRead: Boolean = false,
    ) : InputStream() {
        @Volatile var consumed = 0L
        @Volatile var closed = false
        @Volatile var lastReadRequest = 0
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            lastReadRequest = length
            val call = reads.incrementAndGet()
            if (failAfterFirst && call > 1) throw IOException("fixture read failure")
            if (holdFirst && call == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "fixture read not released" }
                if (failHeldRead) throw IOException("fixture cancelled read failure")
            }
            if (consumed == this.length) return -1
            val count = minOf(length.toLong(), this.length - consumed).toInt()
            buffer.fill(7, offset, offset + count)
            consumed += count
            return count
        }
        override fun close() { closed = true }
    }

    private class SizeProvider(private val size: Long?) : ContentProvider() {
        var cursor: MatrixCursor? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
            MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(size)); cursor = this }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    private class PickerRegistry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry get() = this
        private var request = -1
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            request = requestCode
        }
        fun deliver(uri: Uri) { check(request != -1); dispatchResult(request, uri) }
    }
}
