package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.*
import org.thanosapollo.nema.chat.*
import org.thanosapollo.nema.credentials.*
import org.thanosapollo.nema.service.SessionRuntime
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.thread.*
import org.thanosapollo.nema.xmpp.threads.*
import org.thanosapollo.nema.xmpp.transport.*

/** Deterministic connection fixture, not socket/server or physical Android evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedRenameAuthorityTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "rename-authority-${UUID.randomUUID()}.db"
    private lateinit var db: NemaDatabase
    private lateinit var runtime: SessionRuntime
    private lateinit var presenter: DirectChatPresenter
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })

    @After fun cleanup() = runBlocking {
        if (::presenter.isInitialized) presenter.close()
        if (::runtime.isInitialized) runtime.stop()
        scope.coroutineContext[Job]?.cancelAndJoin()
        if (::db.isInitialized) db.close()
        context.deleteDatabase(databaseName)
        assertTrue("Background failures: $failures", failures.isEmpty())
    }

    @Test fun directOldRenameRejectsReconnectAndFreshFormAcceptsActivity() = journey(MessageKind.CHAT)
    @Test fun mucOldRenameRejectsReconnectAndFreshFormAcceptsActivity() = journey(MessageKind.GROUPCHAT)

    private fun journey(kind: MessageKind) = runBlocking<Unit> {
        db = NemaDatabase.create(context, databaseName)
        val account = AccountConfiguration.create(AccountId.require("rename-account"), "alice@example.org", "alice",
            null, "example.org", null)
        val accounts = AccountRepository(db.accountDao())
        // A real Activity creates its presenter only after the active account is durable.
        accounts.save(account)
        accounts.activate(account.id)
        val credentials = CredentialVault(MemoryBlobs(), TestCipher())
        credentials.store(account.id, "fixture".toCharArray())
        val repository = ChatRepository(db)
        val store = MessageStore(db)
        val peer = if (kind == MessageKind.CHAT) "bob@example.org" else "room@rooms.example.org"
        if (kind == MessageKind.GROUPCHAT) repository.markRoom(account.id.value, peer)
        val directory = if (kind == MessageKind.CHAT) ThreadDirectoryScope.Direct(account.bareJid.value, peer)
            else ThreadDirectoryScope.Muc(peer, "a".repeat(64))
        var row = ThreadDirectoryItem(UUID.randomUUID(), "Project", 1, false, true)
        val mutations = CopyOnWriteArrayList<DirectoryAction>()
        val submissions = CopyOnWriteArrayList<Boolean>()
        val submittedContexts = CopyOnWriteArrayList<DirectoryContext?>()
        // Count every committed INSERT, even if confirmation immediately removes the intent.
        db.openHelper.writableDatabase.execSQL("CREATE TABLE rename_intent_audit(operationId TEXT NOT NULL)")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER rename_intent_insert AFTER INSERT ON thread_directory_intents BEGIN INSERT INTO rename_intent_audit VALUES (NEW.operationId); END")
        fun intentCount() = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM rename_intent_audit").use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
        val factory = SessionConnectionFactory { configured, _, _ ->
            assertEquals(account, configured)
            RecordingConnection(account.id, read = { requested ->
                assertEquals(directoryScope(account.bareJid.value, peer, kind), requested)
                ThreadDirectorySnapshot(account.bareJid.value, "example.org", directory, "snapshot", listOf(row))
            }, write = { action ->
                assertEquals(directory, action.context.scope)
                assertEquals(row.revision, action.revision)
                assertEquals(action.operationId.toString(), store.sharedThreads.intent(account.id.value, peer, kind)?.operationId)
                mutations += action
                row = row.copy(title = requireNotNull(action.title), revision = row.revision + 1)
                ThreadDirectoryMutationResult(account.bareJid.value, "example.org", directory, "snapshot", 1,
                    action.operationId, false, row)
            })
        }
        runtime = SessionRuntime(accounts, credentials, store, PeerIdentityStore(db.messageDao()), scope, factory)
        presenter = DirectChatPresenter(account, repository, scope, runtime::enqueueDirect,
            directoryConnection = runtime.state, refreshDirectory = runtime::refreshThreadDirectory,
            changeDirectory = runtime::changeThreadDirectory)
        val notificationRefreshes = kotlinx.coroutines.channels.Channel<DirectoryView>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        // Consume the notification immediately, before its publisher continues.
        // Directory refresh must see the new lease, not a still-Connecting lifecycle.
        scope.launch(Dispatchers.Unconfined) {
            runtime.state.collect { connection ->
                if (connection is ConnectionState.Connected && connection.generation.value > 1) {
                    notificationRefreshes.send(runtime.refreshThreadDirectory(account, peer, kind))
                }
            }
        }
        suspend fun connect(): ConnectionState.Connected {
            runtime.connectActive()
            val connection = withTimeout(5_000) { runtime.state.first { it is ConnectionState.Connected } as ConnectionState.Connected }
            if (connection.generation.value > 1) {
                val refreshed = withTimeout(5_000) { notificationRefreshes.receive() }
                assertEquals("Refresh consuming reconnect must see its lifecycle lease", DirectoryMode.SHARED, refreshed.mode)
                assertEquals(connection.generation.value, refreshed.context?.generation)
            }
            return connection
        }
        suspend fun ready() = withTimeout(5_000) { presenter.state.first {
            it.selectedPeer == peer && it.contentStatus == ChatContentStatus.Ready && it.recentThreads.any { entry -> entry.shared != null }
        } }
        suspend fun shared(generation: Long) = withTimeout(5_000) { presenter.directoryState.first {
            it.mode == DirectoryMode.SHARED && it.context?.generation == generation
        } }
        val firstConnection = connect()
        presenter.selectPeer(peer)
        val before = ready()
        val authored = requireNotNull(shared(firstConnection.generation.value).context)
        val original = before.recentThreads.single()
        assertEquals(1L, original.shared!!.revision)
        assertTrue(db.messageDao().observeThreadTitles(account.id.value, peer).first().isEmpty())
        compose.setContent {
            val state by presenter.state.collectAsState()
            val view by presenter.directoryState.collectAsState()
            MaterialTheme {
                ConversationContent(state = state, connectionStatus = "Connected", onSelectPeer = presenter::selectPeer,
                    onCloseConversation = presenter::closeConversation, onCloseThread = presenter::closeThread,
                    onDraftChange = { CompletableDeferred(true) }, onSend = { CompletableDeferred(false) },
                    onSelectThreadDestination = presenter::selectThreadDestination,
                    onRenameNamedThread = { origin, target, title, captured ->
                        submittedContexts += captured
                        presenter.renameNamedThread(origin, target, title, captured).also { submissions += it }
                    }, directoryView = view)
            }
        }
        compose.onNodeWithTag("thread-switcher").performClick()
        compose.onNodeWithContentDescription("Rename Project for everyone").performClick()
        compose.onNodeWithTag("thread-name-input").performTextReplacement("Authored before reconnect")
        // Replace the authenticated connection without replacing the account, presenter or route.
        runtime.stop()
        val secondConnection = connect()
        assertEquals(firstConnection.accountId, secondConnection.accountId)
        assertEquals(firstConnection.generation.value + 1, secondConnection.generation.value)
        val refreshed = requireNotNull(shared(secondConnection.generation.value).context)
        val after = ready()
        assertEquals(authored.copy(generation = refreshed.generation), refreshed)
        assertEquals(before.routeOccurrence, after.routeOccurrence)
        assertEquals(original.shared, after.recentThreads.single().shared)
        assertEquals(0, intentCount())
        compose.onNodeWithText("Save name").performClick()
        compose.waitUntil(5_000) { submissions.size == 1 }
        assertEquals("Old form must reach and be rejected by the actual presenter", listOf(false), submissions.toList())
        assertEquals(listOf(authored), submittedContexts.toList())
        // Bypass Compose: the real presenter must reject stale or absent authoring authority too.
        assertFalse(presenter.renameNamedThread(before.routeOccurrence, original, "Stale direct call", authored))
        assertFalse(presenter.renameNamedThread(before.routeOccurrence, original, "Missing authority", null))
        assertFalse(presenter.renameNamedThread(before.routeOccurrence, original, "Foreign authority",
            refreshed.copy(authority = "elsewhere.example.org")))
        val foreignScope = if (kind == MessageKind.CHAT) ThreadDirectoryScope.Direct(account.bareJid.value, "other@example.org")
            else ThreadDirectoryScope.Muc(peer, "b".repeat(64))
        assertFalse(presenter.renameNamedThread(before.routeOccurrence, original, "Changed scope", refreshed.copy(scope = foreignScope)))
        // Runtime remains the last generation fence, even if a caller skips the presenter.
        assertFalse(runtime.changeThreadDirectory(account, peer, kind, DirectoryAction(authored,
            threadId = row.id, revision = row.revision, title = "Stale runtime call")).confirmed)
        assertEquals("No stale durable intent, including immediately confirmed intents", 0, intentCount())
        assertTrue("No stale transport mutation", mutations.isEmpty())
        assertNull(store.sharedThreads.intent(account.id.value, peer, kind))
        compose.onNodeWithTag("thread-name-input").assertTextContains("Authored before reconnect")
        compose.onNodeWithText("Not saved. Try again.").assertIsDisplayed()
        // Explicitly reopen under the new generation; harmless activity is not changed authority.
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Rename Project for everyone").performClick()
        compose.onNodeWithTag("thread-name-input").performTextReplacement("Fresh name")
        assertEquals(0, original.unreadCount)
        assertEquals(0, original.replyCount)
        repeat(2) { index ->
            store.ingest(IncomingMessage(account.id.value, "activity-$index", peer,
                if (kind == MessageKind.CHAT) peer else "$peer/member", MessageDirection.INBOUND, kind,
                original.thread.id.value, null, "Activity while editing $index", null, emptyList()))
        }
        withTimeout(5_000) { presenter.state.first {
            it.recentThreads.singleOrNull()?.let { entry -> entry.unreadCount == 2 && entry.replyCount == 1 } == true
        } }
        compose.onNodeWithText("Save name").performClick()
        compose.waitUntil(5_000) { submissions.size == 2 }
        assertEquals(listOf(false, true), submissions.toList())
        assertEquals(listOf(authored, refreshed), submittedContexts.toList())
        assertEquals(1, intentCount())
        assertEquals(1, mutations.size)
        assertEquals(1L, mutations.single().revision)
        assertEquals("Fresh name", mutations.single().title)
        withTimeout(5_000) { presenter.state.first { it.recentThreads.singleOrNull()?.title == "Fresh name" } }
        compose.onNodeWithTag("thread-name-input").assertDoesNotExist()
        assertNull(store.sharedThreads.intent(account.id.value, peer, kind))
    }

    private class RecordingConnection(
        private val owner: AccountId,
        private val read: suspend (ThreadDirectoryScope) -> ThreadDirectorySnapshot,
        private val write: suspend (DirectoryAction) -> ThreadDirectoryMutationResult,
    ) : SessionConnection {
        override var isUsable = false
        private lateinit var attempt: SessionAttemptIdentity
        override fun revoke() { isUsable = false }
        override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) { updateAttempt(attempt); isUsable = true }
        override suspend fun reconnect(attempt: SessionAttemptIdentity) { updateAttempt(attempt); isUsable = true }
        override fun updateAttempt(attempt: SessionAttemptIdentity) { this.attempt = attempt }
        override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) = entered()
        override suspend fun disconnect() { isUsable = false }
        override suspend fun listThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, directory: ThreadDirectoryScope): ThreadDirectorySnapshot {
            assertEquals(owner, accountId); assertEquals(attempt.generation, generation); assertTrue(isUsable)
            return read(directory)
        }
        override suspend fun mutateThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, action: DirectoryAction): ThreadDirectoryMutationResult {
            assertEquals(owner, accountId); assertEquals(attempt.generation, generation); assertTrue(isUsable)
            return write(action)
        }
    }

    private class MemoryBlobs : CredentialBlobStore {
        private val values = mutableMapOf<AccountId, WrappedCredential>()
        override fun read(accountId: AccountId) = values[accountId]
        override fun write(accountId: AccountId, credential: WrappedCredential) { values[accountId] = credential }
        override fun delete(accountId: AccountId) { values.remove(accountId) }
    }
    private class TestCipher : CredentialCipher {
        override fun encrypt(accountId: AccountId, plaintext: ByteArray) = WrappedCredential(byteArrayOf(1), plaintext.copyOf())
        override fun decrypt(accountId: AccountId, credential: WrappedCredential) = credential.ciphertext.copyOf()
        override fun deleteKey(accountId: AccountId) = Unit
    }
}
