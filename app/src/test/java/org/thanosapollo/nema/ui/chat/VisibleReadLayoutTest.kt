package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ChatRoute
import org.thanosapollo.nema.chat.ChatRouteOccurrence
import org.thanosapollo.nema.chat.ThreadSummary
import org.thanosapollo.nema.chat.TimelineMessage
import org.thanosapollo.nema.chat.VisibleReadRequest
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VisibleReadLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val occurrence = ChatRouteOccurrence(ChatRoute("peer@example.org"), 1)
    private fun row(id: String) = TimelineMessage(id, "peer@example.org", id, false, null, null, null)

    @Test
    fun foregroundLayoutAdmitsOnlyBubblesAndScrollPreservesOffscreenHoles() {
        val resumed = mutableStateOf(false)
        val rows = mutableStateOf((0..80).map { row("row-$it") })
        val reads = mutableListOf<VisibleReadRequest>()
        compose.setContent {
            MaterialTheme {
                MessageTimeline(rows.value, accountId = "account", routeOccurrence = occurrence,
                    activityResumed = resumed.value, onMarkVisibleRead = { reads.add(it); true },
                    typingLabel = "Typing", modifier = Modifier.height(300.dp))
            }
        }
        compose.waitForIdle()
        assertTrue(reads.isEmpty())
        compose.runOnIdle { resumed.value = true }
        compose.waitForIdle()
        compose.waitUntil(5_000) { reads.isNotEmpty() }
        assertTrue(reads.first().messageIds.contains("row-80"))
        assertFalse(reads.any { "row-0" in it.messageIds })
        assertTrue(reads.all { it.accountId == "account" && it.occurrence == occurrence && it.messageIds.all { id -> id.startsWith("row-") } })
        compose.onNodeWithTag("message-timeline").performScrollToIndex(50)
        compose.waitForIdle()
        compose.waitUntil(5_000) { reads.any { "row-31" in it.messageIds } }
        compose.runOnIdle { resumed.value = false; reads.clear() }
        compose.onNodeWithTag("message-timeline").performScrollToIndex(75)
        compose.waitForIdle()
        assertTrue(reads.isEmpty())
        compose.runOnIdle { resumed.value = true }
        compose.waitForIdle()
        compose.waitUntil(5_000) { reads.isNotEmpty() }
        assertFalse(reads.any { "row-80" in it.messageIds })
    }

    @Test
    fun zeroIntersectionDoesNotAdmitButOnePixelDoes() {
        val resumed = mutableStateOf(false)
        val reads = mutableListOf<VisibleReadRequest>()
        compose.setContent {
            MaterialTheme {
                MessageTimeline((0..80).map { row("row-$it") }, accountId = "account",
                    routeOccurrence = occurrence, activityResumed = resumed.value,
                    onMarkVisibleRead = { reads.add(it); true }, modifier = Modifier.height(300.dp))
            }
        }
        val timeline = compose.onNodeWithTag("message-timeline")
        timeline.performScrollToIndex(40)
        compose.waitForIdle()
        val bubble = compose.onNodeWithTag("message-bubble-row-40")
        val height = bubble.fetchSemanticsNode().size.height.toFloat()
        timeline.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, height) }
        compose.waitForIdle()
        val edge = bubble.fetchSemanticsNode().positionInRoot.y
        val bottom = timeline.fetchSemanticsNode().boundsInRoot.bottom
        assertEquals("bubble touches viewport without overlap", bottom, edge, 0f)
        compose.runOnIdle { resumed.value = true }
        compose.waitForIdle()
        compose.waitUntil(5_000) { reads.isNotEmpty() }
        assertTrue(reads.none { "row-40" in it.messageIds })
        timeline.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -1f) }
        compose.waitForIdle()
        assertEquals(bottom - 1f, bubble.fetchSemanticsNode().positionInRoot.y, 0f)
        compose.waitUntil(5_000) { reads.any { "row-40" in it.messageIds } }
    }

    @Test
    fun infoExcludesArrivalAndHeldCallbackKeepsItsOriginalOccurrence() {
        val state = mutableStateOf(org.thanosapollo.nema.chat.DirectChatState(
            accountId = "account", selectedPeer = "peer@example.org", routeOccurrence = occurrence,
            contentStatus = org.thanosapollo.nema.chat.ChatContentStatus.Ready, messages = listOf(row("old"))))
        val entered = mutableListOf<VisibleReadRequest>()
        val completed = mutableListOf<VisibleReadRequest>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        compose.setContent {
            MaterialTheme {
                ConversationContent(state.value, "Connected", onSelectPeer = { true }, onCloseConversation = {},
                    onDraftChange = { kotlinx.coroutines.CompletableDeferred(true) },
                    onSend = { kotlinx.coroutines.CompletableDeferred(true) }, activityResumed = true,
                    onMarkVisibleRead = { request ->
                        entered.add(request)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                        completed.add(request)
                        true
                    })
            }
        }
        try {
            compose.waitForIdle()
            compose.waitUntil(5_000) { entered.isNotEmpty() }
            assertEquals(setOf("old"), entered.single().messageIds)
            compose.onNodeWithContentDescription("Open contact info").performClick()
            compose.onNodeWithTag("message-timeline").assertDoesNotExist()
            compose.runOnIdle { state.value = state.value.copy(messages = listOf(row("arrival"))) }
            compose.waitForIdle()
            assertEquals(1, entered.size)
            compose.onNodeWithContentDescription("Back to conversation").performClick()
            compose.waitForIdle()
            compose.waitUntil(5_000) { entered.any { "arrival" in it.messageIds } }
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { state.value = state.value.copy(
                routeOccurrence = occurrence.copy(generation = 2), messages = listOf(row("replacement"))) }
            // No frame/layout has run for the replacement occurrence.
            assertTrue(entered.none { it.occurrence.generation == 2 })
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.waitUntil(5_000) { entered.any { it.occurrence.generation == 2 } }
            assertTrue(entered.filter { it.occurrence.generation == 2 }.all { it.messageIds == setOf("replacement") })
            release.complete(Unit)
            compose.waitForIdle()
            compose.waitUntil(5_000) { completed.size == entered.size }
            assertEquals(occurrence, completed.first { "old" in it.messageIds }.occurrence)
        } finally {
            compose.mainClock.autoAdvance = true
            release.complete(Unit)
        }
    }

    @Test
    fun unchangedLatestAndReceiptFailuresCannotHideNewVisibleIds() {
        val rows = mutableStateOf(listOf(row("older"), row("latest").copy(markable = true, markerTargetId = "wire")))
        val receipts = mutableStateOf(false)
        val throwReceipt = mutableStateOf(false)
        val reads = mutableListOf<VisibleReadRequest>()
        var wireCalls = 0
        compose.setContent {
            MaterialTheme {
                MessageTimeline(rows.value, accountId = "account", routeOccurrence = occurrence,
                    activityResumed = true, readReceiptsEnabled = receipts.value,
                    onMarkVisibleRead = { reads.add(it); true }, onMessageDisplayed = {
                        wireCalls++
                        if (throwReceipt.value) error("controlled receipt failure")
                        false
                    }, modifier = Modifier.height(600.dp))
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { reads.any { "older" in it.messageIds } }
        assertEquals(0, wireCalls)
        compose.runOnIdle {
            receipts.value = true
            rows.value = listOf(row("replacement"), rows.value.last())
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { reads.any { "replacement" in it.messageIds } && wireCalls > 0 }
        compose.runOnIdle {
            throwReceipt.value = true
            rows.value = listOf(row("third").copy(
                markable = true, markerTargetId = "third-wire",
                replyToId = "quoted", replyReferenceIds = setOf("reference"),
                threadSummaries = listOf(ThreadSummary(ThreadRef(ThreadId.require("child")), 1, "hidden", "hidden preview")),
            ), rows.value.last())
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) { reads.any { "third" in it.messageIds } && wireCalls > 1 }
        assertTrue(reads.none { it.messageIds.any { id -> id in setOf("quoted", "reference", "hidden", "wire") } })
    }
}
