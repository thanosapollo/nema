package org.thanosapollo.nema

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.android.controller.ActivityController
import org.thanosapollo.nema.account.LoginFormInput
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.chat.ChatRoute
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.chat.DirectChatPresenter
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.credentials.CredentialBlobStore
import org.thanosapollo.nema.credentials.CredentialCipher
import org.thanosapollo.nema.credentials.CredentialVault
import org.thanosapollo.nema.credentials.WrappedCredential
import org.thanosapollo.nema.service.SessionRuntime
import org.thanosapollo.nema.service.ConnectionCommandOutcome
import org.thanosapollo.nema.session.SessionConnection
import org.thanosapollo.nema.session.SessionConnectionFactory
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.session.SessionEvent
import org.thanosapollo.nema.service.XmppConnectionService
import org.thanosapollo.nema.storage.AccountRepository
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.MessageThreadEntity
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.ArchiveCursorKey
import org.thanosapollo.nema.storage.ArchivePage
import org.thanosapollo.nema.storage.ArchiveDirection
import org.thanosapollo.nema.storage.ArchivedIncomingMessage
import org.thanosapollo.nema.storage.SharedThreadStore
import org.thanosapollo.nema.xmpp.threads.ThreadDirectoryItem
import org.thanosapollo.nema.xmpp.threads.ThreadDirectoryScope
import org.thanosapollo.nema.xmpp.threads.ThreadDirectorySnapshot
import org.thanosapollo.nema.xmpp.transport.ArchivePageRequest
import org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope
import org.thanosapollo.nema.xmpp.transport.ArchivePageDirection
import org.thanosapollo.nema.xmpp.transport.ArchiveMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.SessionCapabilities
import org.thanosapollo.nema.xmpp.transport.CarbonCapabilityState
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import java.util.UUID
import org.thanosapollo.nema.xmpp.transport.AccountId

@RunWith(RobolectricTestRunner::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
@Config(sdk = [34], application = NemaApplication::class)
class MessageNotificationTapTest {
    @get:Rule val compose = createEmptyComposeRule()

    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun provideUnusedTestKeystore() {
            // Only construction is needed. No credential is stored or transport started.
            java.security.Security.addProvider(object : java.security.Provider("NemaTest", 1.0, "JVM test only") {
                init { put("KeyStore.AndroidKeyStore", "com.sun.crypto.provider.JceKeyStore") }
            })
        }

        @JvmStatic
        @org.junit.AfterClass
        fun removeTestKeystore() { java.security.Security.removeProvider("NemaTest") }
    }

    private val app get() = ApplicationProvider.getApplicationContext<NemaApplication>()
    private val accountId = AccountId.require("account-a")

    private fun seed() {
        val account = LoginFormInput("me@example.org").toConfiguration(accountId)
        runBlocking {
            AccountRepository(app.database.accountDao()).apply { save(account); activate(account.id) }
        }
        app.sessionRuntime.claimAutomaticConnectionStart()
    }

    // Literal external contract, not the producer/helper: proves Activity + Compose consume it.
    private fun tap(peer: String = "peer@example.org", owner: String = accountId.value) =
        Intent(app, MainActivity::class.java)
            .setAction("org.thanosapollo.nema.action.OPEN_MESSAGE")
            .putExtra(XmppConnectionService.EXTRA_ACCOUNT_ID, owner)
            .putExtra(XmppConnectionService.EXTRA_PEER_JID, peer)

    private fun launch(intent: Intent = tap(), state: Bundle = Bundle()): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java, intent).create(state).start().resume().visible()

    private fun awaitPeer(peer: String?) {
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            app.sessionRuntime.visiblePeer.get() == peer
        }
        compose.waitForIdle()
        assertEquals(peer, app.sessionRuntime.visiblePeer.get())
    }

    private fun awaitFeedback() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Message not opened").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Message not opened").assertExists()
    }

    private fun destroy(controller: ActivityController<MainActivity>) { controller.pause().stop().destroy() }

    @Test
    fun namedThreadTapOpensExactDestinationAndBackReturnsToMainInRealActivity() {
        seed()
        val root = runBlocking { requireNotNull(app.chatRepository.createNamedThread(accountId.value, "peer@example.org", MessageKind.CHAT, "Project")) }
        val intent = tap().putExtra("thread_id", root.id.value)
        val controller = launch(intent)
        try {
            awaitPeer("peer@example.org")
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Message Project").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Message Project").assertExists()
            compose.waitUntil(5_000) {
                runBlocking { app.chatRepository.observeRoute(accountId.value).first() == ChatRoute("peer@example.org", root) }
            }
            controller.get().onBackPressedDispatcher.onBackPressed()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Message Main").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("peer@example.org", app.sessionRuntime.visiblePeer.get())
        } finally { destroy(controller) }
    }

    @Test
    fun namedAndChildPendingIntentsRoundTripFullLineageWithoutRetargetingMain() {
        val root = ThreadRef(ThreadId.require("project/root"))
        val child = ThreadRef(ThreadId.require("task"), root.id)
        val targets = listOf(null, root, child, child.copy(parentId = ThreadId.require("other")))
            .map { MessageNotificationTarget(accountId.value, "peer@example.org", it) }
        val pending = targets.map { it.pendingIntent(app) }
        assertEquals(targets.size, pending.toSet().size)
        assertEquals(targets.size, targets.map { it.notificationTag }.toSet().size)
        targets.forEachIndexed { index, target ->
            pending[index].send()
            assertEquals(target, MessageNotificationTarget.fromIntent(shadowOf(app).nextStartedActivity))
        }
        listOf(
            tap().putExtra("parent_thread_id", "orphan"),
            tap().putExtra("thread_id", ""),
            tap().putExtra("thread_id", "same").putExtra("parent_thread_id", "same"),
            tap().putExtra("thread_id", 42),
        ).forEach { assertNull(MessageNotificationTarget.fromIntent(it)) }
    }

    @Test
    fun coldNotificationOpensNotifiedConversationInRealActivity() {
        seed()
        val controller = launch()
        try { awaitPeer("peer@example.org") } finally { destroy(controller) }
    }

    @Test
    fun warmTapReplacesRouteAndBackDoesNotReplayAfterRecreation() {
        seed()
        val controller = launch()
        try {
            awaitPeer("peer@example.org")
            controller.newIntent(tap("other@example.org"))
            awaitPeer("other@example.org")
            controller.get().onBackPressedDispatcher.onBackPressed()
            awaitPeer(null)
            val saved = Bundle()
            controller.saveInstanceState(saved)
            // Recreate with the original launch Intent, as Android can retain it.
            destroy(controller)
            val recreated = launch(tap(), saved)
            try {
                compose.waitForIdle()
                awaitPeer(null)
                assertNull(MessageNotificationTarget.fromIntent(recreated.get().intent))
                recreated.newIntent(tap("other@example.org"))
                awaitPeer("other@example.org")
            } finally { destroy(recreated) }
        } finally {
            if (!controller.get().isDestroyed) destroy(controller)
        }
    }

    @Test
    fun pendingTapSurvivesRecreationBeforeCompositionConsumesIt() {
        seed()
        val first = Robolectric.buildActivity(MainActivity::class.java, tap()).create(Bundle())
        val saved = Bundle()
        first.saveInstanceState(saved).destroy()
        val second = launch(Intent(app, MainActivity::class.java), saved)
        try { awaitPeer("peer@example.org") } finally { destroy(second) }
    }

    @Test
    fun latestWarmTapWinsAndOldSavedRouteCannotOverrideIt() {
        seed()
        runBlocking { app.chatRepository.saveRoute(accountId.value, ChatRoute("old@example.org")) }
        val saved = Bundle().apply { putString("nema.process-token", app.processToken) }
        val controller = launch(tap(), saved)
        controller.newIntent(tap("newest@example.org"))
        try {
            awaitPeer("newest@example.org")
            compose.waitUntil(5_000) {
                runBlocking { app.chatRepository.observeRoute(accountId.value).first()?.peerJid == "newest@example.org" }
            }
        } finally { destroy(controller) }
    }

    @Test
    fun wrongAccountSamePeerShowsFeedbackAndNeverSwitches() {
        seed()
        runBlocking {
            AccountRepository(app.database.accountDao()).save(
                LoginFormInput("other@example.org").toConfiguration(AccountId.require("account-b")),
            )
        }
        val controller = launch()
        try {
            awaitPeer("peer@example.org")
            controller.newIntent(tap(owner = "account-b"))
            awaitFeedback()
            assertEquals(accountId, runBlocking { app.sessionRuntime.activeAccount.first() }?.id)
            assertEquals("peer@example.org", app.sessionRuntime.visiblePeer.get())
            compose.onNodeWithText("OK").performClick()
            controller.get().onBackPressedDispatcher.onBackPressed()
            awaitPeer(null)
        } finally { destroy(controller) }
    }

    @Test
    fun coldWrongAccountTapNeverOpensPeerOrActivatesItsOwner() {
        seed()
        val controller = launch(tap(owner = "account-b"))
        try {
            awaitFeedback()
            assertNull(app.sessionRuntime.visiblePeer.get())
            assertEquals(accountId, runBlocking { app.sessionRuntime.activeAccount.first() }?.id)
            assertNull(MessageNotificationTarget.fromIntent(controller.get().intent))
        } finally { destroy(controller) }
    }

    @Test
    fun absentAccountIsResolvedRatherThanWaitingForever() {
        app.sessionRuntime.claimAutomaticConnectionStart()
        val controller = launch()
        try {
            awaitFeedback()
            assertNull(runBlocking { app.sessionRuntime.activeAccount.first() })
            assertNull(app.sessionRuntime.visiblePeer.get())
        } finally { destroy(controller) }
    }

    @Test
    fun malformedAndLegacyIntentsDoNotNavigateOrReplaceCurrentRoute() {
        seed()
        val controller = launch()
        try {
            awaitPeer("peer@example.org")
            val invalid = listOf(
                Intent(app, MainActivity::class.java).putExtra("peer_jid", "other@example.org"),
                tap().removeOwner(), tap(peer = "not a jid"), tap(peer = "peer@example.org/resource"),
                tap(owner = ""), tap(owner = "bad\naccount"), tap(owner = "a".repeat(1025)),
                tap().putExtra("peer_jid", 42), tap().putExtra("account_id", 42),
                tap().apply { removeExtra("peer_jid") }, tap().setAction(Intent.ACTION_VIEW),
            )
            invalid.forEach {
                controller.newIntent(it)
                compose.waitForIdle()
                assertEquals("peer@example.org", app.sessionRuntime.visiblePeer.get())
            }
        } finally { destroy(controller) }
    }

    @Test
    fun pendingIdentitySeparatesAccountsAndCollidingPeersWithoutRetargeting() {
        val targets = listOf(
            MessageNotificationTarget("account-a", "an@example.org"),
            MessageNotificationTarget("account-b", "an@example.org"),
            MessageNotificationTarget("account-a", "c0@example.org"),
        )
        assertEquals(targets[0].peerJid.hashCode(), targets[2].peerJid.hashCode())
        val pending = targets.map { it.pendingIntent(app) }
        assertEquals(3, pending.toSet().size)
        assertEquals(3, targets.map { it.notificationTag }.toSet().size)
        targets.forEachIndexed { index, target ->
            assertEquals(pending[index], target.pendingIntent(app))
            assertTrue(pending[index].isImmutable)
            pending[index].send()
            val delivered = shadowOf(app).nextStartedActivity
            assertEquals(target, MessageNotificationTarget.fromIntent(delivered))
            assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                delivered.flags and (Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    @Test fun liveMainNotificationThenPrivateNameOpensActualMainAndNamedDrafts() = notificationMembershipJourney(false, false)
    @Test fun afterArchiveMainNotificationThenPrivateNameOpensActualMainAndNamedDrafts() = notificationMembershipJourney(true, false)
    @Test fun liveMainNotificationThenSharedNameOpensActualMainAndNamedDrafts() = notificationMembershipJourney(false, true)
    @Test fun afterArchiveMainNotificationThenSharedNameOpensActualMainAndNamedDrafts() = notificationMembershipJourney(true, true)

    @Test fun liveMainNotificationThenArchivedSharedNameOpensActualMainAndNamedDrafts() = notificationMembershipJourney(false, true, true)
    @Test fun afterArchiveMainNotificationThenArchivedSharedNameOpensActualMainAndNamedDrafts() = notificationMembershipJourney(true, true, true)

    /** Real Room/runtime production callback -> PendingIntent -> Activity, with no network. */
    private fun notificationMembershipJourney(archive: Boolean, shared: Boolean, archivedName: Boolean = false) = runBlocking<Unit> {
        seed()
        val peer = "peer@example.org"
        val account = requireNotNull(app.sessionRuntime.activeAccount.first())
        val repository = app.chatRepository
        val store = MessageStore(app.database)
        val thread = ThreadRef(ThreadId.require(UUID.randomUUID().toString()))
        val mainKey = DirectConversationKey(accountId.value, peer)
        val namedKey = mainKey.copy(thread = thread)
        fun incoming(id: String, body: String, wireThread: String) = IncomingMessage(
            accountId.value, id, peer, peer, MessageDirection.INBOUND, MessageKind.CHAT,
            wireThread, null, body, null, emptyList(),
        )
        // Establish a real cursor so the runtime's first MAM query is AFTER, not bootstrap.
        store.applyArchivePage(ArchivePage(
            ArchiveCursorKey(accountId.value, account.bareJid.value, "ACCOUNT"), ArchiveDirection.BOOTSTRAP,
            null, true, false, true, "initial", "initial",
            listOf(ArchivedIncomingMessage("initial", incoming("older", "Older Main body", "older-session"))),
        ))
        store.ingest(incoming("current", "Current Main body", thread.id.value))
        repository.saveDraft(mainKey, "Saved Main draft")
        repository.saveDraft(namedKey, "Saved named draft")
        // A title for a different account, peer, or kind must not name this CHAT destination.
        val dao = app.database.messageDao()
        app.database.accountDao().upsert(AccountEntity("foreign", "foreign@example.org", "foreign", null, "example.org", null, null))
        for ((owner, venue, kind) in listOf(Triple("foreign", peer, MessageKind.CHAT),
            Triple(accountId.value, "other@example.org", MessageKind.CHAT), Triple(accountId.value, peer, MessageKind.GROUPCHAT))) {
            assertTrue(dao.createNamedThread(MessageThreadEntity(owner, venue, kind, thread.id.value, null), "Not this destination"))
        }
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val targets = Channel<MessageNotificationTarget>(Channel.UNLIMITED)
        var credential: WrappedCredential? = null
        val vault = CredentialVault(object : CredentialBlobStore {
            override fun read(accountId: AccountId) = credential
            override fun write(accountId: AccountId, value: WrappedCredential) { credential = value }
            override fun delete(accountId: AccountId) { credential = null }
        }, object : CredentialCipher {
            override fun encrypt(accountId: AccountId, plaintext: ByteArray) = WrappedCredential(byteArrayOf(1), plaintext.copyOf())
            override fun decrypt(accountId: AccountId, credential: WrappedCredential) = credential.ciphertext.copyOf()
            override fun deleteKey(accountId: AccountId) = Unit
        })
        vault.store(accountId, "fixture-only".toCharArray())
        var phase = 0
        var archiveRequests = 0
        lateinit var attempt: SessionAttemptIdentity
        lateinit var deliver: (SessionEvent) -> Unit
        fun envelope(generation: ConnectionGeneration) = IncomingMessageEnvelope(
            accountId, generation, peer, peer, false, null, "Notification $phase", thread,
            messageId = "notification-$phase", sentAtEpochMs = 2_000L + phase,
            sentTimeSource = if (archive) org.thanosapollo.nema.xmpp.transport.MessageTimeSource.MAM
                else org.thanosapollo.nema.xmpp.transport.MessageTimeSource.LOCAL,
        )
        val runtime = SessionRuntime(AccountRepository(app.database.accountDao()), vault, store, app.peerIdentityStore,
            ownerScope, SessionConnectionFactory { _, _, event ->
                deliver = event
                object : SessionConnection {
                    override var isUsable = true
                    override fun revoke() { isUsable = false }
                    override suspend fun connect(credential: CharArray, identity: SessionAttemptIdentity) { attempt = identity }
                    override suspend fun reconnect(identity: SessionAttemptIdentity) { attempt = identity }
                    override fun updateAttempt(identity: SessionAttemptIdentity) { attempt = identity }
                    override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) = error("No sends expected")
                    override suspend fun disconnect() { isUsable = false }
                    override suspend fun discoverCapabilities(accountId: AccountId, generation: ConnectionGeneration) =
                        SessionCapabilities(archive, CarbonCapabilityState.UNSUPPORTED, false)
                    override suspend fun queryArchive(request: ArchivePageRequest): ArchivePageEnvelope {
                        assertEquals(ArchivePageDirection.AFTER, request.direction)
                        archiveRequests++
                        return ArchivePageEnvelope(request, true, true, false, "after-$phase", "after-$phase",
                            listOf(ArchiveMessageEnvelope("after-$phase", envelope(request.generation))))
                    }
                }
            })
        runtime.onInsertedInbound = { owner, venue, _, destination ->
            targets.trySend(MessageNotificationTarget(owner.value, venue, destination)).getOrThrow()
        }
        try {
            for (named in listOf(false, true)) {
                if (named) {
                    if (shared) {
                        SharedThreadStore(app.database).snapshot(accountId.value, account.bareJid.value, peer, MessageKind.CHAT,
                            ThreadDirectorySnapshot(account.bareJid.value, "example.org", ThreadDirectoryScope.Direct(account.bareJid.value, peer),
                                "fixture", listOf(ThreadDirectoryItem(UUID.fromString(thread.id.value), "Project", 1, archivedName, true))))
                        assertTrue(dao.observeThreadTitles(accountId.value, peer).first().none { it.messageKind == MessageKind.CHAT })
                    } else assertTrue(repository.renameThread(accountId.value, peer, MessageKind.CHAT, thread, "Project"))
                }
                assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
                if (!archive) deliver(SessionEvent.Incoming(attempt, envelope(attempt.generation)))
                val target = withTimeout(5_000) { targets.receive() }
                runtime.stop()
                // Canonical wire identity is still persisted even when its UI destination is Main.
                assertEquals(thread.id.value, store.messages(accountId.value).single { it.body == "Notification $phase" }.threadId)
                val presenter = DirectChatPresenter(account, repository, ownerScope, { _, _ -> false })
                try {
                    assertTrue(presenter.selectNotificationDestination(target.peerJid, target.thread))
                    val selected = withTimeout(5_000) { presenter.state.first { it.selectedPeer == peer && it.contentStatus == ChatContentStatus.Ready } }
                    assertEquals(if (named) thread else null, selected.selectedThread)
                    assertEquals(if (named) "Saved named draft" else "Saved Main draft", selected.draft)
                    assertTrue(selected.messages.any { it.body == "Current Main body" })
                    assertEquals(!named, selected.messages.any { it.body == "Older Main body" })
                } finally { presenter.close() }
                target.pendingIntent(app).send()
                val controller = launch(shadowOf(app).nextStartedActivity)
                try {
                    awaitPeer(peer)
                    val title = if (named) "Project" else "Main"
                    val draft = if (named) "Saved named draft" else "Saved Main draft"
                    compose.onNodeWithTag("thread-switcher").assertTextContains(title)
                    compose.onNodeWithTag("message-composer").assertTextContains(draft)
                    compose.onNodeWithTag("message-timeline").performScrollToKey("current")
                    compose.onNode(hasText("Current Main body", substring = false) and hasAnyAncestor(hasTestTag("message-timeline")), useUnmergedTree = true).assertExists()
                    if (!named) {
                        compose.onNodeWithTag("message-timeline").performScrollToKey("older")
                        compose.onNode(hasText("Older Main body", substring = false) and hasAnyAncestor(hasTestTag("message-timeline")), useUnmergedTree = true).assertExists()
                    }
                    else compose.onNode(hasText("Older Main body", substring = false) and hasAnyAncestor(hasTestTag("message-timeline")), useUnmergedTree = true).assertDoesNotExist()
                    // Placeholder and saved draft cannot be visible simultaneously. Exercise both.
                    compose.onNodeWithTag("message-composer").performTextReplacement("")
                    compose.onNodeWithText("Message $title").assertExists()
                    compose.onNodeWithTag("message-composer").performTextReplacement(draft)
                    compose.waitUntil(5_000) { runBlocking { repository.observeDraft(if (named) namedKey else mainKey).first() == draft } }
                } finally { destroy(controller) }
                assertEquals("Saved Main draft", repository.observeDraft(mainKey).first())
                phase++
            }
            assertEquals(if (archive) 2 else 0, archiveRequests)
            assertTrue(targets.tryReceive().isFailure)
        } finally { runtime.stop(); ownerScope.cancel(); targets.close() }
    }

    private fun Intent.removeOwner() = apply { removeExtra("account_id") }
}
