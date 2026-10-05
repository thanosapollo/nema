package org.thanosapollo.nema.ui.chat

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
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
import org.robolectric.annotation.GraphicsMode
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.*
import org.thanosapollo.nema.createRobolectricComposeRule
import org.thanosapollo.nema.credentials.*
import org.thanosapollo.nema.service.SessionRuntime
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.*

/** Production Compose -> presenter -> runtime -> Room -> dispatcher; no real network. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UncertainRetryJourneyTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val databaseName = "uncertain-retry-${UUID.randomUUID()}.db"
    private lateinit var db: NemaDatabase
    private lateinit var runtime: SessionRuntime
    private lateinit var presenter: DirectChatPresenter
    private lateinit var store: MessageStore
    private lateinit var account: AccountConfiguration
    private lateinit var intent: OutboundIntent
    private lateinit var key: RetryUncertainKey
    private val sent = CopyOnWriteArrayList<OutgoingMessageEnvelope>()
    private val results = CopyOnWriteArrayList<Boolean>()
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })
    private val enteredRetry = CompletableDeferred<Unit>()
    private val releaseRetry = CompletableDeferred<Unit>()
    private var holdRetry = false

    @After fun cleanup() = runBlocking {
        releaseRetry.complete(Unit)
        if (::presenter.isInitialized) presenter.close()
        if (::runtime.isInitialized) runtime.stop()
        scope.coroutineContext[Job]?.cancelAndJoin()
        if (::db.isInitialized) db.close()
        context.deleteDatabase(databaseName)
        assertTrue("Background failures: $failures", failures.isEmpty())
    }

    private fun setup() = runBlocking {
        db = NemaDatabase.create(context, databaseName)
        account = AccountConfiguration.create(AccountId.require("retry-account"), SELF, "alice", null, "example.org", null)
        val accounts = AccountRepository(db.accountDao())
        accounts.save(account)
        accounts.activate(account.id)
        val credentials = CredentialVault(MemoryBlobs(), TestCipher())
        credentials.store(account.id, "fixture".toCharArray())
        store = MessageStore(db)
        intent = OutboundIntent(account.id.value, "original-operation", "original-local", "original-origin",
            PEER, SELF, MessageKind.CHAT, null, null, "Lost send")
        store.compose(intent)
        store.recordPotentialDelivery(requireNotNull(store.claim(account.id.value, intent.operationId, 19)))
        key = RetryUncertainKey(account.id.value, intent.operationId, 19, 1)
        val factory = SessionConnectionFactory { _, _, _ -> RecordingConnection(sent) }
        runtime = SessionRuntime(accounts, credentials, store, PeerIdentityStore(db.messageDao()), scope, factory)
        presenter = DirectChatPresenter(account, ChatRepository(db), scope, runtime::enqueueDirect,
            retry = { capturedAccount, capturedKey ->
                enteredRetry.complete(Unit)
                if (holdRetry) releaseRetry.await()
                runtime.retryUncertain(capturedAccount, capturedKey).also { results += it }
            })
        runtime.connectActive()
        withTimeout(5_000) { runtime.state.first { it is ConnectionState.Connected } }
        presenter.selectPeer(PEER)
        withTimeout(5_000) { presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.messages.singleOrNull()?.retryUncertainKey == key } }
        compose.setContent {
            val state by presenter.state.collectAsState()
            MaterialTheme {
                ConversationContent(state = state, connectionStatus = "Connected",
                    onSelectPeer = presenter::selectPeer, onCloseConversation = presenter::closeConversation,
                    onDraftChange = presenter::updateDraft, onSend = presenter::sendDraft,
                    onRetryUncertain = presenter::retryUncertain)
            }
        }
        // Reconnection and rendering never blindly resend an uncertain operation.
        assertTrue(sent.isEmpty())
    }

    private fun openConfirmation() {
        compose.onNodeWithTag("message-bubble-original-local").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Retry send…").assertIsDisplayed().performClick()
        compose.onNodeWithText("The recipient may already have this message. Retrying can create a duplicate.").assertIsDisplayed()
        compose.onNodeWithTag("confirm-retry-send").assertIsEnabled()
        assertTrue(results.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test fun successfulRetryPreservesIdentityAndSingleLocalBubble() = runBlocking {
        setup()
        openConfirmation()
        compose.onNodeWithTag("confirm-retry-send").performClick()
        compose.waitUntil(5_000) { results.size == 1 && sent.size == 1 }
        compose.onNodeWithText("Retry queued").assertIsDisplayed()
        compose.onNodeWithTag("confirm-retry-send").assertIsNotEnabled()
        assertEquals(listOf(true), results.toList())
        val envelope = sent.single()
        assertEquals(intent.operationId, envelope.operationId)
        assertEquals(intent.originId, envelope.originId)
        assertEquals(intent.body, envelope.body)
        assertEquals(2, envelope.attempt)
        assertEquals(listOf(intent.localMessageId), store.messages(account.id.value).map { it.localMessageId })
        compose.onAllNodesWithTag("message-bubble-original-local").assertCountEquals(1)
        assertFalse(runtime.retryUncertain(account, key))
        assertEquals(1, sent.size)
    }

    @Test fun leavingConversationDismissesCapturedRetryWithoutSending() = runBlocking {
        setup()
        openConfirmation()
        presenter.closeConversation()
        compose.waitUntil(5_000) { presenter.state.value.selectedPeer == null }
        // Presenter publication precedes Compose collection and exit animation.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("retry-send-confirmation").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("retry-send-confirmation").assertDoesNotExist()
        assertFalse(presenter.retryUncertain(key))
        assertTrue(sent.isEmpty())
        assertTrue(results.isEmpty())
    }

    @Test fun cancelDoesNotQueueOrSend() = runBlocking {
        setup()
        openConfirmation()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("retry-send-confirmation").assertDoesNotExist()
        assertEquals(OutboxStatus.UNCERTAIN, store.outbox(account.id.value, intent.operationId)?.status)
        assertTrue(sent.isEmpty())
        assertTrue(results.isEmpty())
    }

    @Test fun receiptWhileConfirmationOpenDisablesRetry() = evidenceDisablesRetry("receipt")
    @Test fun archiveMatchWhileConfirmationOpenDisablesRetry() = evidenceDisablesRetry("archive")
    @Test fun serverAcknowledgementWhileConfirmationOpenDisablesRetry() = evidenceDisablesRetry("ack")

    private fun evidenceDisablesRetry(kind: String) = runBlocking {
        setup()
        openConfirmation()
        applyEvidence(kind)
        compose.waitUntil(5_000) { presenter.state.value.messages.single().retryUncertainKey == null }
        compose.onNodeWithTag("confirm-retry-send").assertIsNotEnabled().performClick()
        compose.onNodeWithText("This message is no longer eligible for retry.").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("message-bubble-original-local").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Retry send…").assertDoesNotExist()
        assertFalse(runtime.retryUncertain(account, key))
        assertTrue(sent.isEmpty())
        assertTrue(results.isEmpty())
        assertEquals(1, store.messages(account.id.value).size)
    }

    @Test fun receiptAfterPresenterAdmissionStillSuppressesRuntimeRequeue() = evidenceAtRuntimeAdmission("receipt")
    @Test fun archiveAfterPresenterAdmissionStillSuppressesRuntimeRequeue() = evidenceAtRuntimeAdmission("archive")
    @Test fun serverAckAfterPresenterAdmissionStillSuppressesRuntimeRequeue() = evidenceAtRuntimeAdmission("ack")

    private fun evidenceAtRuntimeAdmission(kind: String) = runBlocking {
        setup()
        holdRetry = true
        openConfirmation()
        compose.onNodeWithTag("confirm-retry-send").performClick()
        compose.waitUntil(5_000) { enteredRetry.isCompleted }
        compose.onNodeWithTag("confirm-retry-send").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        applyEvidence(kind)
        releaseRetry.complete(Unit)
        compose.waitUntil(5_000) { results.size == 1 }
        assertEquals(listOf(false), results.toList())
        compose.onNodeWithText("Retry was not queued. The message or account may have changed.").assertIsDisplayed()
        assertTrue(sent.isEmpty())
        assertEquals(1, store.messages(account.id.value).size)
    }

    @Test fun switchedAccountRefusesCapturedConfirmationInsideStorageAdmission() = runBlocking {
        setup()
        holdRetry = true
        openConfirmation()
        compose.onNodeWithTag("confirm-retry-send").performClick()
        compose.waitUntil(5_000) { enteredRetry.isCompleted }
        val other = AccountConfiguration.create(AccountId.require("other-account"), "other@example.org", "other", null, "example.org", null)
        val accounts = AccountRepository(db.accountDao())
        accounts.save(other)
        accounts.switchActive(other.id)
        releaseRetry.complete(Unit)
        compose.waitUntil(5_000) { results.size == 1 }
        assertEquals(listOf(false), results.toList())
        assertTrue(sent.isEmpty())
        assertEquals(OutboxStatus.UNCERTAIN, store.outbox(account.id.value, intent.operationId)?.status)
    }

    @Test fun staleGenerationAttemptAndAccountCannotReachRetry() = runBlocking {
        setup()
        assertFalse(presenter.retryUncertain(key.copy(generation = 18)))
        assertFalse(presenter.retryUncertain(key.copy(attempt = 2)))
        assertFalse(presenter.retryUncertain(key.copy(accountId = "other-account")))
        assertFalse(runtime.retryUncertain(account, key.copy(generation = 18)))
        assertFalse(runtime.retryUncertain(account, key.copy(attempt = 2)))
        assertFalse(runtime.retryUncertain(account, key.copy(accountId = "other-account")))
        assertTrue(results.isEmpty())
        assertTrue(sent.isEmpty())
    }

    private suspend fun applyEvidence(kind: String) {
        when (kind) {
            "receipt" -> assertNotNull(store.recordReceiptSignal(account.id.value, PEER, PEER, intent.operationId, MessageReceiptStage.RECEIVED))
            "archive" -> {
                val archiveKey = ArchiveCursorKey(account.id.value, "example.org", "account")
                val original = store.messages(account.id.value).single()
                val incoming = IncomingMessage(account.id.value, "archive-copy", PEER, SELF, MessageDirection.OUTBOUND,
                    MessageKind.CHAT, original.threadId, original.parentThreadId, intent.body, null,
                    listOf(TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, MessageStore.OUTBOUND_ORIGIN_AUTHORITY, intent.originId)))
                assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(ArchivePage(
                    key = archiveKey, direction = ArchiveDirection.BOOTSTRAP, boundaryId = null,
                    complete = true, hasEarlier = false, stable = true, firstId = "archive-result", lastId = "archive-result",
                    messages = listOf(ArchivedIncomingMessage("archive-result", incoming)),
                )).status)
                assertEquals(OutboxStatus.CONFIRMED, store.outbox(account.id.value, intent.operationId)?.status)
            }
            "ack" -> {
                // SM is not enabled. Exercise its already-modelled durable positive state,
                // without fabricating a network acknowledgement or introducing an SM API.
                val row = requireNotNull(store.outbox(account.id.value, intent.operationId))
                db.messageDao().updateOutbox(row.copy(status = OutboxStatus.ACKNOWLEDGED))
            }
            else -> error("Unknown fixture")
        }
    }

    private class RecordingConnection(private val sent: MutableList<OutgoingMessageEnvelope>) : SessionConnection {
        override var isUsable = false
        override fun revoke() { isUsable = false }
        override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) { isUsable = true }
        override suspend fun reconnect(attempt: SessionAttemptIdentity) { isUsable = true }
        override fun updateAttempt(attempt: SessionAttemptIdentity) = Unit
        override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) { entered(); sent += message }
        override suspend fun disconnect() { isUsable = false }
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
    companion object {
        private const val SELF = "alice@example.org"
        private const val PEER = "bob@example.org"
    }
}
