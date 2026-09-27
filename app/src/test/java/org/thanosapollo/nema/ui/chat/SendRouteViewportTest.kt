package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import org.thanosapollo.nema.createRobolectricComposeRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.chat.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SendRouteViewportTest {
    @get:Rule val compose = createRobolectricComposeRule()

    @Test fun readyChatOwnsTouchesBesideSendWithoutActivatingCoveredHome() {
        RoutePresentationFixture().use { fixture ->
            val presenter = fixture.presenter()
            runBlocking { withTimeout(5_000) { presenter.state.first { it.conversations.size == 31 } } }
            compose.setContent {
                MaterialTheme {
                    val state by presenter.state.collectAsState()
                    ConversationContent(state, "Connected", presenter::selectPeer, onCloseConversation = presenter::closeConversation,
                        onDraftChange = presenter::updateDraft, onSend = presenter::sendDraft)
                }
            }
            compose.onNodeWithTag("home-conversations").performScrollToIndex(15)
            val homeScroll = scroll("home-conversations")
            runBlocking { presenter.selectPeer(fixture.peer) }
            compose.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready && presenter.state.value.selectedPeer == fixture.peer }
            val occurrence = presenter.state.value.routeOccurrence
            val send = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            // The gap outside the button is still chat, not the live Home row beneath it.
            compose.onRoot().performTouchInput { click(Offset(root.right - 1f, send.center.y)) }
            compose.waitForIdle()
            assertEquals("Near-Send touch changed the selected peer", occurrence, presenter.state.value.routeOccurrence)
            // A real enabled Send remains actionable; consuming at the shell must not swallow children.
            compose.onNodeWithContentDescription("Send").performTouchInput { click() }
            compose.waitUntil { fixture.sendEntered.isCompleted }
            assertEquals(occurrence, presenter.state.value.routeOccurrence)
            compose.runOnIdle { presenter.closeConversation() }
            compose.waitUntil { presenter.state.value.selectedPeer == null }
            assertEquals(homeScroll, scroll("home-conversations"))
        }
    }

    @Test fun conversationInputPlanePreservesPhysicalTimelineDragAndComposerFocus() {
        var homeSelections = 0
        val initial = state(messages(0, 80))
        compose.setContent {
            MaterialTheme {
                ConversationContent(initial, "Connected", { homeSelections++; true },
                    onCloseConversation = {}, onDraftChange = { CompletableDeferred(true) },
                    onSend = { CompletableDeferred(true) })
            }
        }
        val before = scroll("message-timeline")
        compose.onNodeWithTag("message-timeline").performTouchInput { swipeDown() }
        compose.waitForIdle()
        val older = scroll("message-timeline")
        assertTrue("Input barrier cancelled the child timeline drag", older > before)
        compose.onNodeWithTag("message-timeline").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(scroll("message-timeline") < older)
        compose.onNodeWithTag("message-composer").performTouchInput { click() }
        compose.onNodeWithTag("message-composer").assertIsFocused()
        assertEquals(0, homeSelections)
    }

    @Test fun holdingSendNeverSelectsAnAlternateDestination() {
        var ordinary = 0
        compose.setContent {
            MaterialTheme {
                ConversationContent(state(messages(0, 10)).copy(draft = "held send"), "Connected", { true },
                    onCloseConversation = {}, onDraftChange = { CompletableDeferred(true) },
                    onSend = { ordinary++; CompletableDeferred(false) })
            }
        }
        compose.onNodeWithContentDescription("Send").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send").assert(
            SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick))
        assertEquals(1, ordinary)
    }

    @Test fun latestViewportFollowsOwnMessagesPublishedWhileHomeIsOpen() = reopen(history = false)
    @Test fun historyViewportPreservesExactMessageWhenOwnMessagesArrive() = reopen(history = true)

    private fun reopen(history: Boolean) {
        val initial = state(messages(0, 60))
        val shown = mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                ConversationContent(shown.value, "Connected", { true }, onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) }, onSend = { CompletableDeferred(true) })
            }
        }
        if (history) compose.onNodeWithTag("message-timeline").performScrollToIndex(35)
        compose.waitForIdle()
        val before = if (history) compose.onNodeWithTag("message-bubble-message-24").fetchSemanticsNode().boundsInRoot else null
        compose.runOnIdle { shown.value = initial.copy(selectedPeer = null, routeOccurrence = ChatRouteOccurrence(null, 2)) }
        compose.waitForIdle()
        compose.runOnIdle { shown.value = state(messages(0, 90), generation = 3) }
        compose.waitForIdle()
        if (history) {
            assertEquals(before, compose.onNodeWithTag("message-bubble-message-24").fetchSemanticsNode().boundsInRoot)
        } else assertEquals(0f, scroll("message-timeline"), 0.01f)
    }

    @Test fun lateSendCompletionDoesNotScrollAReopenedOccurrence() {
        val send = CompletableDeferred<Boolean>()
        val initial = state(messages(0, 80)).copy(draft = "outbound")
        val shown = mutableStateOf(initial)
        compose.setContent {
            MaterialTheme {
                ConversationContent(shown.value, "Connected", { true }, onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) }, onSend = { send })
            }
        }
        compose.onNodeWithContentDescription("Send").performClick()
        compose.runOnIdle { shown.value = initial.copy(selectedPeer = null, routeOccurrence = ChatRouteOccurrence(null, 2)) }
        compose.waitForIdle()
        compose.runOnIdle { shown.value = initial.copy(routeOccurrence = ChatRouteOccurrence(ChatRoute("peer@example.org"), 3)) }
        compose.onNodeWithTag("message-timeline").performScrollToIndex(40)
        compose.waitForIdle()
        val before = scroll("message-timeline")
        compose.runOnIdle { send.complete(true) }
        compose.waitForIdle()
        assertEquals("Old occurrence's send stole the successor's viewport", before, scroll("message-timeline"), 0.01f)
        compose.onNodeWithTag("message-composer").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun durableRoomSendSettlesAcrossHeldRouteAndDraftPersistenceWithoutStealingReopenedHistory() {
        RoutePresentationFixture().use { fixture ->
            val store = org.thanosapollo.nema.storage.MessageStore(fixture.database)
            runBlocking {
                fixture.repository.markRoom(fixture.account, fixture.peer)
                repeat(80) { index ->
                    store.composeDirectDraft(fixture.account, "old-$index", "old-$index", "origin-$index",
                        fixture.peer, "owner@example.org", "history-$index",
                        messageKind = org.thanosapollo.nema.thread.MessageKind.GROUPCHAT)
                }
                fixture.repository.saveDraft(DirectConversationKey(fixture.account, fixture.peer), "queued room body")
            }
            val stored = CompletableDeferred<Unit>()
            val completion = CompletableDeferred<Unit>()
            val presenter = DirectChatPresenter(
                org.thanosapollo.nema.account.AccountConfiguration.create(
                    org.thanosapollo.nema.xmpp.transport.AccountId.require(fixture.account),
                    "owner@example.org", "owner", null, "example.org", null),
                fixture.repository, fixture.scope,
                enqueue = { _, snapshot ->
                    assertTrue(snapshot.groupChat)
                    val queued = store.composeDirectDraft(snapshot.key.accountId, "queued", "queued", "queued-origin",
                        snapshot.key.canonicalBarePeer, "owner@example.org", snapshot.body,
                        messageKind = org.thanosapollo.nema.thread.MessageKind.GROUPCHAT)
                    stored.complete(Unit)
                    completion.await()
                    queued != null
                },
            ).also(fixture.presenters::add)
            compose.setContent {
                MaterialTheme {
                    val current by presenter.state.collectAsState()
                    ConversationContent(current, "Connected", presenter::selectPeer,
                        onCloseConversation = presenter::closeConversation, onDraftChange = presenter::updateDraft,
                        onSend = presenter::sendDraft, onAcknowledgeCompletedSends = presenter::acknowledgeCompletedSends)
                }
            }
            runBlocking { presenter.selectPeer(fixture.peer) }
            compose.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready && presenter.state.value.messages.any { it.id == "old-79" } }
            compose.onNodeWithTag("message-timeline").performScrollToIndex(40)
            compose.onNodeWithContentDescription("Send").performClick()
            runBlocking { withTimeout(5_000) { stored.await() } }
            compose.waitUntil { presenter.state.value.messages.any { it.id == "queued" } }
            compose.waitForIdle()
            val anchor = compose.onNodeWithTag("message-bubble-old-39").fetchSemanticsNode().boundsInRoot
            val entered = fixture.gate.hold()
            try {
                compose.runOnIdle { presenter.closeConversation() }
                compose.waitUntil { presenter.state.value.selectedPeer == null }
                runBlocking { presenter.selectPeer(fixture.other); presenter.selectPeer(fixture.peer) }
                runBlocking { withTimeout(5_000) { entered.await() } }
                compose.waitUntil { presenter.state.value.selectedPeer == fixture.peer }
                val owner = presenter.state.value.routeOccurrence
                compose.onNodeWithTag("message-composer").assertDoesNotExist()
                completion.complete(Unit)
                compose.waitUntil { presenter.state.value.completedSendSnapshots.isNotEmpty() }
                assertEquals(owner, presenter.state.value.routeOccurrence)
                fixture.gate.release()
                compose.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready }
                compose.waitForIdle()
                assertEquals(owner, presenter.state.value.routeOccurrence)
                assertEquals(anchor, compose.onNodeWithTag("message-bubble-old-39").fetchSemanticsNode().boundsInRoot)
                compose.onNodeWithTag("message-composer").assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                runBlocking {
                    assertNotNull(store.outbox(fixture.account, "queued"))
                    assertEquals(ChatRoute(fixture.peer), withTimeout(5_000) {
                        fixture.repository.observeRoute(fixture.account).first { it == ChatRoute(fixture.peer) }
                    })
                }
            } finally {
                completion.complete(Unit)
                fixture.gate.release()
            }
        }
    }

    @Test fun olderHistoryControlRetainsMessagePixelAnchorAndExposesRetryAndExhaustion() {
        val initial = state(messages(25, 75))
        val shown = mutableStateOf(initial)
        val history = mutableStateOf(OlderHistoryState(initial.routeOccurrence))
        val release = CompletableDeferred<Unit>()
        var calls = 0
        compose.setContent {
            MaterialTheme {
                ConversationContent(shown.value, "Connected", { true }, onCloseConversation = {},
                    onDraftChange = { CompletableDeferred(true) }, onSend = { CompletableDeferred(true) },
                    olderHistory = history.value, onLoadOlder = { origin ->
                        assertEquals(initial.routeOccurrence, origin)
                        calls++
                        history.value = OlderHistoryState(origin, OlderHistoryStatus.Pending)
                        if (calls == 1) {
                            history.value = OlderHistoryState(origin, OlderHistoryStatus.Error)
                        } else {
                            release.await()
                            shown.value = initial.copy(messages = messages(0, 75))
                            history.value = OlderHistoryState(origin, OlderHistoryStatus.Exhausted)
                        }
                    })
            }
        }
        compose.onNodeWithTag("message-timeline").performScrollToIndex(50)
        compose.onNodeWithTag("load-older-history").performClick()
        compose.onNodeWithText("Could not load older messages · Retry").assertExists()
        compose.onNodeWithTag("load-older-history").performClick()
        compose.onNodeWithText("Loading older messages…").assertExists()
        compose.onNodeWithTag("load-older-history").assertIsNotEnabled()
        val before = compose.onNodeWithTag("message-bubble-message-25").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { release.complete(Unit) }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("message-bubble-message-25").fetchSemanticsNode().boundsInRoot)
        assertEquals(2, calls)
        compose.onNodeWithTag("message-timeline").performScrollToIndex(75)
        compose.onNodeWithText("Beginning of archive").assertExists()
        compose.onNodeWithTag("load-older-history").assertIsNotEnabled()
    }

    @Test fun directOlderControlInvokesPresenterWithPersonalScope() = olderControlPresenter(false)
    @Test fun roomOlderControlInvokesPresenterWithRoomScope() = olderControlPresenter(true)

    private fun olderControlPresenter(room: Boolean) {
        RoutePresentationFixture().use { fixture ->
            if (room) runBlocking { fixture.repository.markRoom(fixture.account, fixture.peer) }
            val entered = CompletableDeferred<OlderHistoryScope>()
            val release = CompletableDeferred<Unit>()
            val presenter = DirectChatPresenter(
                org.thanosapollo.nema.account.AccountConfiguration.create(
                    org.thanosapollo.nema.xmpp.transport.AccountId.require(fixture.account),
                    "${fixture.account}@example.org", fixture.account, null, "example.org", null),
                fixture.repository, fixture.scope, { _, _ -> true },
                loadOlder = { account, target, current ->
                    assertEquals(fixture.account, account.id.value)
                    assertTrue(current())
                    entered.complete(target)
                    release.await()
                    assertFalse(current())
                    OlderHistoryStatus.Exhausted
                },
            )
            fixture.presenters += presenter
            runBlocking { presenter.selectPeer(fixture.peer) }
            compose.setContent {
                val state by presenter.state.collectAsState()
                val older by presenter.olderHistoryState.collectAsState()
                MaterialTheme {
                    ConversationContent(state, "Connected", presenter::selectPeer,
                        onCloseConversation = presenter::closeConversation,
                        onDraftChange = presenter::updateDraft, onSend = presenter::sendDraft,
                        olderHistory = older, onLoadOlder = presenter::loadOlderHistory)
                }
            }
            compose.waitUntil { presenter.state.value.contentStatus == ChatContentStatus.Ready && presenter.state.value.selectedPeerGroupChat == room }
            compose.onNodeWithTag("load-older-history").performClick()
            assertEquals(if (room) OlderHistoryScope.Room(fixture.peer) else OlderHistoryScope.Personal, runBlocking { entered.await() })
            compose.onNodeWithTag("load-older-history").assertIsNotEnabled()
            compose.runOnIdle { presenter.closeConversation() }
            runBlocking { presenter.selectPeer(fixture.other) }
            compose.waitUntil { presenter.state.value.selectedPeer == fixture.other && presenter.state.value.contentStatus == ChatContentStatus.Ready }
            compose.runOnIdle { release.complete(Unit) }
            compose.onNodeWithText("Load older messages").assertExists()
            compose.onNodeWithTag("load-older-history").assertIsEnabled()
        }
    }

    private fun scroll(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun state(messages: List<TimelineMessage>, generation: Int = 1) = DirectChatState(
        accountId = "account", selectedPeer = "peer@example.org", messages = messages, selectedPeerGroupChat = true,
        routeOccurrence = ChatRouteOccurrence(ChatRoute("peer@example.org"), generation),
    )

    private fun messages(start: Int, end: Int) = (start until end).map {
        TimelineMessage(id = "message-$it", senderJid = "account@example.org", body = "message-$it",
            outgoing = true, delivery = DeliveryPresentation.SENT, retryUncertainKey = null, thread = null)
    }
}
