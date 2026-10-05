package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.*
import org.thanosapollo.nema.createRobolectricComposeRule
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.thread.*
import org.thanosapollo.nema.ui.HomeContent
import org.thanosapollo.nema.xmpp.omemo.*
import org.thanosapollo.nema.xmpp.smack.*
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ProtectedPresentationTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun protectedCardsShowDirectionAndTimeWithoutDeliveryOrContentAffordances() {
        val sentAt = 1_700_000_000_000L
        val incoming = TimelineMessage("protected-in", ProtectedFixtures.peer, "fallback in", false,
            delivery = null, retryUncertainKey = null, thread = null, sentAtEpochMs = sentAt,
            attachmentUrl = "https://example.org/in.png", attachmentMime = "image/png",
            protectedState = "UNSUPPORTED_PAYLOAD")
        val outgoing = TimelineMessage("protected-out", ProtectedFixtures.self, "fallback out", true,
            delivery = DeliveryPresentation.DELIVERED, retryUncertainKey = null, thread = null,
            sentAtEpochMs = sentAt + 60_000, protectedState = "UNSUPPORTED_PAYLOAD")
        var effects = 0
        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(incoming, outgoing), readReceiptsEnabled = true, activityResumed = true,
                    isAttachmentCached = { effects++; true }, onLoadInlineImage = { effects++; null },
                    onUseAttachment = { _, _, _ -> effects++; true }, onEdit = { effects++ },
                    onReact = { _, _ -> effects++; true })
            }
        }
        val received = compose.onNodeWithTag("protected-message-protected-in", useUnmergedTree = true)
        val sent = compose.onNodeWithTag("protected-message-protected-out", useUnmergedTree = true)
        compose.onNodeWithText("Received · ${formatMessageTime(sentAt)}", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Sent · ${formatMessageTime(sentAt + 60_000)}", useUnmergedTree = true).assertIsDisplayed()
        received.assertHasNoClickAction()
        sent.assertHasNoClickAction()
        // Direction is also spatial: received cards start at the leading edge, sent cards end at the trailing edge.
        val root = compose.onNodeWithTag("message-timeline").getUnclippedBoundsInRoot()
        val inBounds = received.getUnclippedBoundsInRoot()
        val outBounds = sent.getUnclippedBoundsInRoot()
        assertEquals(root.left.value, inBounds.left.value, 0.5f)
        assertTrue(inBounds.right < root.right)
        assertEquals(root.right.value, outBounds.right.value, 0.5f)
        assertTrue(outBounds.left > root.left)
        // No receipt tick, delivery state, download or edit affordance on protected content.
        compose.onAllNodesWithTag("message-status", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Delivered", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Download", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("Unauthenticated fallback").assertCountEquals(2)
        compose.runOnIdle { assertEquals(0, effects) }
    }

    @Test fun reopenedRepositoryDrivesInertCardsHomeThreadsRepliesAndDurableActionRefusal() = runBlocking {
        SmackAndroid.initialize(context)
        installNemaOmemoProviders()
        val name = "protected-presentation-${UUID.randomUUID()}.db"
        var db = NemaDatabase.create(context, name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var presenter: DirectChatPresenter? = null
        try {
            db.accountDao().saveBound(AccountEntity("account", ProtectedFixtures.self, "account", null, "example.org", null, null))
            val store = MessageStore(db, clock = { 1000L })
            val protected = ProtectedFixtures.envelope(OmemoProtocol.LEGACY, id = "protected-wire")
                .toIncomingMessage("protected").copy(body = "https://example.org/untrusted-main")
            store.ingest(protected)
            val root = protected.copy(localMessageId = "root", protection = null, body = "ordinary root",
                threadId = "root-thread", aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, ProtectedFixtures.peer, "root-wire")))
            store.ingest(root)
            store.ingest(root.copy(localMessageId = "child", body = "ordinary child", aliases = emptyList(),
                threadId = "child-thread", parentThreadId = "root-thread", replyToId = "root-wire", replyToJid = ProtectedFixtures.peer))
            store.ingest(protected.copy(localMessageId = "reply", protection = null, body = "ordinary reply", aliases = emptyList(),
                replyToId = "protected-wire", replyToJid = ProtectedFixtures.peer, replyFallbackBody = "> untrusted quotation"))
            store.ingest(protected.copy(localMessageId = "protected-child", aliases = emptyList(),
                body = "https://example.org/untrusted-child", threadId = "child-thread", parentThreadId = "root-thread"))
            // Simulate stale cached/uncached attachment metadata; protected projection must not use either.
            for (id in listOf("protected", "protected-child")) {
                val row = db.messageDao().message("account", id)!!
                db.messageDao().updateMessage(row.copy(attachmentUrl = "https://example.org/$id.png", attachmentMime = "image/png"))
            }
            db.close(); db = NemaDatabase.create(context, name)
            val repository = ChatRepository(db)
            val key = DirectConversationKey("account", ProtectedFixtures.peer)
            val view = withTimeout(5_000) { repository.observeConversationTimeline(key).first() }
            val target = view.messages.single { it.id == "protected" }
            val childView = withTimeout(5_000) { repository.observeConversationTimeline(key.copy(
                thread = ThreadRef(ThreadId.require("child-thread"), ThreadId.require("root-thread")))).first() }
            val childTarget = childView.messages.single { it.id == "protected-child" }
            assertNull(childTarget.attachmentUrl)
            assertEquals("https://example.org/untrusted-main", target.body)
            assertNull(target.attachmentUrl)
            assertNull(view.messages.single { it.id == "reply" }.reply)
            assertEquals(ProtectedState.UNSUPPORTED_PAYLOAD.status,
                view.messages.single { it.id == "root" }.threadSummaries.single().latestPreview)
            assertTrue(view.recentThreads.none { it.title.contains("untrusted") })
            val cachedRows = db.messageDao().cachedConversationSummaries("account")
            val liveRows = withTimeout(5_000) { db.messageDao().observeConversationSummaries("account").first() }
            assertEquals("UNSUPPORTED_PAYLOAD", cachedRows.single().protectedState)
            assertEquals(cachedRows.single().protectedState, liveRows.single().protectedState)
            val home = repository.cachedConversations("account")
            assertEquals(ProtectedState.UNSUPPORTED_PAYLOAD.status, home.single().preview)
            assertNull(home.single().previewSender)
            assertNotEquals(home.single(), home.single().copy(protectedState = "REJECTED"))
            var effects = 0
            var showHome by mutableStateOf(false)
            compose.setContent {
                MaterialTheme {
                    if (showHome) HomeContent(home, true, "Connected", "Account", { true })
                    else MessageTimeline(listOf(target, childTarget, view.messages.single { it.id == "reply" }),
                        readReceiptsEnabled = true, activityResumed = true,
                        isAttachmentCached = { effects++; it.endsWith("protected.png") },
                        onLoadInlineImage = { effects++; null }, onUseAttachment = { _, _, _ -> effects++; true },
                        onReply = { effects++ }, onQuote = { effects++ }, onEdit = { effects++ },
                        onReact = { _, _ -> effects++; true }, onMessageDisplayed = { effects++; true })
                }
            }
            compose.onNodeWithText(target.body).assertIsDisplayed().assertHasNoClickAction()
            compose.onNodeWithTag("protected-message-protected").assertIsDisplayed()
            compose.onNodeWithTag("message-bubble-reply").assertHasClickAction()
            compose.onAllNodesWithText("Unauthenticated fallback").assertCountEquals(2)
            compose.runOnIdle { assertEquals(0, effects); showHome = true }
            compose.onNodeWithText(ProtectedState.UNSUPPORTED_PAYLOAD.status).assertIsDisplayed()
            compose.onNodeWithText("https://example.org/untrusted-child").assertDoesNotExist()
            val route = ChatRoute(ProtectedFixtures.peer)
            repository.saveRoute("account", route)
            val destination = route.copy(thread = ThreadRef(ThreadId.require("new-thread")))
            val reply = DraftReply("protected-wire", ProtectedFixtures.peer, target.body, "Peer")
            assertFalse(repository.openThreadReply("account", route, destination, target, reply))
            assertFalse(repository.openThreadReply("account", route, destination, target.copy(protectedState = "NONE"), reply))
            assertEquals(route, repository.observeRoute("account").first())
            assertEquals("", repository.observeDraft(key.copy(thread = destination.thread)).first())
            assertNull(MessageStore(db).reactionWireTarget("account", ProtectedFixtures.peer, target.id))
            val account = AccountConfiguration.create(AccountId.require("account"), ProtectedFixtures.self,
                "account", null, "example.org", null)
            presenter = DirectChatPresenter(account, repository, scope, { _, _ -> true })
            val active = presenter!!
            active.selectPeer(ProtectedFixtures.peer)
            val state = withTimeout(5_000) { active.state.first { it.messages.any { message -> message.id == target.id } } }
            assertFalse(active.startThreadFrom(state.messages.single { it.id == target.id }))
            assertNull(target.correctionTargetOrNull(ConversationVenue.Direct))
            assertFalse(canReact(ConversationVenue.Direct, target))
            assertTrue(active.startThreadFrom(state.messages.single { it.id == "root" }))
        } finally {
            presenter?.close(); scope.cancel(); db.close(); context.deleteDatabase(name)
        }
    }
}
