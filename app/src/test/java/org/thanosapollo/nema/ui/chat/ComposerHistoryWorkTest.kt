package org.thanosapollo.nema.ui.chat

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.*
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.AccountId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ComposerHistoryWorkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directTypingDoesNotRebuildTimelineKeys() = typing(512, false)
    @Test fun largerDirectHistoryDoesNotIncreaseTypingWork() = typing(2_048, false)
    @Test fun roomTypingDoesNotRebuildTimelineKeys() = typing(512, true)
    @Test fun largerRoomHistoryDoesNotIncreaseTypingWork() = typing(2_048, true)

    private fun typing(historySize: Int, room: Boolean) {
        val sql = CopyOnWriteArrayList<String>()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NemaDatabase::class.java)
            .setQueryCallback({ query, _ -> sql.add(query) }, java.util.concurrent.Executor { it.run() })
            .build()
        val repository = ChatRepository(db)
        val account = "typing-account"
        val peer = if (room) "room@conference.example.org" else "peer@example.org"
        val scope = CoroutineScope(SupervisorJob() + Handler(Looper.getMainLooper()).asCoroutineDispatcher("typing-main"))
        val reads = AtomicInteger()
        val keyReads = AtomicInteger()
        val saves = AtomicInteger()
        val notifications = CopyOnWriteArrayList<Pair<String, Boolean>>()
        var counting = false
        var presenter: DirectChatPresenter? = null
        try {
            runBlocking {
                db.accountDao().upsert(AccountEntity(account, "self@example.org", "self", null, "example.org", null, null))
                db.withTransaction {
                    repeat(historySize) { index ->
                        MessageStore(db).ingest(IncomingMessage(
                            accountId = account, localMessageId = "row-$index", peerJid = peer,
                            senderJid = if (room) "$peer/Peer" else peer,
                            direction = MessageDirection.INBOUND,
                            messageKind = if (room) MessageKind.GROUPCHAT else MessageKind.CHAT,
                            threadId = null, parentThreadId = null, body = "body $index", archiveOrdinal = null, aliases = emptyList(),
                        ))
                    }
                }
                if (room) repository.markRoom(account, peer)
            }
            val owner = DirectChatPresenter(
                AccountConfiguration.create(AccountId.require(account), "self@example.org", "self", null, "example.org", null),
                repository, scope, enqueue = { _, _ -> error("network forbidden") },
                notifyComposer = { jid, composing -> notifications.add(jid to composing) },
            )
            presenter = owner
            compose.runOnUiThread { owner.selectNotificationDestination(peer, null) }
            compose.setContent {
                val state by owner.state.collectAsState()
                // Capture a plain snapshot: a delegated getter inside this list would
                // introduce false dependencies into production derivedStateOf readers.
                val messages = state.messages
                val counted = remember(messages) {
                    object : AbstractList<TimelineMessage>() {
                        override val size get() = messages.size
                        override fun get(index: Int): TimelineMessage {
                            if (counting && Looper.myLooper() == Looper.getMainLooper()) {
                                reads.incrementAndGet()
                                if (Thread.currentThread().stackTrace.any { it.className.endsWith("NearestRangeKeyIndexMap") }) {
                                    keyReads.incrementAndGet()
                                }
                            }
                            return messages[index]
                        }
                    }
                }
                MaterialTheme {
                    ConversationContent(state.copy(messages = counted), "Offline", onSelectPeer = owner::selectPeer,
                        onCloseConversation = owner::closeConversation,
                        onDraftChange = { snapshot -> saves.incrementAndGet(); owner.updateDraft(snapshot) },
                        onSend = owner::sendDraft)
                }
            }
            compose.waitUntil(15_000) {
                ShadowLooper.idleMainLooper()
                owner.state.value.messages.size == historySize && owner.state.value.selectedPeerGroupChat == room
            }
            compose.waitForIdle()
            val original = owner.state.value
            sql.clear()
            counting = true
            listOf("a", "b", "c").forEachIndexed { index, char ->
                compose.onNodeWithTag("message-composer").performTextInput(char)
                compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); owner.state.value.draft.length == index + 1 }
                compose.waitForIdle()
            }
            counting = false
            val draftInserts = sql.count { it.startsWith("INSERT INTO `message_drafts`") }
            println("TYPING room=$room history=$historySize mainListReads=${reads.get()} keyIndexReads=${keyReads.get()} saves=${saves.get()} draftInserts=$draftInserts")
            assertEquals(original.routeOccurrence, owner.state.value.routeOccurrence)
            assertSame("draft writes retain the history projection", original.messages, owner.state.value.messages)
            assertEquals("abc", runBlocking { repository.observeDraft(DirectConversationKey(account, peer)).first() })
            compose.onNodeWithTag("message-composer").assertTextEquals("abc")
            assertEquals(3, saves.get())
            assertEquals("all edits reached Room", 3, draftInserts)
            assertEquals(!room, notifications.any { it == peer to true })
            assertEquals("unchanged history must not rebuild lazy keys", 0, keyReads.get())
            // Allow bounded visible-row/endpoint work, not a nearest-range rebuild per edit.
            assertTrue("mainListReads=${reads.get()} history=$historySize", reads.get() < 128)

            // Callback stabilization must not freeze the composer captured before typing.
            compose.onNodeWithTag("message-bubble-row-${historySize - 1}").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("Quote").performClick()
            val quoted = "> ${if (room) "Peer" else "peer"} wrote:\n> body ${historySize - 1}\n\nabc"
            compose.onNodeWithTag("message-composer").assertTextEquals(quoted)
            compose.waitUntil(10_000) { ShadowLooper.idleMainLooper(); owner.state.value.draft == quoted }
            assertEquals(original.routeOccurrence, owner.state.value.routeOccurrence)
        } finally {
            compose.runOnUiThread { presenter?.close(); scope.cancel() }
            compose.waitUntil(5_000) { ShadowLooper.idleMainLooper(); scope.coroutineContext[Job]!!.isCompleted }
            db.close()
        }
    }
}
