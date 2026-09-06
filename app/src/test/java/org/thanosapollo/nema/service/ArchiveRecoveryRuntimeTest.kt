package org.thanosapollo.nema.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.ArchiveFailureKind
import org.thanosapollo.nema.chat.ArchiveSyncState
import org.thanosapollo.nema.credentials.CredentialBlobStore
import org.thanosapollo.nema.credentials.CredentialCipher
import org.thanosapollo.nema.credentials.CredentialVault
import org.thanosapollo.nema.credentials.WrappedCredential
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.storage.*
import org.thanosapollo.nema.xmpp.smack.archiveNetworkCall
import org.thanosapollo.nema.xmpp.transport.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArchiveRecoveryRuntimeTest {
    private lateinit var context: Context
    private lateinit var database: NemaDatabase
    private lateinit var name: String
    private lateinit var runtime: SessionRuntime
    private lateinit var store: MessageStore
    private lateinit var accounts: AccountRepository
    private lateinit var credentials: CredentialVault
    private val connections = mutableListOf<Connection>()
    private val requests = mutableListOf<ArchivePageRequest>()
    private var discover: () -> SessionCapabilities = { CAPABILITIES }
    private var query: suspend (ArchivePageRequest) -> ArchivePageEnvelope = { page(it, complete = true) }
    private var writeObserver: (MessageWriteBoundary) -> Unit = {}
    private var connectCalls = 0
    private var reconnectCalls = 0
    private var sendCalls = 0
    private var onRevoke: (Connection) -> Unit = {}

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        name = "mam-recovery-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, name)
        store = MessageStore.observingWrites(database, observer = { writeObserver(it) })
        accounts = AccountRepository(database.accountDao())
        val blobs = object : CredentialBlobStore {
            private val values = mutableMapOf<AccountId, WrappedCredential>()
            override fun read(accountId: AccountId) = values[accountId]
            override fun write(accountId: AccountId, credential: WrappedCredential) { values[accountId] = credential }
            override fun delete(accountId: AccountId) { values.remove(accountId) }
        }
        credentials = CredentialVault(blobs, object : CredentialCipher {
            override fun encrypt(accountId: AccountId, plaintext: ByteArray) = WrappedCredential(byteArrayOf(1), plaintext.copyOf())
            override fun decrypt(accountId: AccountId, credential: WrappedCredential) = credential.ciphertext.copyOf()
            override fun deleteKey(accountId: AccountId) = Unit
        })
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    // Retire the runtime while its original scheduler and background scope are still live.
    private fun runTest(block: suspend TestScope.() -> Unit) = kotlinx.coroutines.test.runTest {
        try { block() } finally { if (::runtime.isInitialized) runtime.stop() }
    }

    @Test fun `partial progress does not renew automatic retry budget and manual request is one shot`() = runTest {
        query = { request ->
            if (requests.size % 2 == 0) timeout()
            page(request, complete = false, ids = listOf("r${requests.size}"))
        }
        start()
        for ((index, delay) in listOf(2_000L, 5_000L, 15_000L).withIndex()) {
            val waiting = runtime.archiveState.first { it is ArchiveSyncState.WaitingToRetry && it.attempt == index + 1 }
                as ArchiveSyncState.WaitingToRetry
            assertEquals(delay, waiting.delayMs)
            runCurrent()
            advanceTimeBy(delay - 1)
            runCurrent()
            assertEquals((index + 1) * 2, requests.size)
            advanceTimeBy(1)
            runCurrent()
        }
        val exhausted = runtime.archiveState.first { it is ArchiveSyncState.Incomplete } as ArchiveSyncState.Incomplete
        assertEquals(ArchiveFailureKind.TRANSIENT, exhausted.error.kind)
        assertEquals(8, requests.size)
        assertEquals("r7", cursor()?.newestId)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(8, requests.size)
        assertTrue(runtime.retryArchive(exhausted))
        assertFalse(runtime.retryArchive(exhausted))
        // The next cycle fails permanently with the same account/generation.
        query = { throw IllegalArgumentException("synthetic invalid response") }
        val protocol = runtime.archiveState.first { it is ArchiveSyncState.Incomplete && it !== exhausted }
            as ArchiveSyncState.Incomplete
        assertEquals(ArchiveFailureKind.PROTOCOL, protocol.error.kind)
        assertFalse(runtime.retryArchive(exhausted))
        query = { page(it, complete = true, ids = listOf("r9")) }
        assertTrue(runtime.retryArchive(protocol))
        runtime.archiveState.first { it is ArchiveSyncState.Ready }
        assertEquals("r7", requests.last().boundaryId)
        assertEquals("r9", cursor()?.newestId)
        assertEquals(5, store.messages("owner").size)
        assertEquals(1, connectCalls)
        assertEquals(0, reconnectCalls)
        assertEquals(0, sendCalls)
    }

    @Test fun `manual retry is already armed when actionable state is published`() = runTest {
        query = { throw IllegalArgumentException("synthetic invalid response") }
        start()
        val admission = backgroundScope.async(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            val state = runtime.archiveState.first { it is ArchiveSyncState.Incomplete }
            query = { page(it, complete = true) }
            runtime.retryArchive(state)
        }
        assertTrue("Publication must not precede manual admission", admission.await())
        runtime.archiveState.first { it is ArchiveSyncState.Ready }
        assertEquals(2, requests.size)
    }

    @Test fun `account switch retires a still pending old owner retry`() = runTest {
        val replacement = saveAccount("replacement")
        query = { timeout() }
        start()
        val waiting = runtime.archiveState.first { it is ArchiveSyncState.WaitingToRetry }
        runCurrent()
        val oldConnection = connections.single()
        val old = oldConnection.attempt
        val waitingTime = testScheduler.currentTime
        var retirement: Triple<ArchiveSyncState, Long, List<ArchivePageRequest>>? = null
        onRevoke = { connection ->
            if (connection === oldConnection) {
                retirement = Triple(runtime.archiveState.value, testScheduler.currentTime, requests.toList())
            }
        }
        query = { if (it.accountId == replacement.id) page(it, complete = true, ids = listOf("new")) else timeout() }
        val activation = async { runtime.activate(replacement.id) }
        // Keep the test runnable while Room/credential IO completes, so runTest cannot
        // auto-advance through the old timer before the actual revocation boundary.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!activation.isCompleted && System.nanoTime() < deadline) yield()
        assertTrue("Activation did not finish without advancing virtual time", activation.isCompleted)
        assertEquals(ConnectionCommandOutcome.RUNNING, activation.await())
        val retired = requireNotNull(retirement)
        assertSame(waiting, retired.first)
        assertEquals(waitingTime, retired.second)
        assertEquals(1, retired.third.size)
        assertEquals(old.generation, retired.third.single().generation)
        runtime.archiveState.first { it is ArchiveSyncState.Ready && it.identity.accountId == replacement.id }
        val queriesAtReplacement = requests.toList()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(queriesAtReplacement, requests)
        assertEquals(1, requests.count { it.accountId == replacement.id })
        assertTrue(requests.filter { it.accountId == old.accountId }.all { it.generation == old.generation })
        assertTrue(store.messages("owner").isEmpty())
        assertEquals(1, store.messages("replacement").size)
    }

    @Test fun `stop during delay retires timer and same-account successor does not accept old retry`() = runTest {
        query = { timeout() }
        start()
        runtime.archiveState.first { it is ArchiveSyncState.WaitingToRetry }
        runCurrent()
        runtime.stop()
        runtime.archiveState.first { it is ArchiveSyncState.Idle }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, requests.size)
        query = { throw IllegalArgumentException("invalid") }
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val old = runtime.archiveState.first { it is ArchiveSyncState.Incomplete }
        runtime.stop()
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val successor = runtime.archiveState.first { it is ArchiveSyncState.Incomplete && it !== old }
        assertFalse(runtime.retryArchive(old))
        assertTrue(runtime.retryArchive(successor))
    }

    @Test fun `late noncancellable response after stop cannot commit cursor rows or status`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        query = { request ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                page(request, complete = true, ids = listOf("stale"))
            }
        }
        start()
        entered.await()
        val stopping = async { runtime.stop() }
        runtime.state.first { it is ConnectionState.Stopped }
        release.complete(Unit)
        stopping.await()
        runtime.archiveState.first { it is ArchiveSyncState.Idle }
        assertTrue(store.messages("owner").isEmpty())
        assertNull(cursor())
        assertEquals(1, requests.size)
    }

    @Test fun `connection loss during archive backoff retires old generation retry`() = runTest {
        query = { timeout() }
        start()
        runtime.archiveState.first { it is ArchiveSyncState.WaitingToRetry }
        runCurrent()
        val connection = connections.single()
        val old = connection.attempt
        connection.lose()
        runtime.state.first { it is ConnectionState.ReconnectWait }
        query = { page(it, complete = true, ids = listOf("reconnected")) }
        repeat(6) {
            runCurrent()
            advanceTimeBy(1_000)
        }
        runtime.archiveState.first { it is ArchiveSyncState.Ready && it.identity.generation != old.generation }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(old.generation, connection.attempt.generation), requests.map { it.generation })
        assertEquals(1, reconnectCalls)
        assertEquals(1, connectCalls)
        assertEquals(0, sendCalls)
        assertEquals(listOf("reconnected"), store.messages("owner").map { it.body })
    }

    @Test fun `late successful page cannot overwrite connected replacement archive`() = runTest {
        lateQueryAfterReplacement(fail = false)
    }

    @Test fun `late failed page cannot schedule retry or overwrite connected replacement archive`() = runTest {
        lateQueryAfterReplacement(fail = true)
    }

    private suspend fun TestScope.lateQueryAfterReplacement(fail: Boolean) {
        val replacement = saveAccount("replacement")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        query = { request ->
            if (request.accountId.value == "owner") withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                if (fail) timeout() else page(request, complete = true, ids = listOf("stale"))
            } else page(request, complete = true, ids = listOf("replacement"))
        }
        start()
        entered.await()
        val publications = mutableListOf<ArchiveSyncState>()
        try {
            assertEquals(ConnectionCommandOutcome.RUNNING, runtime.activate(replacement.id))
            assertEquals(replacement.id, (runtime.state.value as ConnectionState.Connected).accountId)
            backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
                runtime.archiveState.collect { publications += it }
            }
        } finally { release.complete(Unit) }
        val ready = runtime.archiveState.first { it is ArchiveSyncState.Ready && it.identity.accountId == replacement.id }
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertSame(ready, runtime.archiveState.value)
        assertTrue(publications.none {
            it is ArchiveSyncState.RetryableError || it is ArchiveSyncState.WaitingToRetry ||
                it is ArchiveSyncState.Incomplete ||
                (it is ArchiveSyncState.Ready && it.identity.accountId.value == "owner")
        })
        assertTrue(store.messages("owner").isEmpty())
        assertNull(cursor())
        assertEquals(listOf("replacement"), store.messages("replacement").map { it.body })
        assertEquals("replacement", store.archiveCursor(ArchiveCursorKey("replacement", "replacement@example.org", "ACCOUNT"))?.newestId)
        assertEquals(listOf("owner", "replacement"), requests.map { it.accountId.value })
    }

    @Test fun `page budget is explicit continuation from committed cursor not automatic failure retry`() = runTest {
        query = { page(it, complete = false, ids = listOf("r${requests.size}")) }
        start()
        val continuation = runtime.archiveState.first { it is ArchiveSyncState.ContinuationRequired }
        assertEquals(100, requests.size)
        assertEquals("r100", cursor()?.newestId)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(100, requests.size)
        query = { page(it, complete = true) }
        assertTrue(runtime.retryArchive(continuation))
        assertFalse(runtime.retryArchive(continuation))
        runtime.archiveState.first { it is ArchiveSyncState.Ready }
        assertEquals("r100", requests.last().boundaryId)
        assertEquals(100, store.messages("owner").size)
    }

    @Test fun `nonadvancing incomplete page stops but complete empty page succeeds on manual recovery`() = runTest {
        query = { page(it, complete = false) }
        start()
        val incomplete = runtime.archiveState.first { it is ArchiveSyncState.Incomplete } as ArchiveSyncState.Incomplete
        assertEquals(ArchiveFailureKind.PROTOCOL, incomplete.error.kind)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, requests.size)
        assertNull(cursor()?.newestId)
        query = { page(it, complete = true) }
        assertTrue(runtime.retryArchive(incomplete))
        runtime.archiveState.first { it is ArchiveSyncState.Ready }
        assertTrue(store.messages("owner").isEmpty())
    }

    @Test fun `storage fault rolls back archive page and terminates rather than retrying network`() = runTest {
        query = { page(it, complete = true, ids = listOf("uncommitted")) }
        writeObserver = { if (it == MessageWriteBoundary.AFTER_MESSAGE) throw IllegalStateException("synthetic disk failure") }
        start()
        val failure = runtime.state.first { it is ConnectionState.Failed } as ConnectionState.Failed
        assertEquals(SessionFailureReason.LOCAL_STORAGE, failure.reason)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, requests.size)
        assertNull(cursor())
        assertTrue(store.messages("owner").isEmpty())
        assertEquals(0, reconnectCalls)
    }

    @Test fun `scope mismatch repeated boundary and missing cursor require manual recovery without cursor reset`() = runTest {
        query = { request ->
            if (requests.size == 1) page(request, complete = false, ids = listOf("r1"))
            else page(request.copy(scope = "wrong"), complete = true, ids = listOf("r2"))
        }
        start()
        var previous: ArchiveSyncState? = null
        for (next in listOf<suspend (ArchivePageRequest) -> ArchivePageEnvelope>(
            { page(it, complete = false, ids = listOf("r1")) },
            { throw org.jivesoftware.smack.XMPPException.XMPPErrorException(null,
                org.jivesoftware.smack.packet.StanzaError.getBuilder(org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found).build()) },
            { page(it, complete = true) },
        )) {
            val incomplete = runtime.archiveState.first { it is ArchiveSyncState.Incomplete && it !== previous }
                as ArchiveSyncState.Incomplete
            assertEquals(ArchiveFailureKind.PROTOCOL, incomplete.error.kind)
            assertEquals("r1", cursor()?.newestId)
            val before = requests.size
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(before, requests.size)
            previous = incomplete
            query = next
            assertTrue(runtime.retryArchive(incomplete))
        }
        runtime.archiveState.first { it is ArchiveSyncState.Ready }
        assertEquals(listOf(null, "r1", "r1", "r1", "r1"), requests.map { it.boundaryId })
        assertEquals(listOf("r1"), store.messages("owner").map { it.body })
    }

    @Test fun `capability timeout retries but unsupported MAM does not query or offer manual retry`() = runTest {
        var discoveries = 0
        discover = { if (++discoveries == 1) timeout() else CAPABILITIES.copy(mamV2 = false) }
        start()
        runtime.archiveState.first { it is ArchiveSyncState.WaitingToRetry }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        val unsupported = runtime.archiveState.first { it is ArchiveSyncState.Unsupported }
        assertFalse(runtime.retryArchive(unsupported))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, discoveries)
        assertTrue(requests.isEmpty())
        assertTrue(runtime.state.value is ConnectionState.Connected)
    }

    private suspend fun TestScope.start() {
        runtime = SessionRuntime(accounts, credentials, store, PeerIdentityStore(database.messageDao()), backgroundScope,
            SessionConnectionFactory { _, _, event -> Connection(event).also { connections += it } })
        accounts.activate(saveAccount("owner").id)
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
    }

    private suspend fun saveAccount(id: String): AccountConfiguration {
        val account = AccountConfiguration.create(AccountId.require(id), "$id@example.org", id, null, "example.org", null)
        accounts.save(account)
        credentials.store(account.id, "secret".toCharArray())
        return account
    }

    private suspend fun cursor() = store.archiveCursor(ArchiveCursorKey("owner", "owner@example.org", "ACCOUNT"))

    private inner class Connection(private val event: (SessionEvent) -> Unit) : SessionConnection {
        override var isUsable = true
        lateinit var attempt: SessionAttemptIdentity
        fun lose() { isUsable = false; event(SessionEvent.ConnectionLost(attempt, SessionFailureReason.NETWORK)) }
        override fun revoke() { onRevoke(this); isUsable = false }
        override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) { connectCalls++; this.attempt = attempt; isUsable = true }
        override suspend fun reconnect(attempt: SessionAttemptIdentity) { reconnectCalls++; this.attempt = attempt; isUsable = true }
        override fun updateAttempt(attempt: SessionAttemptIdentity) { this.attempt = attempt }
        override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) { sendCalls++; entered() }
        override suspend fun disconnect() { isUsable = false }
        override suspend fun discoverCapabilities(accountId: AccountId, generation: ConnectionGeneration) = discover()
        override suspend fun queryArchive(request: ArchivePageRequest): ArchivePageEnvelope {
            requests += request
            return query(request)
        }
    }

    private fun timeout(): Nothing = archiveNetworkCall {
        throw org.jivesoftware.smack.SmackException.NoResponseException.newWith(
            5_000L, org.jivesoftware.smack.filter.StanzaFilter { true }, false,
        )
    }

    private fun page(request: ArchivePageRequest, complete: Boolean, ids: List<String> = emptyList()) = ArchivePageEnvelope(
        request, stable = true, complete = complete, hasEarlier = false,
        firstId = ids.firstOrNull(), lastId = ids.lastOrNull(),
        messages = ids.map { id -> ArchiveMessageEnvelope(id, IncomingMessageEnvelope(
            request.accountId, request.generation, "peer@example.org", "peer@example.org", false,
            originId = null, body = id, thread = null, messageId = id,
            sentAtEpochMs = 2_000, sentTimeSource = MessageTimeSource.MAM,
        )) },
    )

    private companion object {
        val CAPABILITIES = SessionCapabilities(true, CarbonCapabilityState.UNSUPPORTED, false)
    }
}
