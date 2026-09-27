package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import org.thanosapollo.nema.createRobolectricComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.chat.DirectChatPresenter
import org.thanosapollo.nema.chat.RoutePresentationFixture
import org.thanosapollo.nema.chat.TimelineMessage
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirstReadyViewportTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test
    fun heldProjectionNeverRestoresFallbackBeforeOldAnchorArrives() {
        RoutePresentationFixture().use { fixture ->
            runBlocking {
                repeat(100) { index ->
                    MessageStore(fixture.database).ingest(IncomingMessage(
                        accountId = fixture.account, localMessageId = "old-$index", peerJid = fixture.peer,
                        senderJid = fixture.peer, direction = MessageDirection.INBOUND, messageKind = MessageKind.CHAT,
                        threadId = null, parentThreadId = null, body = "body $index", archiveOrdinal = null, aliases = emptyList(),
                    ))
                }
            }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val presenter = DirectChatPresenter(
                AccountConfiguration.create(AccountId.require(fixture.account), "${fixture.account}@example.org", fixture.account, null, "example.org", null),
                fixture.repository, fixture.scope, enqueue = { _, _ -> true },
                observeRoom = { flow { entered.complete(Unit); release.await(); emit(null) } },
            ).also(fixture.presenters::add)
            val anchor = TimelineViewportAnchor("old-5", 17, 0)
            val writes = mutableListOf<TimelineViewportAnchor>()
            val reads = mutableListOf<Set<String>>()
            compose.setContent {
                val state by presenter.state.collectAsState()
                MaterialTheme {
                    if (state.selectedPeer == fixture.peer && state.contentStatus == ChatContentStatus.Ready) {
                        MessageTimeline(state.messages, initialViewport = anchor, onViewportChanged = writes::add,
                            accountId = state.accountId, routeOccurrence = state.routeOccurrence, activityResumed = true,
                            onMarkVisibleRead = { reads.add(it.messageIds); true }, modifier = Modifier.height(300.dp))
                    }
                }
            }
            runBlocking { presenter.selectPeer(fixture.peer); withTimeout(5_000) { entered.await() } }
            compose.waitForIdle()
            assertEquals(ChatContentStatus.Loading, presenter.state.value.contentStatus)
            assertTrue(writes.isEmpty())
            assertTrue(reads.isEmpty())
            release.complete(Unit)
            compose.waitUntil(5_000) { writes.isNotEmpty() }
            assertEquals(anchor.messageId, writes.first().messageId)
            assertEquals(anchor.offset, writes.first().offset)
            compose.waitUntil(5_000) { reads.isNotEmpty() }
            assertTrue(reads.first().contains("old-5"))
            assertTrue(reads.none { "old-99" in it })
        }
    }

    @Test
    fun missingAnchorSettlesAtBoundedFallback() {
        val writes = mutableListOf<TimelineViewportAnchor>()
        val reads = mutableListOf<Set<String>>()
        compose.setContent {
            MaterialTheme {
                MessageTimeline(rows(100), initialViewport = TimelineViewportAnchor("deleted", 13, 40),
                    onViewportChanged = writes::add, activityResumed = true,
                    onMarkVisibleRead = { reads.add(it.messageIds); true }, modifier = Modifier.height(300.dp))
            }
        }
        compose.waitUntil(5_000) { writes.isNotEmpty() }
        assertEquals(TimelineViewportAnchor("row-59", 13, 40), writes.first())
        compose.waitUntil(5_000) { reads.isNotEmpty() }
        assertTrue(reads.first().contains("row-59"))
        assertTrue(reads.none { "row-99" in it || "deleted" in it })
    }

    @Test
    fun authoritativeEmptySettlesWithoutWritingOrRestoringStaleAnchorOnLaterRows() {
        val messages = mutableStateOf(emptyList<TimelineMessage>())
        val writes = mutableListOf<TimelineViewportAnchor>()
        val reads = mutableListOf<Set<String>>()
        compose.setContent {
            MaterialTheme {
                MessageTimeline(messages.value, initialViewport = TimelineViewportAnchor("row-5", 17, 50),
                    onViewportChanged = writes::add, activityResumed = true,
                    onMarkVisibleRead = { reads.add(it.messageIds); true }, modifier = Modifier.height(300.dp))
            }
        }
        compose.waitForIdle()
        assertTrue(writes.isEmpty())
        assertTrue(reads.isEmpty())
        compose.runOnIdle { messages.value = rows(100) }
        compose.waitForIdle()
        compose.waitUntil(5_000) { writes.isNotEmpty() }
        assertEquals(TimelineViewportAnchor("row-99", 0, 0), writes.first())
        compose.waitUntil(5_000) { reads.isNotEmpty() }
        assertTrue(reads.first().contains("row-99"))
        assertTrue(reads.none { "row-5" in it })
    }

    private fun rows(count: Int) = (0 until count).map {
        TimelineMessage(id = "row-$it", senderJid = "peer@example.org", body = "body $it",
            outgoing = false, delivery = null, retryUncertainKey = null, thread = null)
    }
}
