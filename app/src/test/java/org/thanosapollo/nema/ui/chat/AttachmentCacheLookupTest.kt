package org.thanosapollo.nema.ui.chat

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import org.thanosapollo.nema.createRobolectricComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.TimelineMessage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AttachmentCacheLookupTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private fun row(url: String) = TimelineMessage("attachment", "peer@example.org", "", false, null, null, null)
        .copy(attachmentUrl = url, attachmentName = "file.pdf", attachmentMime = "application/pdf")

    @Test
    fun cacheLookupIsOffMainAndLateMissCannotUndoSuccessfulUse() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        val onMain = AtomicBoolean(true)
        val calls = AtomicInteger()
        val typing = mutableStateOf<String?>(null)
        try {
            compose.setContent {
                MaterialTheme {
                    MessageTimeline(listOf(row("https://example.org/file")), typingLabel = typing.value,
                        isAttachmentCached = {
                            calls.incrementAndGet()
                            onMain.set(Looper.myLooper() == Looper.getMainLooper())
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            finished.set(true)
                            false
                        }, onUseAttachment = { _, _, _ -> true })
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(onMain.get())
            compose.onNodeWithTag("message-attachment").performClick()
            compose.onNodeWithTag("message-attachment").assertTextEquals("Open file.pdf")
            compose.runOnIdle { typing.value = "Typing" }
            assertEquals(1, calls.get())
            release.countDown()
            compose.waitUntil(5_000) { finished.get() }
            compose.waitForIdle()
            compose.onNodeWithTag("message-attachment").assertTextEquals("Open file.pdf")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun urlReplacementRejectsHeldOldCacheHit() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val oldFinished = AtomicBoolean(false)
        val newFinished = AtomicBoolean(false)
        val url = mutableStateOf("https://example.org/old")
        try {
            compose.setContent {
                MaterialTheme {
                    MessageTimeline(listOf(row(url.value)), isAttachmentCached = {
                        if (it.endsWith("old")) {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            oldFinished.set(true)
                            true
                        } else {
                            newFinished.set(true)
                            false
                        }
                    })
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            compose.runOnIdle { url.value = "https://example.org/new" }
            compose.onNodeWithTag("message-attachment").assertTextEquals("Download file.pdf")
            release.countDown()
            compose.waitUntil(5_000) { oldFinished.get() && newFinished.get() }
            compose.waitForIdle()
            compose.onNodeWithTag("message-attachment").assertTextEquals("Download file.pdf")
            compose.onNodeWithText("Open file.pdf").assertDoesNotExist()
        } finally {
            release.countDown()
        }
    }
}
