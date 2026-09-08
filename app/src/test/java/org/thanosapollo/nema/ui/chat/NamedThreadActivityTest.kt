package org.thanosapollo.nema.ui.chat

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.chat.ChatRepository
import org.thanosapollo.nema.chat.DirectChatPresenter
import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.VisibleReadRequest
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.IdentityAliasKind
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.SharedThreadStore
import org.thanosapollo.nema.storage.TrustedIdentityAlias
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.draftKey
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.threads.ThreadDirectoryItem
import org.thanosapollo.nema.xmpp.threads.ThreadDirectoryScope
import org.thanosapollo.nema.xmpp.threads.ThreadDirectorySnapshot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NamedThreadActivityTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "named-activity-${UUID.randomUUID()}.db"
    private lateinit var database: NemaDatabase
    private lateinit var repository: ChatRepository
    private lateinit var store: MessageStore
    private lateinit var presenter: DirectChatPresenter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() = runBlocking {
        openDatabase()
        for (account in listOf(ACCOUNT, OTHER_ACCOUNT)) database.accountDao().upsert(
            AccountEntity(account, "$account@example.org", account, null, "example.org", null, null),
        )
    }

    @After fun tearDown() {
        if (::presenter.isInitialized) presenter.close()
        scope.cancel()
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase() {
        database = NemaDatabase.create(context, databaseName)
        repository = ChatRepository(database)
        store = MessageStore(database)
    }

    private fun startPresenter() {
        presenter = DirectChatPresenter(
            AccountConfiguration.create(AccountId.require(ACCOUNT), "$ACCOUNT@example.org", ACCOUNT,
                null, "example.org", null),
            repository, scope, { _, _ -> false },
        )
    }

    private fun showConversation() {
        compose.setContent {
            val state by presenter.state.collectAsState()
            MaterialTheme {
                ConversationContent(
                    state = state, connectionStatus = "Offline", onSelectPeer = presenter::selectPeer,
                    onCloseConversation = presenter::closeConversation, onCloseThread = presenter::closeThread,
                    onDraftChange = { CompletableDeferred(true) }, onSend = { CompletableDeferred(false) },
                    onContinueThread = presenter::continueThread,
                    onSelectThreadDestination = presenter::selectThreadDestination,
                    onCreateNamedThread = presenter::createNamedThread,
                    onRenameNamedThread = presenter::renameNamedThread,
                )
            }
        }
    }

    private suspend fun awaitState(predicate: (DirectChatState) -> Boolean): DirectChatState =
        withTimeout(5_000) { presenter.state.first { it.contentStatus == ChatContentStatus.Ready && predicate(it) } }

    @Test fun namingParentKeepsEvictedExistingChildActionableAfterReopen() = childJourney(false)
    @Test fun namedParentKeepsChildArrivingLaterActionableAfterReopen() = childJourney(true)
    @Test fun sharedParentKeepsEvictedExistingChildActionableAfterReopen() = childJourney(false, shared = true)
    @Test fun sharedParentKeepsChildArrivingLaterActionableAfterReopen() = childJourney(true, shared = true)

    private fun childJourney(nameBeforeChild: Boolean, shared: Boolean = false) = runBlocking<Unit> {
        val parent = ThreadRef(ThreadId.require(UUID.randomUUID().toString()))
        val child = ThreadRef(ThreadId.require("child"), parent.id)
        suspend fun nameParent() {
            if (shared) publishShared(MessageKind.CHAT, parent to "Project")
            else assertTrue(repository.renameThread(ACCOUNT, PEER, MessageKind.CHAT, parent, "Project"))
        }
        store.ingest(incoming("parent-message", "Parent body", parent).copy(
            aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "parent-wire")),
        ))
        if (nameBeforeChild) nameParent()
        store.ingest(incoming("child-message", "Exact child body", child).copy(
            replyToId = "parent-wire", replyToJid = PEER,
        ))
        if (!nameBeforeChild) nameParent()
        repeat(11) { store.ingest(incoming("new-$it", "New session $it", ThreadRef(ThreadId.require("session-$it")))) }
        assertFalse(repository.observeRecentThreads(ACCOUNT, PEER).first().any { it.thread == child })
        // Reopen the real Room database: this entry point cannot depend on a remembered ID or recent-ten cache.
        database.close()
        openDatabase()
        val parentMessages = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, parent)).first()
        assertEquals(listOf("parent-message"), parentMessages.map { it.id })
        assertEquals(child, parentMessages.single().threadSummaries.single().thread)
        assertEquals("Exact child body", parentMessages.single().threadSummaries.single().latestPreview)
        assertFalse(repository.observeTimeline(ACCOUNT, PEER).first().any { it.id in setOf("parent-message", "child-message") })
        startPresenter()
        presenter.selectNotificationDestination(PEER, parent)
        awaitState { it.selectedThread == parent && it.messages.isNotEmpty() }
        showConversation()
        compose.onNodeWithTag("thread-summary-${child.draftKey()}").assertIsDisplayed().performClick()
        val opened = awaitState { it.selectedThread == child }
        assertEquals(listOf("parent-message", "child-message"), opened.messages.map { it.id })
        assertEquals(child, opened.messages.last().thread)
        compose.onNodeWithText("Exact child body", substring = false).assertIsDisplayed()
        compose.onNodeWithText("New child thread").assertDoesNotExist()
        presenter.closeConversation()
        awaitState { it.selectedPeer == null }
        presenter.selectNotificationDestination(PEER, parent)
        awaitState { it.selectedThread == parent && it.messages.isNotEmpty() }
        compose.onNodeWithTag("thread-summary-${child.draftKey()}").assertIsDisplayed().performClick()
        awaitState { it.selectedThread == child }
        compose.onNodeWithText("Exact child body", substring = false).assertIsDisplayed()
    }

    @Test fun directMainUnreadRemainsVisibleAndReadSeparatelyFromNamedSibling() = mainActivityJourney(false)
    @Test fun roomMainUnreadRemainsVisibleAndReadSeparatelyFromNamedSibling() = mainActivityJourney(true)
    @Test fun sharedDirectMainUnreadRemainsVisibleAndReadSeparately() = mainActivityJourney(false, shared = true)
    @Test fun sharedRoomMainUnreadRemainsVisibleAndReadSeparately() = mainActivityJourney(true, shared = true)
    @Test fun archivedDirectDestinationsKeepActivityOutsideMain() = mainActivityJourney(false, shared = true, archived = true)
    @Test fun archivedRoomDestinationsKeepActivityOutsideMain() = mainActivityJourney(true, shared = true, archived = true)

    private fun mainActivityJourney(room: Boolean, shared: Boolean = false, archived: Boolean = false) = runBlocking {
        val kind = if (room) MessageKind.GROUPCHAT else MessageKind.CHAT
        if (room) repository.markRoom(ACCOUNT, PEER)
        val project = if (shared) ThreadRef(ThreadId.require(UUID.randomUUID().toString()))
            else requireNotNull(repository.createNamedThread(ACCOUNT, PEER, kind, "Project"))
        val sibling = if (shared) ThreadRef(ThreadId.require(UUID.randomUUID().toString()))
            else requireNotNull(repository.createNamedThread(ACCOUNT, PEER, kind, "Sibling"))
        if (shared) publishShared(kind, project to "Project", sibling to "Sibling", archived = archived)
        store.ingest(incoming("project-body", "Project body", project, kind).copy(unreadEligible = false))
        store.ingest(incoming("sibling-body", "Sibling body", sibling, kind))
        // A live GROUPCHAT ingest promotes a peer to a room. Seed mixed historical kinds
        // before selecting the intended venue, rather than accidentally changing that venue.
        store.ingest(incoming("other-kind", "Other kind", kind = if (room) MessageKind.CHAT else MessageKind.GROUPCHAT))
        org.thanosapollo.nema.storage.PeerIdentityStore(database.messageDao()).saveRoom(ACCOUNT, PEER, room)
        startPresenter()
        presenter.selectNotificationDestination(PEER, project)
        val before = awaitState { it.selectedThread == project && it.recentThreads.any { entry -> entry.thread == sibling && entry.unreadCount == 1 } }
        showConversation()
        compose.onNodeWithTag("thread-switcher").assertTextContains("Threads · 1 unread")
        // Other account/peer/kind and historical MAM input must not increment this destination.
        store.ingest(incoming("other-account", "Other account", kind = kind).copy(accountId = OTHER_ACCOUNT))
        store.ingest(incoming("other-peer", "Other peer", kind = kind).copy(peerJid = OTHER_PEER, senderJid = OTHER_PEER))
        store.ingest(incoming("history", "Historical Main", kind = kind).copy(
            unreadEligible = false, archiveOrdinal = 1,
            archiveAuthority = if (room) PEER else "$ACCOUNT@example.org",
            archiveScope = if (room) PEER else "ACCOUNT",
        ))
        assertEquals(0, repository.observeConversationTimeline(DirectConversationKey(ACCOUNT, PEER, project)).first().mainUnreadCount)
        store.ingest(incoming("main-body", "Incoming Main", kind = kind))
        awaitState { it.conversations.any { row -> row.peerJid == PEER && row.unreadCount == 3 } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Main · 1 unread")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("thread-switcher").assertTextContains("Main · 1 unread")
        compose.onNodeWithTag("thread-switcher").assertTextContains("Threads · 1 unread")
        assertEquals(before.messages, presenter.state.value.messages)
        assertEquals(before.recentThreads, presenter.state.value.recentThreads)
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithTag("thread-destination-main").assertIsDisplayed().assertTextContains("1 unread")
        compose.onNodeWithTag("thread-destination-main").performClick()
        val main = awaitState { it.selectedPeer == PEER && it.selectedThread == null && it.messages.any { message -> message.id == "main-body" } }
        assertFalse(main.messages.any { it.id in setOf("project-body", "sibling-body") })
        // Admit the actual Main viewport through the presenter, never a peer-wide blanket mark.
        assertTrue(presenter.markVisibleConversationRead(VisibleReadRequest(
            ACCOUNT, main.routeOccurrence, main.messages.map { it.id }.toSet(), main.messages.map { it.id },
        )))
        withTimeout(5_000) { repository.observeConversations(ACCOUNT).first { it.single { row -> row.peerJid == PEER }.unreadCount == 1 } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Main · 1 unread")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("thread-switcher").assertTextContains("Threads · 1 unread")
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithTag("thread-destination-main").assertTextContains("Main")
        if (archived) {
            compose.onNodeWithTag("thread-destination-${sibling.id.value}").assertDoesNotExist()
            compose.onNodeWithText("Archived (2) · 1 unread").performClick()
        }
        compose.onNodeWithTag("thread-destination-${sibling.id.value}")
            .assertTextContains(if (shared) "Shared · 1 unread" else "On this device · 1 unread")
        assertEquals(1, repository.observeRecentThreads(ACCOUNT, PEER).first().single { it.thread == sibling }.unreadCount)
        assertFalse(store.messages(OTHER_ACCOUNT).single().locallyRead)
        assertFalse(store.messages(ACCOUNT).single { it.localMessageId == "other-peer" }.locallyRead)
    }

    @Test fun mainActivityUsesVisibleMembershipAndNamedReadDoesNotClearMain() = visibleMembershipJourney(false)
    @Test fun mainActivityUsesVisibleMembershipAndSharedReadDoesNotClearMain() = visibleMembershipJourney(true)

    private fun visibleMembershipJourney(shared: Boolean) = runBlocking {
        val project = if (shared) ThreadRef(ThreadId.require(UUID.randomUUID().toString()))
            else requireNotNull(repository.createNamedThread(ACCOUNT, PEER, MessageKind.CHAT, "Project"))
        if (shared) publishShared(MessageKind.CHAT, project to "Project")
        val session = ThreadRef(ThreadId.require("implicit-main"))
        val child = ThreadRef(ThreadId.require("hidden-child"), session.id)
        store.ingest(incoming("main-root", "Main root", session).copy(
            unreadEligible = false,
            aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "main-wire")),
        ))
        store.ingest(incoming("hidden-child", "Hidden child unread", child).copy(replyToId = "main-wire", replyToJid = PEER))
        store.ingest(incoming("main-unread", "Main unread", session))
        store.ingest(incoming("named-unread", "Named unread", project))
        startPresenter()
        presenter.selectNotificationDestination(PEER, project)
        val named = awaitState { it.selectedThread == project && it.mainUnreadCount == 1 &&
            it.recentThreads.any { entry -> entry.thread == project && entry.unreadCount == 1 } }
        assertEquals(listOf("named-unread"), named.messages.map { it.id })
        assertTrue(presenter.markVisibleConversationRead(VisibleReadRequest(
            ACCOUNT, named.routeOccurrence, setOf("named-unread"), named.messages.map { it.id },
        )))
        val readNamed = awaitState { it.recentThreads.single { entry -> entry.thread == project }.unreadCount == 0 }
        assertEquals(1, readNamed.mainUnreadCount)
        assertFalse(store.messages(ACCOUNT).single { it.localMessageId == "main-unread" }.locallyRead)
        presenter.closeThread()
        val main = awaitState { it.selectedPeer == PEER && it.selectedThread == null && it.messages.isNotEmpty() }
        assertEquals(listOf("main-root", "main-unread"), main.messages.map { it.id })
        assertEquals(child, main.messages.first().threadSummaries.single().thread)
        assertTrue(presenter.markVisibleConversationRead(VisibleReadRequest(
            ACCOUNT, main.routeOccurrence, main.messages.map { it.id }.toSet(), main.messages.map { it.id },
        )))
        awaitState { it.mainUnreadCount == 0 }
        assertFalse(store.messages(ACCOUNT).single { it.localMessageId == "hidden-child" }.locallyRead)
        assertTrue(store.messages(ACCOUNT).single { it.localMessageId == "main-unread" }.locallyRead)
    }

    /** Exercise the shared-only branch of thread_destinations, never private title rows. */
    private suspend fun publishShared(kind: MessageKind, vararg entries: Pair<ThreadRef, String>, archived: Boolean = false) {
        val bare = "$ACCOUNT@example.org"
        SharedThreadStore(database).snapshot(ACCOUNT, bare, PEER, kind, ThreadDirectorySnapshot(
            bare, "example.org", if (kind == MessageKind.CHAT) ThreadDirectoryScope.Direct(bare, PEER)
                else ThreadDirectoryScope.Muc(PEER, "a".repeat(64)),
            "opaque", entries.map { (thread, title) -> ThreadDirectoryItem(UUID.fromString(thread.id.value), title, 1, archived, true) },
        ))
        assertTrue(database.messageDao().observeThreadTitles(ACCOUNT, PEER).first().isEmpty())
        assertTrue(repository.observeRecentThreads(ACCOUNT, PEER).first().filter { it.locallyNamed }.all { it.shared != null })
    }

    private fun incoming(id: String, body: String, thread: ThreadRef? = null, kind: MessageKind = MessageKind.CHAT) = IncomingMessage(
        accountId = ACCOUNT, localMessageId = id, peerJid = PEER, senderJid = PEER,
        direction = MessageDirection.INBOUND, messageKind = kind,
        threadId = thread?.id?.value, parentThreadId = thread?.parentId?.value,
        body = body, archiveOrdinal = null, aliases = emptyList(),
    )

    companion object {
        private const val ACCOUNT = "account"
        private const val OTHER_ACCOUNT = "other-account"
        private const val PEER = "peer@example.org"
        private const val OTHER_PEER = "other-peer@example.org"
    }
}
