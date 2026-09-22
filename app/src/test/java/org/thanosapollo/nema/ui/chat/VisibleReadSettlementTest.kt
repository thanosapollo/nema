package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.chat.RoutePresentationFixture
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.ui.HomeContent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VisibleReadSettlementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directReadSurvivesNavigation() = settlement(room = false, thread = false)
    @Test fun roomReadSurvivesPause() = settlement(room = true, thread = false)
    @Test fun childReadSurvivesNavigation() = settlement(room = false, thread = true)

    private fun settlement(room: Boolean, thread: Boolean) {
        RoutePresentationFixture().use { f ->
            val p = f.presenter()
            val selectedThread = ThreadRef(ThreadId.require("child"), ThreadId.require("parent"))
            runBlocking {
                if (room) f.repository.markRoom(f.account, f.peer)
                if (thread) {
                    MessageStore(f.database).ingest(IncomingMessage(
                        accountId = f.account, localMessageId = "child-message", peerJid = f.peer,
                        senderJid = f.peer, direction = MessageDirection.INBOUND, messageKind = MessageKind.CHAT,
                        threadId = "child", parentThreadId = "parent", body = "child body",
                        archiveOrdinal = null, aliases = emptyList(),
                    ))
                }
                if (!thread) repeat(12) { index ->
                    MessageStore(f.database).ingest(IncomingMessage(
                        accountId = f.account, localMessageId = "older-$index", peerJid = f.peer,
                        senderJid = f.peer, direction = MessageDirection.INBOUND,
                        messageKind = if (room) MessageKind.GROUPCHAT else MessageKind.CHAT,
                        threadId = null, parentThreadId = null, body = "older body $index",
                        archiveOrdinal = null, aliases = emptyList(), sentAtEpochMs = index + 1L,
                        sentTimeSource = org.thanosapollo.nema.xmpp.transport.MessageTimeSource.MAM,
                    ))
                }
                p.selectPeer(f.peer)
                if (thread) p.continueThread(selectedThread)
                withTimeout(5_000) { p.state.first {
                    it.contentStatus == ChatContentStatus.Ready && it.messages.size == (if (thread) 1 else 13) &&
                        it.selectedThread == (if (thread) selectedThread else null)
                } }
            }
            val readCount = if (thread) 1 else 13
            val target = if (thread) "child-message" else "message-0"
            val before = p.state.value.conversations.single { it.peerJid == f.peer }
            val visible = mutableStateOf(true)
            val resumed = mutableStateOf(false)
            val entered = CompletableDeferred<Job>()
            val writerEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val owner = p.javaClass.getDeclaredField("presenterJob").apply { isAccessible = true }.get(p) as Job
            val existingChildren = owner.children.toSet()
            compose.setContent {
                val state by p.state.collectAsState()
                MaterialTheme {
                    Box {
                        HomeContent(state.conversations.filter { it.peerJid == f.peer }, state.conversationsReady,
                            "Connected", "Self", onSelectPeer = { true })
                        if (visible.value) {
                            MessageTimeline(state.messages, accountId = state.accountId,
                                routeOccurrence = state.routeOccurrence, activityResumed = resumed.value,
                                onMarkVisibleRead = { request ->
                                    entered.complete(currentCoroutineContext()[Job]!!)
                                    p.markVisibleConversationRead(request)
                                }, modifier = Modifier.height(300.dp))
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(before.unreadCount.toString()).assertExists()
            // Hold the real Room writer, not a fake read callback or NonCancellable wrapper.
            val writer = f.scope.launch {
                f.database.withTransaction { writerEntered.complete(Unit); release.await() }
            }
            try {
                runBlocking { withTimeout(5_000) { writerEntered.await() } }
                compose.runOnIdle { resumed.value = true }
                compose.waitForIdle()
                compose.waitUntil(5_000) { entered.isCompleted }
                // Callback entry is before off-Main preparation, not an admission fence.
                // Wait for the actual presenter-owned async write while Room's writer is held.
                compose.waitUntil(5_000) {
                    owner.children.any { it is kotlinx.coroutines.Deferred<*> && it !in existingChildren && it.isActive }
                }
                val caller = runBlocking { entered.await() }
                assertTrue(caller.isActive)
                compose.runOnIdle {
                    if (room) resumed.value = false else { p.closeConversation(); visible.value = false }
                }
                compose.waitForIdle()
                assertTrue("Compose cancelled the actual read caller", caller.isCancelled)
                release.complete(Unit)
                runBlocking { withTimeout(5_000) { writer.join() } }
                // Wait for invalidation, then assert durable state separately from Home projection.
                try {
                    compose.waitUntil(5_000) {
                        p.state.value.conversations.single { it.peerJid == f.peer }.unreadCount == before.unreadCount - readCount
                    }
                } catch (_: androidx.compose.ui.test.ComposeTimeoutException) { }
                assertEquals("admitted visible ID must survive effect cancellation", true,
                    runBlocking { f.database.messageDao().message(f.account, target) }?.locallyRead)
                if (!thread) repeat(12) { index ->
                    assertEquals("offscreen history must read through the visible boundary", true,
                        runBlocking { f.database.messageDao().message(f.account, "older-$index") }?.locallyRead)
                }
                val after = p.state.value.conversations.single { it.peerJid == f.peer }
                assertEquals(before.unreadCount - readCount, after.unreadCount)
                assertEquals(before.preview, after.preview)
                assertEquals(before.localSequence, after.localSequence)
                if (thread) assertEquals(false,
                    runBlocking { f.database.messageDao().message(f.account, "message-0") }?.locallyRead)
                compose.waitForIdle()
                if (after.unreadCount == 0) compose.onNodeWithText(before.unreadCount.toString()).assertDoesNotExist()
                else compose.onNodeWithText(after.unreadCount.toString()).assertExists()
                assertEquals(false, runBlocking { f.database.messageDao().message(f.account, "message-1") }?.locallyRead)
            } finally {
                release.complete(Unit)
                compose.runOnIdle { visible.value = false }
                runBlocking { withTimeout(5_000) { writer.join() } }
            }
        }
    }
}
