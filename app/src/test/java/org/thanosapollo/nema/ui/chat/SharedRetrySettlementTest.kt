package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
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

/** Real Compose/presenter/controller/Room; deterministic transport, not a network proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SharedRetrySettlementTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "retry-settlement-${UUID.randomUUID()}.db"
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

    @Test fun failedRetryRetainsOriginalCreateUntilExactConfirmation() = journey(MessageKind.GROUPCHAT, failRecoveryOnce = true)
    @Test fun failedKeepRetainsOriginalRenameUntilSuccessfulReadback() = journey(MessageKind.CHAT, rename = true, keep = true, failRecoveryOnce = true)
    @Test fun failedRefreshDoesNotDetachOriginalCreateRecovery() = journey(MessageKind.CHAT, failRefreshOnce = true)
    @Test fun directCreateExactRetrySettlesForm() = journey(MessageKind.CHAT)
    @Test fun mucCreateExactRetrySettlesForm() = journey(MessageKind.GROUPCHAT)
    @Test fun directRenameExactRetrySettlesForm() = journey(MessageKind.CHAT, rename = true)
    @Test fun mucRenameExactRetrySettlesForm() = journey(MessageKind.GROUPCHAT, rename = true)
    @Test fun directCreateKeepCurrentSettlesForm() = journey(MessageKind.CHAT, keep = true)
    @Test fun mucCreateKeepCurrentSettlesForm() = journey(MessageKind.GROUPCHAT, keep = true)
    @Test fun directRenameKeepCurrentSettlesForm() = journey(MessageKind.CHAT, rename = true, keep = true)
    @Test fun mucRenameKeepCurrentSettlesForm() = journey(MessageKind.GROUPCHAT, rename = true, keep = true)
    @Test fun editedInputSurvivesRetryOfOriginalCreate() = journey(MessageKind.CHAT, replacement = "edit")
    @Test fun replacementFormSurvivesKeepOfOriginalRename() = journey(MessageKind.GROUPCHAT, rename = true, keep = true, replacement = "form")
    @Test fun lateRetryCannotEraseOrRedirectReplacementRoute() = journey(MessageKind.CHAT, replacement = "route")
    @Test fun lateKeepCannotEraseOrRedirectReplacementRoute() = journey(MessageKind.GROUPCHAT, keep = true, replacement = "route")

    private fun journey(kind: MessageKind, rename: Boolean = false, keep: Boolean = false, replacement: String? = null, failRecoveryOnce: Boolean = false, failRefreshOnce: Boolean = false) = runBlocking<Unit> {
        db = NemaDatabase.create(context, databaseName)
        val account = AccountConfiguration.create(AccountId.require("retry-account"), "alice@example.org", "alice", null, "example.org", null)
        val accounts = AccountRepository(db.accountDao())
        accounts.save(account)
        accounts.activate(account.id)
        val credentials = CredentialVault(MemoryBlobs(), TestCipher())
        credentials.store(account.id, "fixture".toCharArray())
        val repository = ChatRepository(db)
        val store = MessageStore(db)
        val peer = if (kind == MessageKind.CHAT) "bob@example.org" else "room@rooms.example.org"
        if (kind == MessageKind.GROUPCHAT) repository.markRoom(account.id.value, peer)
        if (replacement == "form" || replacement == "route") repository.createNamedThread(account.id.value, peer, kind, "A private alias")
        val directory = if (kind == MessageKind.CHAT) ThreadDirectoryScope.Direct(account.bareJid.value, peer)
            else ThreadDirectoryScope.Muc(peer, "a".repeat(64))
        val rows = CopyOnWriteArrayList<ThreadDirectoryItem>()
        if (rename) rows += ThreadDirectoryItem(UUID.randomUUID(), "Original", 1, false, true)
        val mutations = CopyOnWriteArrayList<DirectoryAction>()
        val submissions = CopyOnWriteArrayList<Boolean>()
        val recoveries = CopyOnWriteArrayList<Boolean>()
        val refreshes = CopyOnWriteArrayList<DirectoryView>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holding = false
        var failRecovery = false
        var failRead = false
        db.openHelper.writableDatabase.execSQL("CREATE TABLE retry_intent_audit(operationId TEXT NOT NULL)")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER retry_intent_insert AFTER INSERT ON thread_directory_intents BEGIN INSERT INTO retry_intent_audit VALUES (NEW.operationId); END")
        fun intentIds() = db.openHelper.readableDatabase.query("SELECT operationId FROM retry_intent_audit").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val factory = SessionConnectionFactory { _, _, _ ->
            RecordingConnection(account.id, read = { requested ->
                if (failRead) { failRead = false; throw IOException("Refresh unavailable") }
                if (holding && keep) { entered.complete(Unit); release.await() }
                if (failRecovery && keep) { failRecovery = false; throw IOException("Readback unavailable") }
                assertEquals(directoryScope(account.bareJid.value, peer, kind), requested)
                ThreadDirectorySnapshot(account.bareJid.value, "example.org", directory, "snapshot", rows.toList())
            }, write = { action ->
                assertEquals(directory, action.context.scope)
                assertEquals(action.operationId.toString(), store.sharedThreads.intent(account.id.value, peer, kind)?.operationId)
                mutations += action
                if (mutations.size == 1) {
                    val row = ThreadDirectoryItem(action.threadId, requireNotNull(action.title), action.revision + 1, false, true)
                    rows.clear(); rows += row
                    // Remote state committed, but no response reaches the real runtime.
                    throw IOException("Applied; response lost")
                }
                assertEquals("Retry preserves all stored arguments, including original UUID and MUC incarnation", mutations.first(), action)
                if (holding) { entered.complete(Unit); release.await() }
                if (failRecovery) { failRecovery = false; throw IOException("Retry response lost again") }
                ThreadDirectoryMutationResult(account.bareJid.value, "example.org", directory, "snapshot", 1,
                    action.operationId, true, rows.single())
            })
        }
        runtime = SessionRuntime(accounts, credentials, store, PeerIdentityStore(db.messageDao()), scope, factory)
        presenter = DirectChatPresenter(account, repository, scope, runtime::enqueueDirect,
            directoryConnection = runtime.state, refreshDirectory = runtime::refreshThreadDirectory,
            changeDirectory = runtime::changeThreadDirectory, keepDirectory = runtime::keepCurrentThreadDirectory)
        runtime.connectActive()
        withTimeout(5_000) { runtime.state.first { it is ConnectionState.Connected } }
        presenter.selectPeer(peer)
        suspend fun ready(selectedPeer: String = peer) = withTimeout(5_000) { presenter.state.first {
            it.selectedPeer == selectedPeer && it.contentStatus == ChatContentStatus.Ready
        } }
        val originalRoute = ready().routeOccurrence
        var expectedRoute = originalRoute
        withTimeout(5_000) { presenter.directoryState.first { it.writable } }
        if (rename) withTimeout(5_000) { presenter.state.first { it.recentThreads.any { row -> row.title == "Original" } } }
        compose.setContent {
            val state by presenter.state.collectAsState()
            val view by presenter.directoryState.collectAsState()
            MaterialTheme {
                ConversationContent(state = state, connectionStatus = "Connected", onSelectPeer = presenter::selectPeer,
                    onCloseConversation = presenter::closeConversation, onCloseThread = presenter::closeThread,
                    onDraftChange = { CompletableDeferred(true) }, onSend = { CompletableDeferred(false) },
                    onSelectThreadDestination = presenter::selectThreadDestination,
                    onCreateSharedNamedThread = { origin, title, authored ->
                        presenter.createSharedNamedThread(origin, title, authored).also { submissions += it }
                    }, onRenameNamedThread = { origin, target, title, authored ->
                        presenter.renameNamedThread(origin, target, title, authored).also { submissions += it }
                    }, onRefreshNamedThreads = {
                        presenter.refreshNamedThreads(it)
                        refreshes += presenter.directoryState.value
                    },
                    onRetryDirectoryChange = { presenter.retryDirectoryChange(it).also { result -> recoveries += result } },
                    onKeepCurrentDirectory = { presenter.keepCurrentDirectory(it).also { result -> recoveries += result } },
                    directoryView = view)
            }
        }
        compose.onNodeWithTag("thread-switcher").performClick()
        if (rename) compose.onNodeWithContentDescription("Rename Original for everyone").performClick()
        else compose.onNodeWithText("New thread").performClick()
        compose.onNodeWithTag("thread-name-input").performTextReplacement("Recovered project")
        compose.onNodeWithText(if (rename) "Save name" else "Create thread").performClick()
        compose.waitUntil(5_000) { submissions.size == 1 }
        assertEquals(listOf(false), submissions.toList())
        withTimeout(5_000) { presenter.directoryState.first { it.mode == DirectoryMode.UNCERTAIN } }
        compose.onNodeWithTag("thread-name-input").assertTextContains("Recovered project")
        val saveFailure = hasText("Shared change not confirmed. Use saved-operation retry.") or hasText("Not saved. Try again.")
        compose.onNode(saveFailure).assertExists()
        val intent = requireNotNull(store.sharedThreads.intent(account.id.value, peer, kind))
        assertEquals(listOf(intent.operationId), intentIds())
        assertEquals(1, mutations.size)
        assertEquals(1, rows.size)
        assertEquals(if (rename) 1L else 0L, intent.revision)
        assertEquals("Recovered project", intent.title)
        assertEquals(intent.action(account.bareJid.value), mutations.single())
        if (failRefreshOnce) {
            failRead = true
            compose.onNodeWithText("Refresh threads").performClick()
            compose.waitUntil(5_000) { refreshes.size == 1 }
            assertEquals(DirectoryMode.UNCERTAIN, refreshes.single().mode)
            assertEquals("Failed read must retain exact recovery ownership", intent.operationId, refreshes.single().pendingOperation)
            compose.onNodeWithTag("thread-name-input").assertTextContains("Recovered project")
        }
        // A read projects the applied row but must not silently consume uncertain authoring.
        if (keep || rename || replacement != null) {
            compose.onNodeWithText("Refresh threads").performClick()
            withTimeout(5_000) { presenter.directoryState.first { it.pendingOperation == intent.operationId } }
            withTimeout(5_000) { presenter.state.first { it.recentThreads.any { row -> row.title == "Recovered project" } } }
            compose.onNodeWithTag("thread-name-input").assertTextContains("Recovered project")
        }
        if (replacement == "edit") compose.onNodeWithTag("thread-name-input").performTextReplacement("Newer input")
        if (replacement == "form") {
            // Private forms remain available while the unrelated shared intent is uncertain.
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Rename A private alias locally").performScrollTo().performClick()
            compose.onNodeWithTag("thread-name-input").performTextReplacement("Newer input")
        }
        holding = replacement == "route"
        failRecovery = failRecoveryOnce
        val recoveryButton = if (keep) "Keep current shared state" else "Retry saved shared change"
        compose.onNodeWithText(recoveryButton).performClick()
        if (failRecoveryOnce) {
            compose.waitUntil(5_000) { recoveries.size == 1 }
            assertEquals(listOf(false), recoveries.toList())
            compose.onNodeWithTag("thread-name-input").assertTextContains("Recovered project")
            compose.onNode(saveFailure).assertExists()
            assertEquals(intent, store.sharedThreads.intent(account.id.value, peer, kind))
            assertEquals(listOf(intent.operationId), intentIds())
            // Re-pressing the original save while uncertain is rejected, not a new operation,
            // and must not detach the form from its existing saved-operation recovery.
            compose.onNodeWithText(if (rename) "Save name" else "Create thread").performClick()
            compose.onNodeWithTag("thread-name-input").assertTextContains("Recovered project")
            assertEquals(listOf(intent.operationId), intentIds())
            compose.onNodeWithText(recoveryButton).performClick()
        }
        if (holding) {
            compose.waitUntil(5_000) { entered.isCompleted }
            // Change occurrence while the runtime owns the held old operation.
            presenter.closeConversation()
            withTimeout(5_000) { presenter.state.first { it.selectedPeer == null } }
            presenter.selectPeer(peer)
            val replacementRoute = ready().routeOccurrence
            assertNotEquals(originalRoute, replacementRoute)
            expectedRoute = replacementRoute
            compose.onNodeWithTag("thread-switcher").performClick()
            compose.onNodeWithContentDescription("Rename A private alias locally").performScrollTo().performClick()
            compose.onNodeWithTag("thread-name-input").performTextReplacement("Newer input")
            release.complete(Unit)
        }
        compose.waitUntil(5_000) { recoveries.size == if (failRecoveryOnce) 2 else 1 }
        assertEquals(if (failRecoveryOnce) listOf(false, true) else listOf(true), recoveries.toList())
        assertNull(store.sharedThreads.intent(account.id.value, peer, kind))
        assertEquals(listOf(intent.operationId), intentIds())
        assertEquals(if (keep) 1 else if (failRecoveryOnce) 3 else 2, mutations.size)
        assertEquals(1, db.sharedThreadDao().rows(account.id.value, peer, kind).size)
        assertEquals(1, rows.size)
        assertNull(presenter.state.value.selectedThread)
        assertEquals(expectedRoute, presenter.state.value.routeOccurrence)
        if (replacement != null) {
            compose.onNodeWithTag("thread-name-input").assertTextContains("Newer input")
        } else {
            compose.onNodeWithTag("thread-name-input").assertDoesNotExist()
            compose.onNodeWithText("Create thread").assertDoesNotExist()
            compose.onNodeWithText("Save name").assertDoesNotExist()
            compose.onNode(saveFailure).assertDoesNotExist()
            compose.onNodeWithText(if (keep) "Kept current shared state." else "Shared change confirmed.").assertExists()
            // Confirmation leaves a destination and requires an explicit New thread action.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("thread-destination-${intent.threadId}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("thread-destination-${intent.threadId}").assertExists()
            compose.onNodeWithText("New thread").performClick()
            assertEquals("", compose.onNodeWithTag("thread-name-input").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            compose.onNode(saveFailure).assertDoesNotExist()
            assertEquals("Opening a fresh form alone cannot insert another intent", listOf(intent.operationId), intentIds())
        }
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
