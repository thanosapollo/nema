package org.thanosapollo.nema.service

import org.thanosapollo.nema.xmpp.threads.*
import org.thanosapollo.nema.chat.ChatRepository
import org.thanosapollo.nema.chat.DirectChatPresenter
import org.thanosapollo.nema.chat.ChatContentStatus
import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.credentials.CredentialAccess
import org.thanosapollo.nema.credentials.CredentialBlobStore
import org.thanosapollo.nema.credentials.CredentialCipher
import org.thanosapollo.nema.credentials.CredentialVault
import org.thanosapollo.nema.credentials.WrappedCredential
import org.thanosapollo.nema.session.ConnectionState
import org.thanosapollo.nema.session.ConnectionAttempt
import org.thanosapollo.nema.session.LifecycleEpoch
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.session.SessionConnection
import org.thanosapollo.nema.session.SessionConnectionFactory
import org.thanosapollo.nema.session.SessionEvent
import org.thanosapollo.nema.session.SessionFailureReason
import org.thanosapollo.nema.storage.aliasAuthority
import org.thanosapollo.nema.storage.IdentityAliasStatus
import org.thanosapollo.nema.storage.AccountRepository
import org.thanosapollo.nema.storage.ArchiveCursorKey
import org.thanosapollo.nema.storage.ArchiveDirection
import org.thanosapollo.nema.storage.ArchivePage
import org.thanosapollo.nema.storage.ArchivedIncomingMessage
import org.thanosapollo.nema.storage.CompleteRosterSnapshot
import org.thanosapollo.nema.storage.DirectReactionTarget
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.IdentityAliasKind
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageReactionEntity
import org.thanosapollo.nema.storage.MessageWriteBoundary
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.OutboundIntent
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.storage.PeerIdentityStore
import org.thanosapollo.nema.storage.ReconciliationRepairStatus
import org.thanosapollo.nema.storage.RosterMember
import org.thanosapollo.nema.storage.RosterStore
import org.thanosapollo.nema.storage.TrustedIdentityAlias
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.bookmarks.RoomBookmark
import org.thanosapollo.nema.xmpp.bookmarks.RoomBookmarkSnapshot
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.IncomingReactionEnvelope
import org.thanosapollo.nema.xmpp.transport.MessageReceiptStage
import org.thanosapollo.nema.xmpp.transport.MessageSignalProtocol
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource
import org.thanosapollo.nema.xmpp.transport.OutgoingFailureEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageSignal
import org.thanosapollo.nema.xmpp.transport.OutgoingReactionEnvelope

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionRuntimeTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private var repairObserver: ((MessageWriteBoundary) -> Unit)? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "session-runtime-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private class HoldNextDispatcher(
        private val delegate: kotlinx.coroutines.CoroutineDispatcher,
    ) : kotlinx.coroutines.CoroutineDispatcher(),
        kotlinx.coroutines.Delay by (delegate as kotlinx.coroutines.Delay) {
        var holdNext = false
        var holdName: String? = null
        val captured = CompletableDeferred<Unit>()
        private var held: Pair<kotlin.coroutines.CoroutineContext, Runnable>? = null
        private val holdingCaller = ThreadLocal<Boolean>()

        fun holdLaunchFromCaller(launch: () -> Unit): Job {
            check(held == null)
            holdingCaller.set(true)
            try {
                launch()
                // launch dispatches synchronously, after reportComposer captures its lease.
                return checkNotNull(checkNotNull(held).first[Job])
            } finally {
                holdingCaller.remove()
            }
        }

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            if (holdingCaller.get() == true ||
                (holdNext && (holdName == null || context[kotlinx.coroutines.CoroutineName]?.name == holdName))) {
                holdNext = false
                check(held == null)
                held = context to block
                captured.complete(Unit)
            } else delegate.dispatch(context, block)
        }
        fun release() {
            val (context, block) = checkNotNull(held)
            held = null
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun `outbound queued composer cannot retarget A B A or reconnect`() = runTest {
        val dispatcher = HoldNextDispatcher(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val runtimeScope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val first = connectedRuntime(runtimeScope, "first") {
            testScheduler.currentTime
        }
        fun queueComposer(): Job = dispatcher.holdLaunchFromCaller {
            // A Room completion may dispatch from another thread while the gate is armed.
            // Force that ordering instead of hoping background work has drained.
            Thread { runtimeScope.launch {} }.apply {
                start()
                join(5_000)
                assertFalse("Unrelated dispatch must have returned", isAlive)
            }
            first.runtime.reportComposer("first", REACTION_PEER, true)
        }
        val queuedBeforeSwitch = queueComposer()
        assertFalse(queuedBeforeSwitch.isCompleted)
        val second = switchAccount(first, "second")
        val returned = switchAccount(second, "first")
        dispatcher.release()
        queuedBeforeSwitch.join()
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(first.connections.created.all { it.sentTyping.isEmpty() })
        val queuedBeforeReconnect = queueComposer()
        assertFalse(queuedBeforeReconnect.isCompleted)
        completeReconnect(returned, returned.connection.attemptIdentity)
        dispatcher.release()
        queuedBeforeReconnect.join()
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(first.connections.created.all { it.sentTyping.isEmpty() })
        returned.runtime.reportComposer("first", REACTION_PEER, true)
        runCurrent()
        assertEquals(listOf(org.thanosapollo.nema.xmpp.chatstates.ChatActivity.COMPOSING),
            returned.connection.sentTyping.map { it.activity })
        first.runtime.serviceDestroyed()
    }

    @Test
    fun `outbound pause retirement resets throttle and rejects old account callback`() = runTest {
        var now = 0L
        val first = connectedRuntime(backgroundScope, "first") { now }
        first.runtime.reportComposer("first", REACTION_PEER, true)
        runCurrent()
        assertEquals(1, first.connection.sentTyping.size)
        val second = switchAccount(first, "second")
        first.runtime.reportComposer("first", "stale@example.org", true)
        second.runtime.reportComposer("second", REACTION_PEER, true)
        runCurrent()
        assertEquals(listOf(REACTION_PEER), second.connection.sentTyping.map { it.recipient })
        assertEquals(org.thanosapollo.nema.xmpp.chatstates.ChatActivity.COMPOSING,
            second.connection.sentTyping.single().activity)
        val returned = switchAccount(second, "first")
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(returned.connection.sentTyping.isEmpty())
        returned.runtime.reportComposer("first", REACTION_PEER, true)
        runCurrent()
        now = org.thanosapollo.nema.xmpp.chatstates.OUTBOUND_COMPOSING_PAUSE_MS
        advanceTimeBy(org.thanosapollo.nema.xmpp.chatstates.OUTBOUND_COMPOSING_PAUSE_MS)
        runCurrent()
        assertEquals(listOf(org.thanosapollo.nema.xmpp.chatstates.ChatActivity.COMPOSING,
            org.thanosapollo.nema.xmpp.chatstates.ChatActivity.PAUSED),
            returned.connection.sentTyping.map { it.activity })
        first.runtime.serviceDestroyed()
    }

    @Test
    fun `outbound old accepted send settlement cannot cancel successor pause`() = runTest {
        val first = connectedRuntime(backgroundScope, "first") { testScheduler.currentTime }
        first.runtime.reportComposer("first", REACTION_PEER, true)
        runCurrent()
        val dispatcher = HoldNextDispatcher(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        dispatcher.holdName = "held-send"
        dispatcher.holdNext = true
        val send = async(dispatcher + kotlinx.coroutines.CoroutineName("held-send"),
            start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            first.runtime.enqueueDirect(first.account, org.thanosapollo.nema.chat.DraftSnapshot(
                org.thanosapollo.nema.chat.DirectConversationKey("first", REACTION_PEER), "body", 1,
            ))
        }
        dispatcher.captured.await()
        // The Room transaction has committed, but enqueueDirect has not resumed.
        assertEquals("body", first.store.messages("first").single().body)
        val second = switchAccount(first, "second")
        second.runtime.reportComposer("second", REACTION_PEER, true)
        runCurrent()
        dispatcher.release()
        assertTrue(send.await())
        advanceTimeBy(org.thanosapollo.nema.xmpp.chatstates.OUTBOUND_COMPOSING_PAUSE_MS)
        runCurrent()
        assertEquals(listOf(org.thanosapollo.nema.xmpp.chatstates.ChatActivity.COMPOSING,
            org.thanosapollo.nema.xmpp.chatstates.ChatActivity.PAUSED),
            second.connection.sentTyping.map { it.activity })
        first.runtime.serviceDestroyed()
    }

    @Test
    fun `ephemeral old body continuation cannot clear successor typing or RTT`() = runTest {
        val first = connectedRuntime(backgroundScope, "first")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        first.runtime.onInsertedInbound = { _, _, _, _ ->
            entered.complete(Unit)
            runBlocking { release.await() }
        }
        val old = first.connection.attemptIdentity
        val incoming = async(kotlinx.coroutines.Dispatchers.Default) {
            first.connection.emitIncoming(IncomingMessageEnvelope(
                accountId = old.accountId, generation = old.generation, peer = REACTION_PEER,
                sender = REACTION_PEER, outbound = false, originId = null, body = "body",
                thread = null, messageId = "old-body",
            ))
        }
        try {
            entered.await()
            val second = switchAccount(first, "second")
            val returned = switchAccount(second, "first")
            val fresh = returned.connection.attemptIdentity
            // The inbound gate still serializes transport callbacks behind the old body.
            // Seed the current projection directly to isolate its post-ingestion clearing boundary.
            first.runtime.chatStates.apply(org.thanosapollo.nema.xmpp.transport.IncomingChatState(
                fresh.accountId, fresh.generation, REACTION_PEER, REACTION_PEER, false,
                org.thanosapollo.nema.xmpp.chatstates.ChatActivity.COMPOSING,
            ))
            first.runtime.realTimeText.apply(org.thanosapollo.nema.xmpp.transport.IncomingRealTimeText(
                fresh.accountId, fresh.generation, REACTION_PEER,
                org.thanosapollo.nema.xmpp.rtt.RttElement(0, org.thanosapollo.nema.xmpp.rtt.RttEvent.NEW,
                    listOf(org.thanosapollo.nema.xmpp.rtt.RttAction.Insert(null, "successor"))), false,
            ))
            release.complete(Unit)
            incoming.await()
            assertEquals(listOf(REACTION_PEER), first.runtime.chatStates.observe(fresh.accountId.value, REACTION_PEER).first())
            assertEquals("successor", first.runtime.realTimeText.observe(fresh.accountId.value, REACTION_PEER).first())
            first.runtime.onInsertedInbound = null
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                returned.connection.emitIncoming(IncomingMessageEnvelope(
                    accountId = fresh.accountId, generation = fresh.generation, peer = REACTION_PEER,
                    sender = REACTION_PEER, outbound = false, originId = null, body = "current",
                    thread = null, messageId = "fresh-body",
                ))
            }
            assertEquals(emptyList<String>(), first.runtime.chatStates.observe(fresh.accountId.value, REACTION_PEER).first())
            assertNull(first.runtime.realTimeText.observe(fresh.accountId.value, REACTION_PEER).first())
        } finally {
            release.complete(Unit)
            incoming.await()
        }
    }

    @Test
    fun `ephemeral collectors retire on switch return and reconnect`() = runTest {
        val first = connectedRuntime(backgroundScope, "first")
        val runtime = first.runtime
        val typing = runtime.chatStates.observe(first.account.id.value, REACTION_PEER)
        val rtt = runtime.realTimeText.observe(first.account.id.value, REACTION_PEER)
        val room = runtime.rooms.observe(first.account.id.value, REACTION_ROOM)
        val typingValues = mutableListOf<List<String>>()
        val rttValues = mutableListOf<String?>()
        val roomValues = mutableListOf<org.thanosapollo.nema.xmpp.muc.RoomView?>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { typing.collect { typingValues += it } }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { rtt.collect { rttValues += it } }
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { room.collect { roomValues += it } }
        fun populate(connection: RecordingConnection) {
            connection.emitEphemeral()
            assertEquals(listOf(REACTION_PEER), typingValues.last())
            assertEquals("draft", rttValues.last())
            assertEquals("subject", roomValues.last()?.subject)
        }
        fun empty() {
            assertEquals(emptyList<String>(), typingValues.last())
            assertNull(rttValues.last())
            assertNull(roomValues.last())
        }
        populate(first.connection)
        val second = switchAccount(first, "second")
        empty()
        first.connection.emitEphemeral()
        empty()
        second.connection.emitEphemeral()
        empty()
        assertEquals(listOf(REACTION_PEER), runtime.chatStates.observe(second.account.id.value, REACTION_PEER).first())
        assertEquals("draft", runtime.realTimeText.observe(second.account.id.value, REACTION_PEER).first())
        val returned = switchAccount(second, "first")
        empty()
        populate(returned.connection)
        val oldAttempt = returned.connection.attemptIdentity
        completeReconnect(returned, oldAttempt, ::empty)
        empty()
        returned.connection.emitEphemeral(oldAttempt)
        empty()
        populate(returned.connection)
        runtime.stop()
        empty()
    }

    @Test
    fun `runtime saves restores switches and removes active account`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val first = account("first")
        val second = account("second")
        val firstPassword = "first-secret".toCharArray()
        val secondPassword = "second-secret".toCharArray()

        assertTrue(
            runtime.prepareActivation(
                token = runtime.beginPendingActivation(),
                configuration = first,
                credential = firstPassword,
                emitActivation = {},
            ),
        )
        assertTrue(firstPassword.all { it == '\u0000' })
        accounts.activate(first.id)
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())

        assertTrue(
            runtime.prepareActivation(
                token = runtime.beginPendingActivation(),
                configuration = second,
                credential = secondPassword,
                emitActivation = {},
            ),
        )
        assertTrue(secondPassword.all { it == '\u0000' })
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.activate(second.id))

        assertEquals(second, runtime.activeAccount.first())
        assertEquals(listOf(first, second), runtime.configuredAccounts.first())
        assertEquals(listOf(first.id, second.id), connections.created.map(RecordingConnection::accountId))
        assertEquals(1, connections.created.first().disconnectCalls)

        runtime.signOut()

        assertTrue(runtime.state.value is ConnectionState.NeedsCredentials)
        assertEquals(null, runtime.activeAccount.first())
        assertEquals(listOf(first), runtime.configuredAccounts.first())
        assertEquals(CredentialAccess.Missing, credentials.load(second.id))
        assertTrue(credentials.load(first.id) is CredentialAccess.Available)
        assertEquals(1, connections.created.last().disconnectCalls)
    }

    @Test
    fun `connect and activate repair before opening each account connection`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val observed = mutableListOf<ReconciliationRepairStatus?>()
        val connections = RecordingConnectionFactory(onCreate = { accountId ->
            observed += runBlocking {
                database.accountDao().reconciliationState(accountId.value)?.status
            }
        })
        val runtime = SessionRuntime(
            accounts,
            credentials,
            MessageStore(database),
            PeerIdentityStore(database.messageDao()),
            backgroundScope,
            connections,
        )
        val first = account("first")
        val second = account("second")
        runtime.prepareActivation(runtime.beginPendingActivation(), first, "first-secret".toCharArray()) {}
        accounts.activate(first.id)

        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        runtime.prepareActivation(runtime.beginPendingActivation(), second, "second-secret".toCharArray()) {}
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.activate(second.id))

        assertEquals(listOf(ReconciliationRepairStatus.COMPLETE, ReconciliationRepairStatus.COMPLETE), observed)
    }

    @Test
    fun `stale ownership after repair prevents connection creation`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts, credentials, MessageStore(database), PeerIdentityStore(database.messageDao()), backgroundScope, connections,
        )
        val active = account("active")
        runtime.prepareActivation(runtime.beginPendingActivation(), active, "secret".toCharArray()) {}
        accounts.activate(active.id)
        var checks = 0

        val result = runtime.connectActive { ++checks < 3 }

        assertEquals(ConnectionCommandOutcome.STALE, result)
        assertTrue(connections.created.isEmpty())
        assertEquals(ReconciliationRepairStatus.COMPLETE, database.accountDao().reconciliationState(active.id.value)?.status)
    }

    @Test
    fun `ordinary repair failure is counted and connection still opens`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val active = account("active")
        val setupStore = MessageStore(database, clock = { 1_000 })
        val connections = RecordingConnectionFactory()
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) error("repair failure")
        }
        val runtime = SessionRuntime(
            accounts, credentials, faulting, PeerIdentityStore(database.messageDao()), backgroundScope, connections,
        )
        runtime.prepareActivation(runtime.beginPendingActivation(), active, "secret".toCharArray()) {}
        accounts.activate(active.id)
        prepareDuplicate(setupStore, active.id)

        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())

        assertEquals(1, connections.created.size)
        val state = requireNotNull(database.accountDao().reconciliationState(active.id.value))
        assertEquals(ReconciliationRepairStatus.PENDING, state.status)
        assertEquals(1L, state.caughtErrorCount)
    }

    @Test
    fun `repair cancellation prevents connection and remains uncounted`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val active = account("active")
        val setupStore = MessageStore(database, clock = { 1_000 })
        val connections = RecordingConnectionFactory()
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) throw kotlinx.coroutines.CancellationException("cancel")
        }
        val runtime = SessionRuntime(
            accounts, credentials, faulting, PeerIdentityStore(database.messageDao()), backgroundScope, connections,
        )
        runtime.prepareActivation(runtime.beginPendingActivation(), active, "secret".toCharArray()) {}
        accounts.activate(active.id)
        prepareDuplicate(setupStore, active.id)

        val failure = runCatching { runtime.connectActive() }.exceptionOrNull()

        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(connections.created.isEmpty())
        val state = requireNotNull(database.accountDao().reconciliationState(active.id.value))
        assertEquals(ReconciliationRepairStatus.PENDING, state.status)
        assertEquals(0L, state.caughtErrorCount)
    }

    @Test
    fun `runtime binds stale duplicate login forms to one durable account`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = RecordingConnectionFactory(),
        )
        val first = account("first", "shared@example.org")
        val duplicate = account("duplicate", "shared@example.org")
        val activated = mutableListOf<AccountId>()

        assertTrue(
            runtime.prepareActivation(
                runtime.beginPendingActivation(),
                first,
                "first-secret".toCharArray(),
                activated::add,
            ),
        )
        assertTrue(
            runtime.prepareActivation(
                runtime.beginPendingActivation(),
                duplicate,
                "replacement-secret".toCharArray(),
                activated::add,
            ),
        )

        assertEquals(listOf(first.copy(authenticationId = duplicate.authenticationId)), runtime.configuredAccounts.first())
        assertEquals(listOf(first.id, first.id), activated)
        assertTrue(credentials.load(first.id) is CredentialAccess.Available)
        assertEquals(CredentialAccess.Missing, credentials.load(duplicate.id))
    }

    @Test
    fun `activate without credentials promotes target active and sign-out removes that target`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val first = account("first")
        val second = account("second")
        accounts.save(first)
        accounts.activate(first.id)
        credentials.store(first.id, "first-secret".toCharArray())
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())

        accounts.save(second)
        assertEquals(ConnectionCommandOutcome.NEEDS_CREDENTIALS, runtime.activate(second.id))

        assertEquals(second, runtime.activeAccount.first())
        val needsCredentials = runtime.state.value as ConnectionState.NeedsCredentials
        assertEquals(second.id, needsCredentials.accountId)
        assertEquals(1, connections.created.single().disconnectCalls)

        runtime.signOut()

        assertEquals(null, runtime.activeAccount.first())
        assertEquals(listOf(first), runtime.configuredAccounts.first())
        assertTrue(credentials.load(first.id) is CredentialAccess.Available)
        assertEquals(CredentialAccess.Missing, credentials.load(second.id))
    }

    @Test
    fun `cancelled login waiting for runtime ownership zeroes credential without persistence`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connectionStarted = CompletableDeferred<Unit>()
        val releaseConnection = CompletableDeferred<Unit>()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = RecordingConnectionFactory(connectionStarted, releaseConnection),
        )
        val active = account("active")
        accounts.save(active)
        accounts.activate(active.id)
        credentials.store(active.id, "active-secret".toCharArray())
        val connect = async { runtime.connectActive() }
        connectionStarted.await()
        val pending = account("pending")
        val pendingCredential = "pending-secret".toCharArray()
        var activations = 0

        val preparation = async {
            runtime.prepareActivation(
                runtime.beginPendingActivation(),
                pending,
                pendingCredential,
                { activations++ },
            )
        }
        runCurrent()
        preparation.cancelAndJoin()

        assertTrue(pendingCredential.all { it == '\u0000' })
        assertEquals(null, accounts.account(pending.id))
        assertEquals(CredentialAccess.Missing, credentials.load(pending.id))
        assertEquals(0, activations)

        releaseConnection.complete(Unit)
        assertEquals(ConnectionCommandOutcome.RUNNING, connect.await())
    }

    @Test
    fun `current protocol failure updates exact outgoing row without inserting a message`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val store = MessageStore(database)
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = store,
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val active = account("active")
        accounts.save(active)
        accounts.activate(active.id)
        credentials.store(active.id, "secret".toCharArray())
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val connection = connections.created.single()
        val intent = OutboundIntent(
            accountId = active.id.value,
            operationId = "operation",
            localMessageId = "local",
            originId = "origin",
            peerJid = "peer@example.org",
            senderJid = active.bareJid.value,
            messageKind = MessageKind.CHAT,
            threadId = null,
            parentThreadId = null,
            body = "body",
        )
        store.compose(intent)
        store.recordPotentialDelivery(
            requireNotNull(
                store.claim(
                    active.id.value,
                    intent.operationId,
                    connection.attemptIdentity.generation.value,
                ),
            ),
        )
        val count = store.messages(active.id.value).size

        connection.emitFailure("operation", "peer@example.org")

        assertEquals(OutboxStatus.FAILED, store.outbox(active.id.value, intent.operationId)?.status)
        assertEquals("remote-server-timeout", store.outbox(active.id.value, intent.operationId)?.failureReason)
        assertEquals(count, store.messages(active.id.value).size)
    }

    @Test
    fun `roster snapshots persist only for the current account attempt`() = runTest {
        val first = connectedRuntime(backgroundScope, "first-roster")
        val staleAttempt = first.connection.attemptIdentity
        val firstSnapshot = CompleteRosterSnapshot(
            first.account.id.value,
            listOf(RosterMember("first-peer@example.org", "First")),
        )

        first.connection.emitRoster(firstSnapshot)
        first.connection.emitRoster(firstSnapshot)
        assertEquals(
            listOf("first-peer@example.org"),
            RosterStore(database).observe(first.account.id.value).first().map { it.jid },
        )

        val second = switchAccount(first, "second-roster")
        first.connection.emitRoster(
            CompleteRosterSnapshot(first.account.id.value, emptyList()),
            staleAttempt,
        )
        second.connection.emitRoster(
            CompleteRosterSnapshot(
                second.account.id.value,
                listOf(RosterMember("second-peer@example.org", null)),
            ),
        )

        assertEquals(
            listOf("first-peer@example.org"),
            RosterStore(database).observe(first.account.id.value).first().map { it.jid },
        )
        assertEquals(
            listOf("second-peer@example.org"),
            RosterStore(database).observe(second.account.id.value).first().map { it.jid },
        )
    }

    @Test
    fun `roster storage failure revokes the connected session`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "failed-roster")
        val attempt = fixture.connection.attemptIdentity
        database.close()

        fixture.connection.emitRoster(CompleteRosterSnapshot(fixture.account.id.value, emptyList()))
        runCurrent()

        assertEquals(
            ConnectionState.Failed(fixture.account.id, attempt.generation, SessionFailureReason.LOCAL_STORAGE),
            fixture.runtime.state.value,
        )
        assertFalse(fixture.connection.isUsable)
        assertEquals(1, fixture.connection.disconnectCalls)
    }

    @Test fun `room MAM uses joined room capability without personal MAM and commits history`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "room-only")
        runCurrent()
        val room = "history@conference.example.org"
        val connection = fixture.connection
        assertFalse(connection.archiveSupported)
        connection.repairRooms += room
        connection.roomArchiveResponse = { request -> roomHistoryPage(request) }
        assertTrue(fixture.runtime.joinMuc(room))
        connection.roomArchiveJob.await().join()
        val request = connection.archiveRequests.single()
        assertEquals(room, request.archiveAuthority)
        assertEquals(room, request.scope)
        val key = ArchiveCursorKey(fixture.account.id.value, room, room)
        assertEquals("history-uid", fixture.store.archiveCursor(key)?.newestId)
        assertEquals("room history", fixture.store.messages(fixture.account.id.value).single().body)
    }

    @Test fun `personal MAM never authorizes an unsupported room`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "personal-only")
        runCurrent()
        fixture.connection.archiveSupported = true
        val room = "unsupported@conference.example.org"
        assertTrue(fixture.runtime.joinMuc(room))
        fixture.connection.published.await()
        runCurrent()
        assertTrue(fixture.connection.archiveRequests.none { it.scope == room })
        assertNull(fixture.store.archiveCursor(ArchiveCursorKey(fixture.account.id.value, room, room)))
    }

    @Test fun `room archive response cannot commit after membership or generation replacement`() = runTest {
        for (mode in listOf("membership", "rejoin", "attempt", "generation")) {
            val fixture = connectedRuntime(backgroundScope, "stale-history-$mode")
            runCurrent()
            val room = "$mode@conference.example.org"
            val connection = fixture.connection
            connection.repairRooms += room
            val release = CompletableDeferred<Unit>()
            connection.roomArchiveResponse = { request ->
                release.await()
                roomHistoryPage(request)
            }
            assertTrue(fixture.runtime.joinMuc(room))
            val job = connection.roomArchiveJob.await()
            when (mode) {
                "membership" -> connection.repairRegistry.retireAll()
                "rejoin" -> {
                    val lease = requireNotNull(connection.repairRegistry.beginJoin(connection.attemptIdentity, room))
                    connection.repairRegistry.publish(lease, false, false, mamV2 = true)
                }
                "attempt" -> connection.updateAttempt(connection.attemptIdentity.copy(attempt = ConnectionAttempt.require(99)))
                "generation" -> connection.updateAttempt(connection.attemptIdentity.copy(generation = ConnectionGeneration.require(99)))
            }
            release.complete(Unit)
            job.join()
            assertEquals(mode, 1, connection.archiveRequests.count { it.scope == room })
            assertNull(fixture.store.archiveCursor(ArchiveCursorKey(fixture.account.id.value, room, room)))
            assertTrue(fixture.store.messages(fixture.account.id.value).isEmpty())
            fixture.runtime.serviceDestroyed()
        }
    }

    @Test fun `room archive queued transaction rechecks membership before admission`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "queued-history")
        runCurrent()
        val room = "queued@conference.example.org"
        val connection = fixture.connection
        connection.repairRooms += room
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holding = CompletableDeferred<kotlinx.coroutines.Deferred<Unit>>()
        connection.roomArchiveResponse = { request ->
            holding.complete(async(Dispatchers.IO) {
                database.withTransaction { entered.complete(Unit); release.await() }
            })
            entered.await()
            roomHistoryPage(request)
        }
        assertTrue(fixture.runtime.joinMuc(room))
        entered.await()
        runCurrent()
        val executor = database.transactionExecutor
        val field = executor.javaClass.declaredFields.single {
            java.util.Collection::class.java.isAssignableFrom(it.type)
        }.apply { isAccessible = true }
        try {
            assertTrue(synchronized(executor) { (field.get(executor) as Collection<*>).isNotEmpty() })
            connection.repairRegistry.retireAll()
        } finally { release.complete(Unit); holding.await().await() }
        connection.roomArchiveJob.await().join()
        assertNull(fixture.store.archiveCursor(ArchiveCursorKey(fixture.account.id.value, room, room)))
        assertTrue(fixture.store.messages(fixture.account.id.value).isEmpty())
    }

    @Test fun `service room query retains original membership through deferred native admission`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "deferred-native-history")
        runCurrent()
        val room = "deferred@conference.example.org"
        val connection = fixture.connection
        org.thanosapollo.nema.xmpp.smack.SmackAndroid.initialize(context)
        org.thanosapollo.nema.xmpp.smack.installNemaMamResultProvider()
        val socket = RoomHistorySocket(room)
        val native = org.thanosapollo.nema.xmpp.smack.SmackSessionConnection(
            socket, "account", "account@example.org", event = {},
        )
        native.updateAttempt(connection.attemptIdentity)
        connection.repairRegistry = native.javaClass.getDeclaredField("roomStableIdAuthorities")
            .apply { isAccessible = true }.get(native) as org.thanosapollo.nema.xmpp.smack.RoomStableIdAuthorityRegistry
        connection.repairRooms += room
        val entered = kotlinx.coroutines.channels.Channel<Pair<Job,
            org.thanosapollo.nema.session.RoomArchiveAuthorization>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val release = kotlinx.coroutines.channels.Channel<Unit>()
        connection.authorizedRoomQuery = { request, authorization ->
            // The actual service/controller call has selected L1. Hold before the
            // production native method (including its IO dispatch) consumes it.
            entered.send(requireNotNull(currentCoroutineContext()[Job]) to authorization)
            release.receive()
            native.queryArchive(request, authorization)
        }
        assertTrue(fixture.runtime.joinMuc(room))
        val (oldJob, oldAuthorization) = entered.receive()
        assertTrue(oldAuthorization.admit())
        // A real second service join publishes L2 in the same attempt/generation.
        assertTrue(fixture.runtime.joinMuc(room))
        assertFalse(oldAuthorization.admit())
        assertTrue(requireNotNull(native.roomArchiveAuthorization(room)).admit())
        release.send(Unit)
        oldJob.join()
        // Fresh synchronization is serialized behind L1; hold it independently
        // so the stale request's zero-wire and zero-durable effects are observable.
        val (freshJob, freshAuthorization) = entered.receive()
        assertEquals(0, socket.queries)
        assertNull(fixture.store.archiveCursor(ArchiveCursorKey(fixture.account.id.value, room, room)))
        assertTrue(fixture.store.messages(fixture.account.id.value).isEmpty())
        release.send(Unit)
        freshJob.join()
        assertTrue(freshAuthorization.admit())
        assertEquals(1, socket.queries)
        assertEquals("history-uid", fixture.store.archiveCursor(
            ArchiveCursorKey(fixture.account.id.value, room, room))?.newestId)
        assertEquals("room history", fixture.store.messages(fixture.account.id.value).single().body)
    }

    private class RoomHistorySocket(private val room: String) : org.jivesoftware.smack.tcp.XMPPTCPConnection(
        org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration.builder()
            .setXmppDomain(org.jxmpp.jid.impl.JidCreate.domainBareFrom("example.org"))
            .setUsernameAndPassword("account", null).build(),
    ) {
        var queries = 0
        init {
            connected = true; authenticated = true
            user = org.jxmpp.jid.impl.JidCreate.entityFullFrom("account@example.org/test")
            replyTimeout = 3000
        }
        override fun throwNotConnectedExceptionIfAppropriate() = Unit
        override fun sendStanzaInternal(packet: org.jivesoftware.smack.packet.Stanza) {
            val query = packet as org.jivesoftware.smackx.mam.element.MamQueryIQ
            queries++
            assertEquals(room, query.to.toString())
            processStanza(org.jivesoftware.smack.util.PacketParserUtils.parseStanza(
                "<message xmlns='jabber:client' from='$room' to='account@example.org/test'>" +
                    "<result xmlns='urn:xmpp:mam:2' queryid='${query.queryId}' id='history-uid'>" +
                    "<forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='2026-01-01T00:00:00Z'/>" +
                    "<message xmlns='jabber:client' from='$room/someone' type='groupchat'><body>room history</body>" +
                    "</message></forwarded></result></message>",
            ))
            processStanza(org.jivesoftware.smack.util.PacketParserUtils.parseStanza(
                "<iq xmlns='jabber:client' from='$room' to='account@example.org/test' id='${query.stanzaId}' type='result'>" +
                    "<fin xmlns='urn:xmpp:mam:2' complete='true' stable='true'><set xmlns='http://jabber.org/protocol/rsm'>" +
                    "<first index='0'>history-uid</first><last>history-uid</last><count>1</count></set></fin></iq>",
            ))
        }
    }

    private fun roomHistoryPage(request: org.thanosapollo.nema.xmpp.transport.ArchivePageRequest) =
        org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope(
            request, stable = true, complete = true, hasEarlier = false,
            firstId = "history-uid", lastId = "history-uid",
            messages = listOf(org.thanosapollo.nema.xmpp.transport.ArchiveMessageEnvelope(
                "history-uid", IncomingMessageEnvelope(
                    accountId = request.accountId, generation = request.generation,
                    peer = request.scope, sender = "${request.scope}/someone", outbound = false,
                    originId = null, body = "room history", thread = null, kind = MessageKind.GROUPCHAT,
                    sentAtEpochMs = 1L, sentTimeSource = MessageTimeSource.MAM,
                ),
            )),
        )

    private suspend fun seedRepairPair(account: String, room: String) {
        val dao = database.messageDao()
        dao.insertPeer(org.thanosapollo.nema.storage.PeerEntity(account, room))
        val base = dao.messages(account).maxOfOrNull { it.localSequence } ?: 0L
        val live = org.thanosapollo.nema.storage.MessageEntity(account, "$room-live", room, "$room/nick",
            MessageDirection.INBOUND, MessageKind.GROUPCHAT, null, null, "historical", base + 1, null,
            sentAtEpochMs = 1000, sentTimeSource = MessageTimeSource.LOCAL, liveDeliveryObserved = true)
        dao.insertMessage(live)
        dao.insertMessage(live.copy(localMessageId = "$room-mam", localSequence = base + 2,
            archiveOrdinal = 0, sentTimeSource = MessageTimeSource.MAM, liveDeliveryObserved = false))
        dao.insertTrustedAlias(org.thanosapollo.nema.storage.TrustedIdentityAliasEntity(account,
            IdentityAliasKind.STANZA_ID, room, "uid", live.localMessageId, IdentityAliasStatus.TRUSTED))
        dao.insertTrustedAlias(org.thanosapollo.nema.storage.TrustedIdentityAliasEntity(account,
            IdentityAliasKind.MAM_RESULT, ArchiveCursorKey(account, room, room).aliasAuthority(), "uid", "$room-mam", IdentityAliasStatus.TRUSTED))
        dao.insertArchivePosition(org.thanosapollo.nema.storage.ArchiveMessagePositionEntity(account, room, room, 0, "$room-mam"))
    }

    @Test fun `successful room join repairs only authorized room and repeats after reopen`() = runTest {
        var fixture = connectedRuntime(backgroundScope, "repair")
        runCurrent()
        val a = "a@conference.example.org"
        val b = "b@conference.example.org"
        val id = fixture.account.id.value
        seedRepairPair(id, a); seedRepairPair(id, b)
        fixture.connection.archiveSupported = true
        fixture.connection.repairRooms += a
        assertTrue(fixture.runtime.joinMuc(a))
        assertEquals(a, fixture.connection.repairArchive.receive())
        val receipt = database.accountDao().reconciliationState(id, "room-archive-uid-v1:${a.length}:$a")
        assertEquals(ReconciliationRepairStatus.COMPLETE, receipt?.status)
        assertEquals(3, database.messageDao().messages(id).size)
        assertNull(database.accountDao().reconciliationState(id, "room-archive-uid-v1:${b.length}:$b"))
        assertTrue(fixture.runtime.joinMuc(b)); runCurrent()
        assertTrue(fixture.connection.archiveRequests.none { it.scope == b })
        assertEquals(3, database.messageDao().messages(id).size)
        assertNull(database.accountDao().reconciliationState(id, "room-archive-uid-v1:${b.length}:$b"))
        fixture.runtime.serviceDestroyed()
        database.close(); database = NemaDatabase.create(context, databaseName)
        fixture = connectedRuntime(backgroundScope, "repair")
        runCurrent()
        fixture.connection.archiveSupported = true
        fixture.connection.repairRooms += a
        assertTrue(fixture.runtime.joinMuc(a)); assertEquals(a, fixture.connection.repairArchive.receive())
        assertEquals(receipt, database.accountDao().reconciliationState(id, "room-archive-uid-v1:${a.length}:$a"))
        fixture.connection.repairRooms += b
        assertTrue(fixture.runtime.joinMuc(b)); assertEquals(b, fixture.connection.repairArchive.receive())
        assertEquals(2, database.messageDao().messages(id).size)
    }

    @Test fun `queued room repair rejects retired attempt membership rejoin and cancellation`() = runTest {
        for (mode in listOf("retire", "attempt", "membership", "rejoin", "cancel")) {
            val job = kotlinx.coroutines.SupervisorJob()
            val scope = CoroutineScope(backgroundScope.coroutineContext + job)
            val fixture = connectedRuntime(scope, "queued-$mode")
            runCurrent()
            val room = "$mode@conference.example.org"
            val id = fixture.account.id.value
            seedRepairPair(id, room)
            val rows = database.messageDao().messages(id)
            val aliases = database.messageDao().trustedAliases(id)
            val positions = database.messageDao().archivePositions(id)
            val c = fixture.connection
            c.repairRooms += room
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holder = async(Dispatchers.IO) { database.withTransaction { entered.complete(Unit); release.await() } }
            entered.await()
            try {
                assertTrue(fixture.runtime.joinMuc(room)); runCurrent()
                val executor = database.transactionExecutor
                val field = executor.javaClass.declaredFields.single {
                    java.util.Collection::class.java.isAssignableFrom(it.type)
                }.apply { isAccessible = true }
                assertTrue(synchronized(executor) { (field.get(executor) as Collection<*>).isNotEmpty() })
                when (mode) {
                    "retire" -> c.revoke()
                    "attempt" -> c.updateAttempt(c.attemptIdentity.copy(attempt = ConnectionAttempt.require(99)))
                    "membership" -> c.repairRegistry.retireAll()
                    "rejoin" -> {
                        val lease = requireNotNull(c.repairRegistry.beginJoin(c.attemptIdentity, room))
                        c.repairRegistry.publish(lease, false, false, mamV2 = true)
                    }
                    "cancel" -> job.cancel()
                }
            } finally { release.complete(Unit); holder.await() }
            // Drain the writer after the submitted repair, before negative assertions.
            database.withTransaction { }
            runCurrent()
            assertEquals(mode, rows, database.messageDao().messages(id))
            assertEquals(mode, aliases, database.messageDao().trustedAliases(id))
            assertEquals(mode, positions, database.messageDao().archivePositions(id))
            assertNull(database.accountDao().reconciliationState(id, "room-archive-uid-v1:${room.length}:$room"))
            job.cancelAndJoin()
        }
    }

    @Test fun `room repair failure keeps archive usable and later join retries`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "fault")
        runCurrent()
        val room = "fault@conference.example.org"
        val id = fixture.account.id.value
        seedRepairPair(id, room)
        fixture.connection.archiveSupported = true
        fixture.connection.repairRooms += room
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER repair_fault BEFORE DELETE ON messages BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
        assertTrue(fixture.runtime.joinMuc(room)); assertEquals(room, fixture.connection.repairArchive.receive())
        val pending = database.accountDao().reconciliationState(id, "room-archive-uid-v1:${room.length}:$room")
        assertEquals(ReconciliationRepairStatus.PENDING, pending?.status)
        assertEquals(2, database.messageDao().messages(id).size)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER repair_fault")
        assertTrue(fixture.runtime.joinMuc(room)); assertEquals(room, fixture.connection.repairArchive.receive())
        assertEquals(1, database.messageDao().messages(id).size)
    }

    @Test fun `admitted room repair completes after retirement without holding entry monitor`() = runTest {
        lateinit var connection: RecordingConnection
        val reached = CompletableDeferred<Unit>()
        repairObserver = { boundary ->
            if (boundary == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) {
                assertFalse(Thread.holdsLock(connection.repairGate))
                connection.revoke()
                reached.complete(Unit)
            }
        }
        val fixture = connectedRuntime(backgroundScope, "accepted")
        connection = fixture.connection
        runCurrent()
        val room = "accepted@conference.example.org"
        seedRepairPair(fixture.account.id.value, room)
        connection.archiveSupported = true; connection.repairRooms += room
        assertTrue(fixture.runtime.joinMuc(room))
        reached.await()
        database.withTransaction { }
        runCurrent()
        assertTrue(connection.archiveRequests.none { it.scope == room })
        assertEquals(1, database.messageDao().messages(fixture.account.id.value).size)
        assertEquals(ReconciliationRepairStatus.COMPLETE, database.accountDao().reconciliationState(
            fixture.account.id.value, "room-archive-uid-v1:${room.length}:$room")?.status)
    }

    @Test
    fun `join publishes bookmark without wiping existing name`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val active = account("active")
        accounts.save(active)
        accounts.activate(active.id)
        credentials.store(active.id, "secret".toCharArray())
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val connection = connections.created.single()
        val room = "coven@conference.example.org"
        connection.bookmarks = listOf(
            RoomBookmark(room, name = "Council of Oberon", nick = "Legacy", password = "secret"),
        )

        assertTrue(runtime.joinMuc(room, nick = "Puck"))
        runCurrent()

        assertEquals(
            listOf(
                RoomBookmark(
                    roomJid = room,
                    name = "Council of Oberon",
                    nick = "Puck",
                    password = "secret",
                    autojoin = true,
                ),
            ),
            connection.publishedBookmarks,
        )
    }

    @Test
    fun `join does not publish when bookmark snapshot is incomplete`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val active = account("active")
        accounts.save(active)
        accounts.activate(active.id)
        credentials.store(active.id, "secret".toCharArray())
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val connection = connections.created.single()
        connection.bookmarkReadComplete = false

        assertTrue(runtime.joinMuc("coven@conference.example.org", nick = "Puck"))
        runCurrent()

        assertTrue(connection.publishedBookmarks.isEmpty())
    }

    @Test
    fun `bookmark read modify write is serialized in join order`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val active = account("active")
        accounts.save(active)
        accounts.activate(active.id)
        credentials.store(active.id, "secret".toCharArray())
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val connection = connections.created.single()
        // Startup restoration reads outside the mutation lock; let it finish before arming the gate.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { connection.initialBookmarkReadCompleted.await() }
        }
        val releaseFirstRead = CompletableDeferred<Unit>()
        connection.nextBookmarkReadGate = releaseFirstRead
        val room = "coven@conference.example.org"

        assertTrue(runtime.joinMuc(room, nick = "Old"))
        withTimeout(5_000) { connection.gatedBookmarkReadEntered.await() }
        assertTrue(runtime.joinMuc(room, nick = "New"))
        runCurrent()
        assertTrue(connection.publishedBookmarks.isEmpty())

        releaseFirstRead.complete(Unit)
        runCurrent()
        assertEquals(listOf("Old", "New"), connection.publishedBookmarks.map(RoomBookmark::nick))
    }

    @Test
    fun `completed old join cannot start replacement followups`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts, credentials, MessageStore(database), PeerIdentityStore(database.messageDao()),
            backgroundScope, connections,
        )
        val first = account("first")
        val second = account("second")
        for (account in listOf(first, second)) {
            accounts.save(account)
            credentials.store(account.id, "secret".toCharArray())
        }
        accounts.activate(first.id)
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val original = connections.created.single()
        val release = CompletableDeferred<Unit>()
        original.nextJoinGate = release
        original.archiveSupported = true
        val pending = async { runtime.joinMuc("old@conference.example.org", "Old", "old-secret") }
        original.joined.await()
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.activate(second.id))
        connections.created.last().archiveSupported = true
        release.complete(Unit)
        assertFalse(pending.await())
        runCurrent()
        assertTrue(connections.created.all { it.publishedBookmarks.isEmpty() })
        assertTrue(connections.created.all { connection ->
            connection.archiveRequests.none { it.archiveAuthority == "old@conference.example.org" }
        })
    }

    @Test
    fun `restoration peer write cannot retarget B`() = restorationPeerWriteRace("switch")

    @Test
    fun `restoration peer write cannot retarget replacement A`() = restorationPeerWriteRace("return")

    @Test
    fun `restoration peer write cannot retarget reconnected A`() = restorationPeerWriteRace("reconnect")

    @Test
    fun `restoration peer write with current lease joins`() = restorationPeerWriteRace("current")

    private fun restorationPeerWriteRace(transition: String) = runTest {
        val executor = PeerWriteExecutor()
        val peers = androidx.room.Room.inMemoryDatabaseBuilder(context, NemaDatabase::class.java)
            .setTransactionExecutor(executor)
            .build()
        try {
            val accounts = AccountRepository(database.accountDao())
            val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
            val bookmark = RoomBookmark("old@conference.example.org", nick = "Old", password = "old-secret", autojoin = true)
            val connections = RecordingConnectionFactory(firstBookmarks = listOf(bookmark), archiveSupported = true)
            val runtime = SessionRuntime(
                accounts, credentials, MessageStore(database), PeerIdentityStore(peers.messageDao()),
                backgroundScope, connections,
            )
            val first = account("first")
            val second = account("second")
            for (account in listOf(first, second)) {
                accounts.save(account)
                AccountRepository(peers.accountDao()).save(account)
                credentials.store(account.id, "secret".toCharArray())
            }
            accounts.activate(first.id)
            executor.arm()
            assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
            val original = connections.created.single()
            original.repairRooms += bookmark.roomJid
            // Only this database's peer transaction is held, after restoration's authority check.
            executor.entered.await()
            // The fake records the caller's Job at the first bookmark read; the controller
            // calls it directly, so this is the restoration coroutine, not Room's worker.
            val restoration = original.firstBookmarkReadJob.await()
            assertFalse(restoration.isCompleted)
            assertTrue(original.joinedRooms.isEmpty())
            original.bookmarks = emptyList()
            when (transition) {
                "switch", "return" -> {
                    assertEquals(ConnectionCommandOutcome.RUNNING, runtime.activate(second.id))
                    if (transition == "return") {
                        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.activate(first.id))
                    }
                }
                "reconnect" -> completeReconnect(
                    RuntimeFixture(accounts, credentials, MessageStore(database), connections, runtime, first, original),
                    original.attemptIdentity,
                )
            }
            runCurrent()
            executor.release()
            executor.finished.await()
            // A completed SQL task does not fence its coroutine continuation. Join the
            // captured restoration before asserting that it dispatched no stale effects.
            restoration.join()
            assertTrue(restoration.isCompleted)
            runCurrent()
            if (transition == "current") {
                original.joined.await()
                original.published.await()
                original.roomArchiveRequested.await()
                runCurrent()
                assertEquals(listOf(Triple(bookmark.roomJid, bookmark.nick, bookmark.password)), original.joinedRooms)
                assertEquals(listOf(bookmark), original.publishedBookmarks)
                assertEquals(
                    listOf(first.id),
                    original.archiveRequests.filter { it.archiveAuthority == bookmark.roomJid }.map { it.accountId },
                )
            } else {
                assertTrue(connections.created.all { it.joinedRooms.isEmpty() })
                assertTrue(connections.created.all { it.publishedBookmarks.isEmpty() })
                assertTrue(connections.created.all { connection ->
                    connection.archiveRequests.none { it.archiveAuthority == bookmark.roomJid }
                })
            }
        } finally {
            executor.release()
            peers.close()
            executor.close()
        }
    }

    private class PeerWriteExecutor : java.util.concurrent.Executor, AutoCloseable {
        private val delegate = java.util.concurrent.Executors.newSingleThreadExecutor()
        private val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val held = java.util.concurrent.atomic.AtomicReference<Runnable?>()
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        fun arm() { armed.set(true) }

        override fun execute(command: Runnable) {
            if (armed.compareAndSet(true, false)) {
                held.set(Runnable { try { command.run() } finally { finished.complete(Unit) } })
                entered.complete(Unit)
            } else delegate.execute(command)
        }

        fun release() { held.getAndSet(null)?.let(delegate::execute) }
        override fun close() { delegate.shutdownNow() }
    }

    @Test
    fun `stale bookmark read cannot publish after account switch`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val first = account("first")
        val second = account("second")
        accounts.save(first)
        accounts.save(second)
        accounts.activate(first.id)
        credentials.store(first.id, "first-secret".toCharArray())
        credentials.store(second.id, "second-secret".toCharArray())
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val oldConnection = connections.created.single()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { oldConnection.initialBookmarkReadCompleted.await() }
        }
        val releaseOldRead = CompletableDeferred<Unit>()
        oldConnection.nextBookmarkReadGate = releaseOldRead

        assertTrue(runtime.joinMuc("old@conference.example.org", nick = "Old"))
        withTimeout(5_000) { oldConnection.gatedBookmarkReadEntered.await() }
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.activate(second.id))
        val newConnection = connections.created.last()
        // Keep startup restoration from reading the bookmark published by the explicit join.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { newConnection.initialBookmarkReadCompleted.await() }
        }
        assertTrue(runtime.joinMuc("new@conference.example.org", nick = "New"))
        runCurrent()
        assertTrue(oldConnection.publishedBookmarks.isEmpty())
        assertTrue(newConnection.publishedBookmarks.isEmpty())

        releaseOldRead.complete(Unit)
        runCurrent()
        assertTrue(oldConnection.publishedBookmarks.isEmpty())
        assertEquals(listOf("New"), newConnection.publishedBookmarks.map(RoomBookmark::nick))
    }

    @Test
    fun `room update fills empty display name from disco then keeps it`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val identities = PeerIdentityStore(database.messageDao())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = identities,
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val active = account("active")
        accounts.save(active)
        accounts.activate(active.id)
        credentials.store(active.id, "secret".toCharArray())
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val connection = connections.created.single()
        val room = "coven@conference.example.org"

        connection.emitRoom(
            org.thanosapollo.nema.xmpp.muc.RoomView(
                roomJid = room,
                subject = "topic",
                discoName = "Council of Oberon",
            ),
        )
        assertEquals("Council of Oberon", identities.peer(active.id.value, room)?.displayName)

        connection.emitRoom(
            org.thanosapollo.nema.xmpp.muc.RoomView(
                roomJid = room,
                subject = "new topic",
                discoName = "Other",
            ),
        )
        assertEquals("Council of Oberon", identities.peer(active.id.value, room)?.displayName)
    }

    @Test
    fun `live receipt request acknowledges full requester while replays stay silent`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val active = account("active")
        assertTrue(
            runtime.prepareActivation(
                token = runtime.beginPendingActivation(),
                configuration = active,
                credential = "secret".toCharArray(),
                emitActivation = {},
            ),
        )
        accounts.activate(active.id)
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
        val connection = connections.created.single()
        val requester = "peer@example.org/device"
        fun incoming(id: String, source: MessageTimeSource?) = IncomingMessageEnvelope(
            accountId = active.id,
            generation = connection.attemptIdentity.generation,
            peer = "peer@example.org",
            sender = "peer@example.org",
            outbound = false,
            originId = null,
            body = id,
            thread = null,
            messageId = id,
            sentAtEpochMs = source?.let { 1L },
            sentTimeSource = source,
            receiptRequested = true,
            receiptRecipient = requester,
        )

        val live = incoming("wire-live", null)
        connection.emitIncoming(live)
        runCurrent()
        connection.emitIncoming(live)
        runCurrent()
        listOf(MessageTimeSource.CARBON, MessageTimeSource.MAM).forEach { source ->
            connection.emitIncoming(incoming("wire-${source.name.lowercase()}", source))
            runCurrent()
        }
        connection.emitIncoming(incoming("wire-mam-first", MessageTimeSource.MAM))
        runCurrent()
        connection.emitIncoming(incoming("wire-mam-first", null))
        runCurrent()

        assertEquals(
            listOf(
                OutgoingMessageSignal(
                    accountId = active.id,
                    generation = connection.attemptIdentity.generation,
                    recipient = requester,
                    targetId = "wire-live",
                    stage = MessageReceiptStage.RECEIVED,
                    protocol = MessageSignalProtocol.DELIVERY_RECEIPT,
                ),
                OutgoingMessageSignal(
                    accountId = active.id,
                    generation = connection.attemptIdentity.generation,
                    recipient = requester,
                    targetId = "wire-mam-first",
                    stage = MessageReceiptStage.RECEIVED,
                    protocol = MessageSignalProtocol.DELIVERY_RECEIPT,
                ),
            ),
            connection.sentSignals,
        )
    }

    @Test
    fun `displayed marker uses only the current connected account and exact target`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts = accounts,
            credentials = credentials,
            messages = MessageStore(database),
            peerIdentities = PeerIdentityStore(database.messageDao()),
            runtimeScope = backgroundScope,
            connectionFactory = connections,
        )
        val active = account("active")
        assertTrue(
            runtime.prepareActivation(
                token = runtime.beginPendingActivation(),
                configuration = active,
                credential = "secret".toCharArray(),
                emitActivation = {},
            ),
        )
        accounts.activate(active.id)
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())

        assertTrue(runtime.markDisplayed(active.id.value, "peer@example.org", "wire-1"))
        assertEquals(false, runtime.markDisplayed("other", "peer@example.org", "wire-2"))
        assertEquals(
            listOf(
                OutgoingMessageSignal(
                    accountId = active.id,
                    generation = connections.created.single().attemptIdentity.generation,
                    recipient = "peer@example.org",
                    targetId = "wire-1",
                    stage = MessageReceiptStage.DISPLAYED,
                    protocol = MessageSignalProtocol.CHAT_MARKER,
                ),
            ),
            connections.created.single().sentSignals,
        )
    }

    @Test
    fun `direct reaction fixture resolves exact target with absent snapshot`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "reaction-account")

        installDirectTarget(fixture, DIRECT_TARGET.localId, DIRECT_TARGET.wireId)

        assertEquals(
            DirectReactionTarget(
                fixture.account.id.value,
                REACTION_PEER,
                DIRECT_TARGET.localId,
                DIRECT_TARGET.wireId,
            ),
            fixture.store.resolveDirectReactionTarget(
                fixture.account.id.value,
                REACTION_PEER,
                DIRECT_TARGET.localId,
            ),
        )
        assertNull(ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
    }

    @Test
    fun `correction fixture resolves original while second target stays independent`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "reaction-account")
        installDirectTarget(fixture, DIRECT_TARGET.localId, DIRECT_TARGET.wireId)

        installAcceptedCorrection(fixture)
        installSecondTarget(fixture)

        val expectedOriginal = DirectReactionTarget(
            fixture.account.id.value,
            REACTION_PEER,
            DIRECT_TARGET.localId,
            DIRECT_TARGET.wireId,
        )
        assertEquals(
            expectedOriginal,
            fixture.store.resolveDirectReactionTarget(
                fixture.account.id.value,
                REACTION_PEER,
                CORRECTION.localId,
            ),
        )
        assertEquals(
            DirectReactionTarget(
                fixture.account.id.value,
                REACTION_PEER,
                SECOND_TARGET.localId,
                SECOND_TARGET.wireId,
            ),
            fixture.store.resolveDirectReactionTarget(
                fixture.account.id.value,
                REACTION_PEER,
                SECOND_TARGET.localId,
            ),
        )
    }

    @Test
    fun `reaction envelope helpers preserve exact runtime and event fields`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "reaction-account")
        val attempt = fixture.connection.attemptIdentity
        val emojis = listOf("🔥", "👍")

        assertEquals(
            OutgoingReactionEnvelope(
                fixture.account.id,
                attempt.generation,
                REACTION_PEER,
                DIRECT_TARGET.wireId,
                emojis,
            ),
            outgoingReaction(fixture, emojis, DIRECT_TARGET.wireId),
        )
        listOf(DIRECT_TARGET, SECOND_TARGET).forEach { event ->
            assertEquals(
                IncomingReactionEnvelope(
                    fixture.account.id,
                    attempt.generation,
                    fixture.account.bareJid.value,
                    REACTION_PEER,
                    REACTION_PEER,
                    event.wireId,
                    emojis,
                    event.delayedAtMs,
                ),
                incomingReaction(fixture, attempt, event.wireId, emojis, event.delayedAtMs),
            )
        }
    }

    @Test
    fun `switch account replaces exact runtime owner and attempt`() = runTest {
        val original = connectedRuntime(backgroundScope, "original")

        val replacement = switchAccount(original, "replacement")

        assertEquals(account("replacement"), replacement.runtime.activeAccount.first())
        assertEquals(replacement.account.id, replacement.connection.accountId)
        assertEquals(replacement.account.id, replacement.connection.attemptIdentity.accountId)
        assertEquals(
            ConnectionState.Connected(
                replacement.account.id,
                replacement.connection.attemptIdentity.generation,
            ),
            replacement.runtime.state.value,
        )
        assertFalse(original.connection.isUsable)
        assertEquals(1, original.connection.disconnectCalls)
    }

    @Test
    fun `complete reconnect advances generation and returns exact current attempt`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "reconnect")
        val oldAttempt = fixture.connection.attemptIdentity

        val currentAttempt = completeReconnect(fixture, oldAttempt)

        assertEquals(oldAttempt.accountId, currentAttempt.accountId)
        assertEquals(oldAttempt.generation.value + 1, currentAttempt.generation.value)
        assertEquals(oldAttempt.attempt.value + 1, currentAttempt.attempt.value)
        assertEquals(currentAttempt, fixture.connection.attemptIdentity)
        assertEquals(
            ConnectionState.Connected(currentAttempt.accountId, currentAttempt.generation),
            fixture.runtime.state.value,
        )
    }

    @Test
    fun `old attempt reaction stays rejected after switch and reconnect`() = runTest {
        val original = connectedRuntime(backgroundScope, "original")
        val fixture = switchAccount(original, "replacement")
        installDirectTarget(fixture, DIRECT_TARGET.localId, DIRECT_TARGET.wireId)
        val oldAttempt = fixture.connection.attemptIdentity

        completeReconnect(fixture, oldAttempt)
        emitOldAttemptReaction(fixture, oldAttempt, delayedAtMs = 7_000L)

        assertNull(ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
    }

    @Test
    fun `reactTo sends before committing exact own reaction`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "effect-first")
        installDirectTarget(fixture, DIRECT_TARGET.localId, DIRECT_TARGET.wireId)
        val before = ownReactionSnapshot(fixture, DIRECT_TARGET.localId)
        val step = fixture.connection.queueReaction()

        val result = async {
            fixture.runtime.reactTo(REACTION_PEER, DIRECT_TARGET.localId, "🔥")
        }
        step.entered.await()

        assertEquals(before, ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
        assertEquals(listOf(outgoingReaction(fixture, listOf("🔥"), DIRECT_TARGET.wireId)), fixture.connection.sentReactions)
        assertFalse(result.isCompleted)
        step.release.complete(Unit)
        assertTrue(result.await())
        assertEquals(expectedOwnReaction(fixture, DIRECT_TARGET, "🔥", REACTION_NOW), ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
    }

    @Test
    fun `group reaction sends exact immutable command before commit`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "group-effect-first")
        installGroupTarget(fixture, GROUP_TARGET)
        val step = fixture.connection.queueReaction()

        val result = async { fixture.runtime.reactTo(REACTION_ROOM, GROUP_TARGET.localId, "🔥") }
        step.entered.await()

        assertNull(ownReactionSnapshot(fixture, GROUP_TARGET.localId, REACTION_ROOM))
        assertEquals(
            listOf(outgoingReaction(
                fixture, listOf("🔥"), GROUP_TARGET.wireId, REACTION_ROOM, MessageKind.GROUPCHAT,
            )),
            fixture.connection.sentReactions,
        )
        assertFalse(result.isCompleted)
        step.release.complete(Unit)
        assertTrue(result.await())
        assertEquals(
            expectedOwnReaction(fixture, GROUP_TARGET, "🔥", REACTION_NOW, REACTION_ROOM),
            ownReactionSnapshot(fixture, GROUP_TARGET.localId, REACTION_ROOM),
        )
    }

    @Test
    fun `group reaction locks same target while another target progresses`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "group-keys")
        installGroupTarget(fixture, GROUP_TARGET)
        installGroupTarget(fixture, SECOND_GROUP_TARGET)
        val steps = List(3) { fixture.connection.queueReaction() }

        val first = async { fixture.runtime.reactTo(REACTION_ROOM, GROUP_TARGET.localId, "🔥") }
        steps[0].entered.await()
        val sameAtLock = observeReactionLockEntry(fixture, REACTION_ROOM, GROUP_TARGET.localId)
        val same = async { fixture.runtime.reactTo(REACTION_ROOM, GROUP_TARGET.localId, "👍") }
        sameAtLock.awaitReactionLockEntry()
        val other = async { fixture.runtime.reactTo(REACTION_ROOM, SECOND_GROUP_TARGET.localId, "❤️") }
        steps[1].entered.await()
        assertFalse(same.isCompleted)
        steps[1].release.complete(Unit)
        assertTrue(other.await())
        steps[0].release.complete(Unit)
        assertTrue(first.await())
        steps[2].entered.await()
        steps[2].release.complete(Unit)
        assertTrue(same.await())
        assertEquals(
            listOf("🔥", "🔥\u001f👍"),
            fixture.connection.sentReactions
                .filter { it.targetId == GROUP_TARGET.wireId }
                .map { it.emojis.joinToString("\u001f") },
        )
    }

    @Test
    fun `group reaction completion after reconnect cannot commit`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "group-reconnect")
        installGroupTarget(fixture, GROUP_TARGET)
        val oldAttempt = fixture.connection.attemptIdentity
        val step = fixture.connection.queueReaction()
        val result = async { fixture.runtime.reactTo(REACTION_ROOM, GROUP_TARGET.localId, "🔥") }
        step.entered.await()

        completeReconnect(fixture, oldAttempt)
        step.release.complete(Unit)

        assertFalse(result.await())
        assertNull(ownReactionSnapshot(fixture, GROUP_TARGET.localId, REACTION_ROOM))
    }

    @Test
    fun `reactTo uses the keyed mutex and rejects canonical drift before send`() {
        val source = reactToSource()
        assertTrue(source.contains("reactionCommandLock(key).withLock"))
        val guard = source.indexOf("command.canonicalLocalMessageId != key.canonicalLocalId")
        val send = source.indexOf("controller.sendReaction(")
        assertTrue(guard >= 0 && guard < send)
    }

    @Test
    fun `same canonical reaction commands accumulate in serial order`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "same-key")
        installDirectTarget(fixture, DIRECT_TARGET.localId, DIRECT_TARGET.wireId)
        installAcceptedCorrection(fixture)
        val steps = listOf(fixture.connection.queueReaction(), fixture.connection.queueReaction())

        val first = async { fixture.runtime.reactTo(REACTION_PEER, DIRECT_TARGET.localId, "🔥") }
        steps[0].entered.await()
        val secondAtLock = observeReactionLockEntry(fixture, REACTION_PEER, DIRECT_TARGET.localId)
        val second = async { fixture.runtime.reactTo(REACTION_PEER, CORRECTION.localId, "👍") }
        secondAtLock.awaitReactionLockEntry()
        steps[0].release.complete(Unit)
        steps[1].entered.await()
        assertTrue(first.await())
        assertEquals(listOf(
            outgoingReaction(fixture, listOf("🔥"), DIRECT_TARGET.wireId),
            outgoingReaction(fixture, listOf("🔥", "👍"), DIRECT_TARGET.wireId),
        ), fixture.connection.sentReactions)
        steps[1].release.complete(Unit)
        assertTrue(second.await())
        assertEquals(expectedOwnReaction(fixture, DIRECT_TARGET, "🔥\u001f👍", REACTION_NOW).copy(revision = 2),
            ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
    }

    @Test
    fun `different canonical reaction commands send concurrently`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "different-keys")
        installDirectTarget(fixture, DIRECT_TARGET.localId, DIRECT_TARGET.wireId)
        installSecondTarget(fixture)
        val steps = listOf(fixture.connection.queueReaction(), fixture.connection.queueReaction())

        val first = async { fixture.runtime.reactTo(REACTION_PEER, DIRECT_TARGET.localId, "🔥") }
        val second = async { fixture.runtime.reactTo(REACTION_PEER, SECOND_TARGET.localId, "👍") }
        steps.forEach { it.entered.await() }
        steps.forEach { it.release.complete(Unit) }
        assertEquals(setOf(
            outgoingReaction(fixture, listOf("🔥"), DIRECT_TARGET.wireId),
            outgoingReaction(fixture, listOf("👍"), SECOND_TARGET.wireId),
        ), fixture.connection.sentReactions.toSet())
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(expectedOwnReaction(fixture, DIRECT_TARGET, "🔥", REACTION_NOW),
            ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
        assertEquals(expectedOwnReaction(fixture, SECOND_TARGET, "👍", REACTION_NOW),
            ownReactionSnapshot(fixture, SECOND_TARGET.localId))
    }

    @Test
    fun `queued correction rejects canonical drift after production merge`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "canonical-drift")
        suspend fun ingest(localId: String, aliases: List<TrustedIdentityAlias>) = fixture.store.ingest(IncomingMessage(
            fixture.account.id.value, localId, REACTION_PEER, REACTION_PEER,
            MessageDirection.INBOUND, MessageKind.CHAT, null, null, DIRECT_TARGET.localId, null, aliases,
        ))
        val origin = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, REACTION_PEER, SECOND_TARGET.wireId)
        val message = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, REACTION_PEER, DIRECT_TARGET.wireId)
        ingest(SECOND_TARGET.localId, listOf(origin))
        ingest(DIRECT_TARGET.localId, listOf(message))
        installAcceptedCorrection(fixture)
        val callback = incomingReaction(fixture, fixture.connection.attemptIdentity,
            DIRECT_TARGET.wireId, listOf("❤️"), SECOND_TARGET.delayedAtMs)
            .copy(senderBareJid = fixture.account.bareJid.value)
        val firstStep = fixture.connection.queueReaction(callback = ReactionCallback(
            fixture.connection.attemptIdentity, callback))
        fixture.connection.queueReaction(released = true)
        val first = async { fixture.runtime.reactTo(REACTION_PEER, CORRECTION.localId, "🔥") }
        firstStep.entered.await()
        runCurrent()
        assertEquals(expectedOwnReaction(fixture, DIRECT_TARGET, "❤️", SECOND_TARGET.delayedAtMs!!),
            ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
        val queuedAtLock = observeReactionLockEntry(fixture, REACTION_PEER, DIRECT_TARGET.localId)
        val queued = async {
            fixture.runtime.reactTo(REACTION_PEER, CORRECTION.localId, "👍")
        }
        queuedAtLock.awaitReactionLockEntry()

        assertEquals(1, ingest("bridge", listOf(origin, message)).mergedRows)
        val dao = database.messageDao()
        assertNull(dao.message(fixture.account.id.value, DIRECT_TARGET.localId))
        assertEquals(SECOND_TARGET.localId, dao.trustedAlias(fixture.account.id.value,
            IdentityAliasKind.MESSAGE_ID, REACTION_PEER, DIRECT_TARGET.wireId)?.messageId)
        assertEquals(SECOND_TARGET.localId, fixture.store.resolveDirectReactionTarget(
            fixture.account.id.value, REACTION_PEER, CORRECTION.localId)?.canonicalLocalId)
        firstStep.release.complete(Unit)
        assertTrue(first.await())
        assertFalse(queued.await())
        assertEquals(listOf(outgoingReaction(fixture, listOf("🔥"), DIRECT_TARGET.wireId)),
            fixture.connection.sentReactions)
        assertEquals(MessageReactionEntity(fixture.account.id.value, REACTION_PEER,
            fixture.account.bareJid.value, SECOND_TARGET.localId, SECOND_TARGET.localId,
            DIRECT_TARGET.wireId, "❤️", SECOND_TARGET.delayedAtMs!!, 1),
            ownReactionSnapshot(fixture, SECOND_TARGET.localId))
    }

    @Test
    fun `reactTo preserves durable state and exact throwable behavior`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "send-failure")
        installDirectTarget(fixture, DIRECT_TARGET.localId, DIRECT_TARGET.wireId)
        val before = ownReactionSnapshot(fixture, DIRECT_TARGET.localId)
        suspend fun result(failure: Throwable) = runCatching {
            fixture.connection.queueReaction(failure = failure, released = true)
            fixture.runtime.reactTo(REACTION_PEER, DIRECT_TARGET.localId, "🔥")
        }

        val ordinary = Exception("ordinary")
        assertFalse(result(ordinary).getOrThrow())
        assertEquals(before, ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
        val cancelled = kotlinx.coroutines.CancellationException("cancelled")
        assertSame(cancelled, result(cancelled).exceptionOrNull())
        assertEquals(before, ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
        val fatal = object : Throwable("fatal") {}
        assertSame(fatal, result(fatal).exceptionOrNull())
        assertEquals(before, ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
    }

    @Test
    fun `reactTo accepts trusted synchronous own reaction as submission winner`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "reentry")
        listOf(DIRECT_TARGET, SECOND_TARGET).forEach { target ->
            installDirectTarget(fixture, target.localId, target.wireId)
            val attempt = fixture.connection.attemptIdentity
            val callback = incomingReaction(
                fixture, attempt, target.wireId, listOf("❤️"), target.delayedAtMs,
            ).copy(senderBareJid = fixture.account.bareJid.value)
            fixture.connection.queueReaction(
                callback = ReactionCallback(attempt, callback),
                released = true,
            )

            assertTrue(fixture.runtime.reactTo(REACTION_PEER, target.localId, "🔥"))
            val row = requireNotNull(ownReactionSnapshot(fixture, target.localId))
            val eventTime = target.delayedAtMs ?: row.updatedAtMs
            assertEquals(expectedOwnReaction(fixture, target, "❤️", eventTime), row)
            if (target.delayedAtMs == null) assertTrue(row.updatedAtMs > 0L)
        }
    }

    @Test
    fun `reactTo rejects completion after account switch`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "switch-old")
        installDirectTarget(fixture, DIRECT_TARGET.localId, DIRECT_TARGET.wireId)
        val oldAttempt = fixture.connection.attemptIdentity
        val step = fixture.connection.queueReaction()
        val result = async { fixture.runtime.reactTo(REACTION_PEER, DIRECT_TARGET.localId, "🔥") }
        step.entered.await()

        switchAccount(fixture, "switch-new")
        step.release.complete(Unit)

        assertFalse(result.await())
        assertNull(ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
        emitOldAttemptReaction(fixture, oldAttempt, delayedAtMs = 7_000L)
        assertNull(ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
    }

    @Test
    fun `reactTo rejects completion after reconnect`() = runTest {
        val fixture = connectedRuntime(backgroundScope, "reconnect-send")
        installDirectTarget(fixture, DIRECT_TARGET.localId, DIRECT_TARGET.wireId)
        val oldAttempt = fixture.connection.attemptIdentity
        val step = fixture.connection.queueReaction()
        val result = async { fixture.runtime.reactTo(REACTION_PEER, DIRECT_TARGET.localId, "🔥") }
        step.entered.await()

        completeReconnect(fixture, oldAttempt)
        step.release.complete(Unit)

        assertFalse(result.await())
        assertNull(ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
        emitOldAttemptReaction(fixture, oldAttempt, delayedAtMs = 7_000L)
        assertNull(ownReactionSnapshot(fixture, DIRECT_TARGET.localId))
    }

    @Test
    fun `reaction fake captures exact envelope before entry and waits for release`() = runTest {
        val connection = RecordingConnection(AccountId.require("account"), null, null) {}
        val reaction = outgoingReaction(reactionAttempt())
        val step = connection.queueReaction(released = false)
        var capturedBeforeEntry = false
        step.entered.invokeOnCompletion {
            capturedBeforeEntry = connection.sentReactions.singleOrNull() == reaction
        }
        val sending = async { connection.sendReaction(reaction) }
        runCurrent()
        assertTrue(capturedBeforeEntry)
        assertEquals(listOf(reaction), connection.sentReactions)
        assertFalse(sending.isCompleted)
        step.release.complete(Unit)
        sending.await()
    }

    @Test
    fun `reaction fake invokes synchronous callback before send returns`() = runTest {
        val events = mutableListOf<SessionEvent>()
        var returned = false
        val connection = RecordingConnection(AccountId.require("account"), null, null) {
            assertFalse(returned)
            events += it
        }
        val attempt = reactionAttempt()
        val incoming = incomingReaction(attempt)
        connection.queueReaction(callback = ReactionCallback(attempt, incoming), released = true)
        connection.sendReaction(outgoingReaction(attempt))
        returned = true
        assertEquals(listOf(SessionEvent.Reaction(attempt, incoming)), events)
    }

    @Test
    fun `reaction fake propagates each exact configured throwable`() = runTest {
        val connection = RecordingConnection(AccountId.require("account"), null, null) {}
        val reaction = outgoingReaction(reactionAttempt())
        suspend fun caught(failure: Throwable): Throwable? {
            connection.queueReaction(failure = failure, released = true)
            return runCatching { connection.sendReaction(reaction) }.exceptionOrNull()
        }
        val ordinary = IllegalStateException("ordinary")
        val cancelled = kotlinx.coroutines.CancellationException("cancelled")
        val fatal = object : Throwable("fatal") {}
        assertSame(ordinary, caught(ordinary))
        assertSame(cancelled, caught(cancelled))
        assertSame(fatal, caught(fatal))
    }

    private fun reactionAttempt() = SessionAttemptIdentity(
        AccountId.require("account"), ConnectionGeneration.require(1),
        ConnectionAttempt.require(1), LifecycleEpoch.require(1),
    )
    private fun outgoingReaction(attempt: SessionAttemptIdentity) = OutgoingReactionEnvelope(
        attempt.accountId, attempt.generation, "peer@example.org", "wire", listOf("🔥"),
    )
    private fun incomingReaction(attempt: SessionAttemptIdentity) = IncomingReactionEnvelope(
        attempt.accountId, attempt.generation, "account@example.org", "peer@example.org",
        "peer@example.org", "wire", listOf("🔥"),
    )

    private fun reactToSource() = java.io.File(
        "src/main/java/org/thanosapollo/nema/service/XmppConnectionService.kt",
    ).readText().substringAfter("suspend fun reactTo(").substringBefore("fun reportComposer")

    private fun observeReactionLockEntry(
        fixture: RuntimeFixture,
        peerJid: String,
        canonicalLocalId: String,
    ): CompletableDeferred<Unit> {
        val field = SessionRuntime::class.java.getDeclaredField("reactionMutexes").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mutexes = field.get(fixture.runtime) as MutableMap<Any, Mutex>
        val entered = CompletableDeferred<Unit>()
        synchronized(mutexes) {
            val expected = mapOf(
                "accountId" to fixture.account.id.value,
                "peerJid" to peerJid,
                "ownSender" to fixture.account.bareJid.value,
                "canonicalLocalId" to canonicalLocalId,
            )
            val entry = mutexes.entries.single { (key, _) ->
                expected.all { (name, value) ->
                    key.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(key) == value
                }
            }
            val original = entry.value
            assertTrue("First reaction must still hold the exact canonical mutex", original.isLocked)
            // Observe selection after real Room resolution, without changing acquisition or release.
            // The first holder and queued wrapper both retain the same underlying mutex.
            entry.setValue(object : Mutex by original {
                override suspend fun lock(owner: Any?) {
                    entered.complete(Unit)
                    original.lock(owner)
                }
            })
        }
        return entered
    }

    private suspend fun CompletableDeferred<Unit>.awaitReactionLockEntry() = withContext(Dispatchers.Default) {
        // Room runs outside the virtual scheduler; bound this acknowledgement in real time.
        withTimeout(5_000) { await() }
    }

    @Test
    fun `Smack receipt callback returns from actual IO acknowledgement`() = runBlocking {
        smackCallbackSchedule(null)
    }

    @Test
    fun `Smack incoming and stop settle before and inside durable callback`() = runBlocking {
        listOf(false, true).forEach { smackCallbackSchedule(it) }
    }

    private suspend fun smackCallbackSchedule(stopInsideDurable: Boolean?) {
        org.thanosapollo.nema.xmpp.smack.SmackAndroid.initialize(context)
        val active = account("smack-${stopInsideDurable}")
        val sent = java.util.Collections.synchronizedList(mutableListOf<org.jivesoftware.smack.packet.Stanza>())
        val transport = object : org.jivesoftware.smack.tcp.XMPPTCPConnection(
            org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(org.jxmpp.jid.impl.JidCreate.domainBareFrom("example.org"))
                .setUsernameAndPassword("account", null).build(),
        ) {
            init {
                connected = true
                authenticated = true
                user = org.jxmpp.jid.impl.JidCreate.entityFullFrom("${active.bareJid.value}/test")
            }
            override fun throwNotConnectedExceptionIfAppropriate() = Unit
            override fun sendStanzaInternal(packet: org.jivesoftware.smack.packet.Stanza) { sent += packet }
        }
        val boundary = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val revoked = CompletableDeferred<Unit>()
        val observations = mutableListOf<Boolean>()
        lateinit var smack: org.thanosapollo.nema.xmpp.smack.SmackSessionConnection
        fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            requireNotNull(it.get(owner))
        }
        val factory = SessionConnectionFactory { configuration, _, event ->
            smack = org.thanosapollo.nema.xmpp.smack.SmackSessionConnection(
                connection = transport, authenticationId = "account", expectedBareJid = active.bareJid.value,
                event = { incoming ->
                    if (incoming is SessionEvent.Incoming) {
                        val held = Thread.holdsLock(field(smack, "entryGate"))
                        observations += held
                        // Fail by ownership assertion, not a deadlocked worker or timeout.
                        if (!held) {
                            if (stopInsideDurable == false) runBlocking {
                                boundary.complete(Unit)
                                release.await()
                            }
                            event(incoming)
                        } else boundary.complete(Unit)
                    } else event(incoming)
                },
            )
            val otherOperations = RecordingConnection(configuration.id, null, null, event)
            object : SessionConnection by otherOperations {
                override val isUsable get() = smack.isUsable
                override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) {
                    otherOperations.connect(credential, attempt)
                    smack.updateAttempt(attempt)
                }
                override fun updateAttempt(attempt: SessionAttemptIdentity) { smack.updateAttempt(attempt) }
                override suspend fun sendSignal(signal: OutgoingMessageSignal) { smack.sendSignal(signal) }
                override fun revoke() {
                    smack.revoke()
                    revoked.complete(Unit)
                }
            }
        }
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val store = MessageStore.observingWrites(database, observer = { write ->
            if (stopInsideDurable == true && write == MessageWriteBoundary.AFTER_MESSAGE) runBlocking {
                boundary.complete(Unit)
                release.await()
            }
        })
        val runtime = SessionRuntime(accounts, credentials, store, PeerIdentityStore(database.messageDao()), scope, factory)
        var deliveries = 0
        runtime.onInsertedInbound = { owner, _, _, _ ->
            assertEquals(active.id, owner)
            deliveries++
        }
        try {
            accounts.save(active)
            accounts.activate(active.id)
            credentials.store(active.id, "secret".toCharArray())
            assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
            val stanza = org.jivesoftware.smack.packet.StanzaBuilder.buildMessage("receipt-wire")
                .from(org.jxmpp.jid.impl.JidCreate.entityFullFrom("peer@example.org/device"))
                .to(org.jxmpp.jid.impl.JidCreate.entityFullFrom("${active.bareJid.value}/test"))
                .ofType(if (stopInsideDurable == null) org.jivesoftware.smack.packet.Message.Type.chat
                    else org.jivesoftware.smack.packet.Message.Type.groupchat)
                .setBody("callback body")
                .apply { if (stopInsideDurable == null) addExtension(
                    org.jivesoftware.smackx.receipts.DeliveryReceiptRequest(),
                ) }
                .build()
            val listener = field(smack, "messageListener") as org.jivesoftware.smack.StanzaListener
            val incoming = scope.async { listener.processStanza(stanza) }
            if (stopInsideDurable != null) {
                withTimeout(5_000) { boundary.await() }
                assertEquals(listOf(false), observations)
                val stopping = scope.async { runtime.stop() }
                withTimeout(5_000) { revoked.await() }
                // Stop has retired the transport while the incoming callback is held.
                assertFalse(smack.isUsable)
                if (stopInsideDurable) assertFalse(stopping.isCompleted)
                release.complete(Unit)
                withTimeout(5_000) { incoming.await(); stopping.await() }
                assertTrue(runtime.state.value is ConnectionState.Stopped)
                assertEquals(if (stopInsideDurable) 1 else 0, store.messages(active.id.value).size)
                assertEquals(0, deliveries)
                assertTrue(sent.isEmpty())
            } else {
                withTimeout(5_000) { incoming.await() }
                assertEquals(listOf(false), observations)
                assertEquals(1, deliveries)
                val receipt = sent.single() as org.jivesoftware.smack.packet.Message
                assertEquals("peer@example.org/device", receipt.to.toString())
                assertEquals("receipt-wire", org.jivesoftware.smackx.receipts.DeliveryReceipt.from(receipt).id)
                assertEquals(1, store.messages(active.id.value).size)
            }
        } finally {
            release.complete(Unit)
            runtime.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `live and catchup notifications retain exact account for same peer across switch`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val store = MessageStore(database)
        val owners = listOf(account("notify-a"), account("notify-b"))
        for (owner in owners) {
            accounts.save(owner)
            credentials.store(owner.id, "secret".toCharArray())
            prepareDuplicate(store, owner.id) // Bootstrap cursor makes the next page AFTER.
            for (id in listOf("catchup-root", "live-root")) {
                assertTrue(database.messageDao().createNamedThread(
                    org.thanosapollo.nema.storage.MessageThreadEntity(owner.id.value, "peer@example.org", MessageKind.CHAT, id, null), id,
                ))
            }
        }
        val connections = mutableListOf<RecordingConnection>()
        val factory = SessionConnectionFactory { configuration, _, event ->
            val connection = RecordingConnection(configuration.id, null, null, event)
            connections += connection
            connection.archiveSupported = true
            object : SessionConnection by connection {
                override suspend fun queryArchive(request: org.thanosapollo.nema.xmpp.transport.ArchivePageRequest):
                    org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope {
                    assertEquals(org.thanosapollo.nema.xmpp.transport.ArchivePageDirection.AFTER, request.direction)
                    val message = IncomingMessageEnvelope(
                        accountId = request.accountId, generation = request.generation,
                        peer = "peer@example.org", sender = "peer@example.org", outbound = false,
                        originId = null, body = "catchup body", thread = ThreadRef(ThreadId.require("catchup-root"), ThreadId.require("false-parent")), messageId = "catchup-wire",
                        sentAtEpochMs = 2_000, sentTimeSource = MessageTimeSource.MAM,
                    )
                    return org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope(
                        request, stable = true, complete = true, hasEarlier = false,
                        firstId = "catchup", lastId = "catchup",
                        messages = listOf(org.thanosapollo.nema.xmpp.transport.ArchiveMessageEnvelope("catchup", message)),
                    )
                }
            }
        }
        val runtime = SessionRuntime(accounts, credentials, store, PeerIdentityStore(database.messageDao()), backgroundScope, factory)
        val notifications = kotlinx.coroutines.channels.Channel<Pair<Triple<AccountId, String, String>, ThreadRef?>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        runtime.onInsertedInbound = { owner, peer, body, thread ->
            notifications.trySend(Triple(owner, peer, body) to thread).getOrThrow()
        }
        try {
            for (owner in owners) {
                if (owner == owners.first()) {
                    accounts.activate(owner.id)
                    assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
                } else {
                    assertEquals(ConnectionCommandOutcome.RUNNING, runtime.activate(owner.id))
                }
                assertEquals(Triple(owner.id, "peer@example.org", "catchup body") to ThreadRef(ThreadId.require("catchup-root")), notifications.receive())
                val current = connections.last()
                withContext(Dispatchers.IO) {
                    current.emitIncoming(IncomingMessageEnvelope(
                        accountId = owner.id, generation = current.attemptIdentity.generation,
                        peer = "peer@example.org", sender = "peer@example.org", outbound = false,
                        originId = null, body = "live body", thread = ThreadRef(ThreadId.require("live-root"), ThreadId.require("false-parent")), messageId = "live-wire",
                    ))
                }
                assertEquals(Triple(owner.id, "peer@example.org", "live body") to ThreadRef(ThreadId.require("live-root")), notifications.receive())
                val child = ThreadRef(ThreadId.require("live-child"), ThreadId.require("live-root"))
                withContext(Dispatchers.IO) {
                    current.emitIncoming(IncomingMessageEnvelope(
                        accountId = owner.id, generation = current.attemptIdentity.generation,
                        peer = "peer@example.org", sender = "peer@example.org", outbound = false,
                        originId = null, body = "child body", thread = child, messageId = "child-wire",
                    ))
                }
                assertEquals(Triple(owner.id, "peer@example.org", "child body") to child, notifications.receive())
            }
            // A retired producer cannot classify or notify into the replacement account.
            val retired = connections.first()
            withContext(Dispatchers.IO) {
                retired.emitIncoming(IncomingMessageEnvelope(
                    accountId = owners.first().id, generation = retired.attemptIdentity.generation,
                    peer = "peer@example.org", sender = "peer@example.org", outbound = false,
                    originId = null, body = "retired body", thread = ThreadRef(ThreadId.require("retired-root")),
                    messageId = "retired-wire",
                ))
            }
            assertTrue(notifications.tryReceive().isFailure)
            assertFalse(store.messages(owners.first().id.value).any { it.body == "retired body" })
        } finally { runtime.stop() }
    }

    @Test
    fun `healthy session retries MAM timeout from committed cursor without reconnect or resend`() = runTest {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val store = MessageStore(database)
        val owner = account("mam-recovery")
        accounts.save(owner)
        accounts.activate(owner.id)
        credentials.store(owner.id, "secret".toCharArray())
        val requests = mutableListOf<org.thanosapollo.nema.xmpp.transport.ArchivePageRequest>()
        var connects = 0
        var reconnects = 0
        var sends = 0
        val factory = SessionConnectionFactory { configuration, _, event ->
            val connection = RecordingConnection(configuration.id, null, null, event)
            connection.archiveSupported = true
            object : SessionConnection by connection {
                override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) {
                    connects++
                    connection.connect(credential, attempt)
                }
                override suspend fun reconnect(attempt: SessionAttemptIdentity) { reconnects++ }
                override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) { sends++ }
                override suspend fun queryArchive(request: org.thanosapollo.nema.xmpp.transport.ArchivePageRequest):
                    org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope {
                    requests += request
                    if (requests.size == 2) org.thanosapollo.nema.xmpp.smack.archiveNetworkCall {
                        throw org.jivesoftware.smack.SmackException.NoResponseException.newWith(
                            5_000L, org.jivesoftware.smack.filter.StanzaFilter { true }, false,
                        )
                    }
                    val ids = if (requests.size == 1) listOf("r1") else listOf("r1", "r2")
                    return org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope(
                        request, stable = true, complete = requests.size > 1, hasEarlier = false,
                        firstId = ids.first(), lastId = ids.last(),
                        messages = ids.map { id -> org.thanosapollo.nema.xmpp.transport.ArchiveMessageEnvelope(
                            id, IncomingMessageEnvelope(
                                accountId = request.accountId, generation = request.generation,
                                peer = "peer@example.org", sender = "peer@example.org", outbound = false,
                                originId = null, body = id, thread = null, messageId = id,
                                sentAtEpochMs = 2_000, sentTimeSource = MessageTimeSource.MAM,
                            ),
                        ) },
                    )
                }
            }
        }
        val runtime = SessionRuntime(accounts, credentials, store, PeerIdentityStore(database.messageDao()), backgroundScope, factory)
        try {
            assertEquals(ConnectionCommandOutcome.RUNNING, runtime.connectActive())
            val connected = runtime.state.value
            runtime.archiveState.first { it is org.thanosapollo.nema.chat.ArchiveSyncState.WaitingToRetry }
            assertEquals("r1", store.archiveCursor(ArchiveCursorKey(owner.id.value, owner.bareJid.value, "ACCOUNT"))?.newestId)
            runCurrent()
            advanceTimeBy(2_000)
            runCurrent()
            // The retry rereads Room's committed cursor on its executor. Draining the test
            // scheduler alone does not fence that continuation or the successor page commit.
            runtime.archiveState.first { it is org.thanosapollo.nema.chat.ArchiveSyncState.Ready }
            assertEquals("A healthy lifecycle must run the next MAM query", 3, requests.size)
            assertEquals(listOf(null, "r1", "r1"), requests.map { it.boundaryId })
            assertEquals(listOf("r1", "r2"), store.messages(owner.id.value).map { it.body }.sorted())
            assertEquals(connected, runtime.state.value)
            assertEquals(1, connects)
            assertEquals(0, reconnects)
            assertEquals(0, sends)
        } finally { runtime.stop() }
    }

    @Test fun sharedDirectoryPresenterRuntimeRoomDirectAndMucJourney() = runTest {
        val fixture = connectedRuntime(backgroundScope, "local-account")
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(fixture.account, repository, backgroundScope, { _, _ -> true },
            directoryConnection = fixture.runtime.state,
            refreshDirectory = fixture.runtime::refreshThreadDirectory,
            changeDirectory = fixture.runtime::changeThreadDirectory)
        try {
            for (kind in listOf(MessageKind.CHAT, MessageKind.GROUPCHAT)) {
                val peer = if (kind == MessageKind.CHAT) REACTION_PEER else REACTION_ROOM
                if (kind == MessageKind.GROUPCHAT) repository.markRoom(fixture.account.id.value, peer)
                val directory = if (kind == MessageKind.CHAT) ThreadDirectoryScope.Direct(fixture.account.bareJid.value, peer)
                    else ThreadDirectoryScope.Muc(peer, "a".repeat(64))
                val rows = linkedMapOf<UUID, ThreadDirectoryItem>()
                val calls = mutableListOf<DirectoryAction>()
                fixture.connection.directoryRead = { requested ->
                    assertEquals(peer, when (requested) { is ThreadDirectoryScope.Direct -> requested.b; is ThreadDirectoryScope.Muc -> requested.room })
                    ThreadDirectorySnapshot(fixture.account.bareJid.value, "example.org", directory, "snapshot", rows.values.toList())
                }
                fixture.connection.directoryWrite = { action ->
                    assertEquals(directory, action.context.scope)
                    // Real Room intent is committed before the authenticated command boundary.
                    assertEquals(action.operationId.toString(), fixture.store.sharedThreads.intent(fixture.account.id.value, peer, kind)?.operationId)
                    calls += action
                    val previous = rows[action.threadId]
                    assertEquals(previous?.revision ?: 0, action.revision)
                    val row = ThreadDirectoryItem(action.threadId, action.title ?: previous!!.title,
                        action.revision + 1, action.archived ?: previous?.archived ?: false, true)
                    rows[row.id] = row
                    ThreadDirectoryMutationResult(fixture.account.bareJid.value, "example.org", directory, "snapshot", rows.size,
                        action.operationId, false, row)
                }
                presenter.selectPeer(peer)
                val main = presenter.state.first { it.selectedPeer == peer && it.contentStatus == ChatContentStatus.Ready }
                presenter.directoryState.first { it.mode == DirectoryMode.SHARED }
                assertFalse(presenter.createSharedNamedThread(main.routeOccurrence, "Stale form",
                    presenter.directoryState.value.context!!.copy(generation = -1)))
                assertTrue(presenter.createSharedNamedThread(main.routeOccurrence, "Shared project", presenter.directoryState.value.context!!))
                val selected = presenter.state.first { it.selectedPeer == peer && it.selectedThread != null && it.recentThreads.any { row -> row.shared != null } && it.contentStatus == ChatContentStatus.Ready }
                presenter.directoryState.first { it.mode == DirectoryMode.SHARED }
                assertTrue(database.messageDao().observeThreadTitles(fixture.account.id.value, peer).first().isEmpty())
                val named = selected.recentThreads.single { it.shared != null }
                val authored = presenter.directoryState.value.context
                fixture.store.ingest(IncomingMessage(fixture.account.id.value, "shared-$kind", peer,
                    if (kind == MessageKind.CHAT) peer else "$peer/member", MessageDirection.INBOUND, kind,
                    named.thread.id.value, null, "Activity while naming", null, emptyList()))
                presenter.state.first { it.recentThreads.any { row -> row.thread == named.thread && row.unreadCount == 1 } }
                // The form's old unread/reply projection must not invalidate its still-current name CAS.
                assertTrue(presenter.renameNamedThread(selected.routeOccurrence, named, "Renamed", authored))
                val renamed = presenter.state.first { it.recentThreads.any { row -> row.title == "Renamed" } }
                assertEquals(named.thread, renamed.recentThreads.single().thread)
                assertTrue(presenter.archiveNamedThread(renamed.routeOccurrence, renamed.recentThreads.single()))
                val archived = presenter.state.first { it.recentThreads.singleOrNull()?.shared?.archived == true }
                assertEquals(named.thread, archived.selectedThread)
                assertEquals(listOf(0L, 1L, 2L), calls.map { it.revision })
                assertEquals(3, calls.map { it.operationId }.distinct().size)
                assertFalse(presenter.createNamedThread(main.routeOccurrence, "Stale"))
                presenter.closeConversation()
            }
        } finally { presenter.close(); fixture.runtime.stop() }
    }

    @Test fun sharedDirectoryUncertainIntentSurvivesCallerCancellationAndRetriesExactPayload() = runTest {
        val f = connectedRuntime(backgroundScope, "local-account")
        try {
            val directory = ThreadDirectoryScope.Muc(REACTION_ROOM, "a".repeat(64))
            val action = DirectoryAction(DirectoryContext("example.org", directory, f.connection.attemptIdentity.generation.value), title = "Owned")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val actions = mutableListOf<DirectoryAction>()
            f.connection.directoryWrite = { command ->
                actions += command; entered.complete(Unit); release.await()
                throw ThreadDirectoryException.Transport()
            }
            val caller = launch { f.runtime.changeThreadDirectory(f.account, REACTION_ROOM, MessageKind.GROUPCHAT, action) }
            entered.await()
            caller.cancelAndJoin()
            assertEquals(action.copy(context = action.context.copy(generation = null)), f.store.sharedThreads.intent(f.account.id.value, REACTION_ROOM, MessageKind.GROUPCHAT)?.action(f.account.bareJid.value))
            release.complete(Unit)
            f.connection.directoryRead = { ThreadDirectorySnapshot(f.account.bareJid.value, "example.org", directory, "snapshot", emptyList()) }
            val uncertain = f.runtime.refreshThreadDirectory(f.account, REACTION_ROOM, MessageKind.GROUPCHAT)
            assertEquals(DirectoryMode.UNCERTAIN, uncertain.mode)
            f.connection.directoryWrite = { command ->
                actions += command
                ThreadDirectoryMutationResult(f.account.bareJid.value, "example.org", directory, "snapshot", 1,
                    command.operationId, true, ThreadDirectoryItem(command.threadId, command.title!!, 1, false, true))
            }
            assertTrue(f.runtime.changeThreadDirectory(f.account, REACTION_ROOM, MessageKind.GROUPCHAT, null).confirmed)
            assertEquals(listOf(action, action).map { it.copy(context = it.context.copy(generation = null)) }, actions)
            assertNull(f.store.sharedThreads.intent(f.account.id.value, REACTION_ROOM, MessageKind.GROUPCHAT))
        } finally { f.runtime.stop() }
    }

    @Test fun sharedDirectoryOldSessionSnapshotCannotPublishAfterAccountSwitch() = runTest {
        val f = connectedRuntime(backgroundScope, "old-account")
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val row = ThreadDirectoryItem(UUID.randomUUID(), "Old", 1, false, true)
            f.connection.directoryRead = { directory ->
                entered.complete(Unit); release.await()
                ThreadDirectorySnapshot(f.account.bareJid.value, "example.org", directory, "snapshot", listOf(row))
            }
            val read = async { f.runtime.refreshThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT) }
            entered.await()
            switchAccount(f, "replacement")
            release.complete(Unit)
            assertEquals(DirectoryMode.OFFLINE, read.await().mode)
            assertTrue(database.sharedThreadDao().rows(f.account.id.value, REACTION_PEER, MessageKind.CHAT).isEmpty())
            assertTrue(database.sharedThreadDao().rows("replacement", REACTION_PEER, MessageKind.CHAT).isEmpty())
        } finally { f.runtime.stop() }
    }

    @Test fun sharedDirectoryStaleGenerationCannotAuthorNewIntentAndRetryConflictRequiresExplicitReadbackSettlement() = runTest {
        val f = connectedRuntime(backgroundScope, "local-account")
        try {
            val directory = ThreadDirectoryScope.Direct(f.account.bareJid.value, REACTION_PEER)
            val context = DirectoryContext("example.org", directory, f.connection.attemptIdentity.generation.value)
            val action = DirectoryAction(context, title = "Uncertain")
            var writes = 0
            f.connection.directoryWrite = { writes++; throw ThreadDirectoryException.Transport() }
            assertFalse(f.runtime.changeThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT,
                action.copy(context = context.copy(generation = context.generation!! + 1))).confirmed)
            assertEquals(0, writes)
            assertNull(f.store.sharedThreads.intent(f.account.id.value, REACTION_PEER, MessageKind.CHAT))
            assertEquals(DirectoryMode.UNCERTAIN, f.runtime.changeThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT, action).view.mode)
            f.connection.directoryWrite = { throw ThreadDirectoryException.Conflict() }
            assertEquals(DirectoryMode.UNCERTAIN, f.runtime.changeThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT, null).view.mode)
            assertNotNull(f.store.sharedThreads.intent(f.account.id.value, REACTION_PEER, MessageKind.CHAT))
            f.connection.directoryRead = { ThreadDirectorySnapshot(f.account.bareJid.value, "example.org", directory, "snapshot", emptyList()) }
            assertFalse(f.runtime.keepCurrentThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT, UUID.randomUUID().toString()))
            assertTrue(f.runtime.keepCurrentThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT, action.operationId.toString()))
            assertNull(f.store.sharedThreads.intent(f.account.id.value, REACTION_PEER, MessageKind.CHAT))
        } finally { f.runtime.stop() }
    }

    @Test fun sharedDirectoryFailuresKeepCachedNamesAndOfflineRejectsWrites() = runTest {
        val f = connectedRuntime(backgroundScope, "local-account")
        val directory = ThreadDirectoryScope.Direct(f.account.bareJid.value, REACTION_PEER)
        val row = ThreadDirectoryItem(UUID.randomUUID(), "Shared cached", 1, false, true)
        f.connection.directoryRead = { ThreadDirectorySnapshot(f.account.bareJid.value, "example.org", directory, "snapshot", listOf(row)) }
        assertEquals(DirectoryMode.SHARED, f.runtime.refreshThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT).mode)
        f.connection.directoryRead = { throw ThreadDirectoryException.Malformed("incomplete") }
        assertEquals(DirectoryMode.ERROR, f.runtime.refreshThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT).mode)
        f.connection.directoryRead = { throw ThreadDirectoryException.Unsupported() }
        assertEquals(DirectoryMode.LOCAL_ONLY, f.runtime.refreshThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT).mode)
        f.runtime.stop()
        assertEquals(DirectoryMode.OFFLINE, f.runtime.refreshThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT).mode)
        assertFalse(f.runtime.changeThreadDirectory(f.account, REACTION_PEER, MessageKind.CHAT,
            DirectoryAction(DirectoryContext("example.org", directory), title = "Offline")).confirmed)
        assertEquals("Shared cached", database.sharedThreadDao().rows(f.account.id.value, REACTION_PEER, MessageKind.CHAT).single().title)
    }

    @Test
    fun `presenter offline join retries after runtime reconnect`() = runTest {
        val f = connectedRuntime(backgroundScope, "offline-muc")
        f.runtime.stop()
        val results = kotlinx.coroutines.channels.Channel<Boolean>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val presenter = DirectChatPresenter(
            f.account, ChatRepository(database), backgroundScope, { _, _ -> true },
            directoryConnection = f.runtime.state,
            joinMuc = { f.runtime.joinMuc(it).also { result -> results.send(result) } },
        )
        try {
            presenter.joinRoom(REACTION_ROOM)
            assertFalse(results.receive())
            assertTrue(f.connection.joinedRooms.isEmpty())
            assertEquals(ConnectionCommandOutcome.RUNNING, f.runtime.connectActive())
            // Connecting may independently produce an offline false result. Only
            // the new Connected owner can satisfy the selected-room entry.
            while (!results.receive()) Unit
            assertEquals(REACTION_ROOM, f.connections.created.last().joinedRooms.single().first)
        } finally { presenter.close(); f.runtime.stop() }
    }

    @Test
    fun `delayed room join keeps outbox pending sends direct and wakes room once`() = queuedRoomJoin("success")

    @Test
    fun `failed room join keeps outbox pending until explicit retry`() = queuedRoomJoin("false")

    @Test
    fun `cancelled room join keeps outbox pending until explicit retry`() = queuedRoomJoin("cancel")

    private fun queuedRoomJoin(outcome: String) = runTest {
        val f = connectedRuntime(backgroundScope, "queued-muc")
        val release = CompletableDeferred<Unit>()
        f.connection.requireRoomMembership = true
        f.connection.nextJoinGate = release
        fun intent(id: String, kind: MessageKind) = OutboundIntent(
            accountId = f.account.id.value, operationId = id, localMessageId = id,
            originId = id, peerJid = if (kind == MessageKind.GROUPCHAT) REACTION_ROOM else REACTION_PEER,
            senderJid = f.account.bareJid.value, messageKind = kind, threadId = null,
            parentThreadId = null, body = id,
        )
        suspend fun awaitStatus(id: String, expected: OutboxStatus) {
            val peer = if (id == "room-pending") REACTION_ROOM else REACTION_PEER
            database.messageDao().observeDirectTimeline(f.account.id.value, peer).first { rows ->
                rows.any { it.operationId == id && it.outboxStatus == expected.name }
            }
        }
        try {
            f.store.compose(intent("room-pending", MessageKind.GROUPCHAT))
            f.store.compose(intent("direct-pending", MessageKind.CHAT))
            val join = async { f.runtime.joinMuc(REACTION_ROOM) }
            f.connection.joined.await()
            // A new local compose is the ordinary dispatch wake.
            val draft = org.thanosapollo.nema.chat.DraftSnapshot(
                org.thanosapollo.nema.chat.DirectConversationKey(f.account.id.value, REACTION_PEER), "wake", 1,
            )
            assertTrue(f.runtime.enqueueDirect(f.account, draft))
            awaitStatus("direct-pending", OutboxStatus.UNCERTAIN)
            awaitStatus("room-pending", OutboxStatus.PENDING)
            assertFalse(f.connection.sentMessages.any { it.kind == MessageKind.GROUPCHAT })
            when (outcome) {
                "cancel" -> join.cancelAndJoin()
                "false" -> {
                    f.connection.nextJoinResult = false
                    release.complete(Unit)
                    assertFalse(join.await())
                }
                else -> {
                    release.complete(Unit)
                    assertTrue(join.await())
                }
            }
            if (outcome != "success") {
                runCurrent()
                assertEquals(OutboxStatus.PENDING, f.store.outbox(f.account.id.value, "room-pending")?.status)
                assertFalse(f.connection.sentMessages.any { it.kind == MessageKind.GROUPCHAT })
                assertTrue(f.runtime.joinMuc(REACTION_ROOM))
            }
            awaitStatus("room-pending", OutboxStatus.UNCERTAIN)
            assertEquals(1, f.connection.sentMessages.count { it.operationId == "room-pending" })
            assertTrue(f.runtime.joinMuc(REACTION_ROOM))
            runCurrent()
            assertEquals(1, f.connection.sentMessages.count { it.operationId == "room-pending" })
        } finally { release.complete(Unit); f.runtime.stop() }
    }

    private suspend fun connectedRuntime(
        scope: CoroutineScope, id: String, clock: () -> Long = { REACTION_NOW },
    ): RuntimeFixture {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val store = repairObserver?.let { MessageStore.observingWrites(database, observer = it) }
            ?: MessageStore(database) { REACTION_NOW }
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts, credentials, store, PeerIdentityStore(database.messageDao()), scope, connections, clock,
        )
        val account = account(id)
        accounts.save(account)
        accounts.activate(account.id)
        credentials.store(account.id, "secret".toCharArray())
        check(runtime.connectActive() == ConnectionCommandOutcome.RUNNING)
        return RuntimeFixture(
            accounts, credentials, store, connections, runtime, account, connections.created.single(),
        )
    }

    private suspend fun installDirectTarget(
        fixture: RuntimeFixture,
        localId: String,
        wireId: String,
    ) {
        fixture.store.ingest(
            IncomingMessage(
                fixture.account.id.value, localId, REACTION_PEER, REACTION_PEER,
                MessageDirection.INBOUND, MessageKind.CHAT, null, null, localId, null,
                listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, REACTION_PEER, wireId)),
            ),
        )
    }

    private suspend fun installAcceptedCorrection(fixture: RuntimeFixture) {
        val original = requireNotNull(fixture.store.resolveDirectReactionTarget(
            fixture.account.id.value, REACTION_PEER, DIRECT_TARGET.localId,
        ))
        fixture.store.ingest(
            IncomingMessage(
                fixture.account.id.value, CORRECTION.localId, REACTION_PEER, REACTION_PEER,
                MessageDirection.INBOUND, MessageKind.CHAT, null, null, "corrected", null,
                listOf(TrustedIdentityAlias(
                    IdentityAliasKind.MESSAGE_ID, REACTION_PEER, CORRECTION.wireId,
                )),
                replaceId = DIRECT_TARGET.wireId,
            ),
        )
        val stored = fixture.store.messages(fixture.account.id.value)
            .single { it.localMessageId == CORRECTION.localId }
        assertEquals(DIRECT_TARGET.wireId, stored.replaceId)
        assertEquals(original, fixture.store.resolveDirectReactionTarget(
            fixture.account.id.value, REACTION_PEER, CORRECTION.localId,
        ))
    }

    private suspend fun installSecondTarget(fixture: RuntimeFixture) =
        installDirectTarget(fixture, SECOND_TARGET.localId, SECOND_TARGET.wireId)

    private suspend fun ownReactionSnapshot(
        fixture: RuntimeFixture,
        canonicalLocalId: String,
        peer: String = REACTION_PEER,
    ) =
        database.messageDao().messageReaction(
            fixture.account.id.value,
            peer,
            fixture.account.bareJid.value,
            canonicalLocalId,
        )

    private fun expectedOwnReaction(
        fixture: RuntimeFixture,
        target: ReactionEventCase,
        emojis: String,
        updatedAtMs: Long,
        peer: String = REACTION_PEER,
    ) = MessageReactionEntity(
        fixture.account.id.value, peer, fixture.account.bareJid.value,
        target.localId, target.localId, target.wireId, emojis, updatedAtMs, 1,
    )

    private fun outgoingReaction(
        fixture: RuntimeFixture,
        emojis: List<String>,
        wireId: String,
        peer: String = REACTION_PEER,
        kind: MessageKind = MessageKind.CHAT,
    ) = OutgoingReactionEnvelope(
        fixture.account.id, fixture.connection.attemptIdentity.generation,
        peer, wireId, emojis, kind,
    )

    private suspend fun installGroupTarget(fixture: RuntimeFixture, target: ReactionEventCase) {
        fixture.store.ingest(IncomingMessage(
            fixture.account.id.value, target.localId, REACTION_ROOM, "$REACTION_ROOM/alice",
            MessageDirection.INBOUND, MessageKind.GROUPCHAT, null, null, target.localId, null,
            listOf(TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, REACTION_ROOM, target.wireId)),
        ))
    }

    private fun incomingReaction(
        fixture: RuntimeFixture,
        attempt: SessionAttemptIdentity,
        wireId: String,
        emojis: List<String>,
        delayedAtMs: Long?,
    ) = IncomingReactionEnvelope(
        attempt.accountId, attempt.generation, fixture.account.bareJid.value,
        REACTION_PEER, REACTION_PEER, wireId, emojis, delayedAtMs,
    )

    private suspend fun switchAccount(fixture: RuntimeFixture, id: String): RuntimeFixture {
        val replacement = account(id)
        fixture.accounts.save(replacement)
        fixture.credentials.store(replacement.id, "secret".toCharArray())
        assertEquals(ConnectionCommandOutcome.RUNNING, fixture.runtime.activate(replacement.id))
        return fixture.copy(
            account = replacement,
            connection = fixture.connections.created.last(),
        )
    }

    private fun emitOldAttemptReaction(
        fixture: RuntimeFixture,
        attempt: SessionAttemptIdentity,
        delayedAtMs: Long,
    ) {
        val reaction = incomingReaction(
            fixture, attempt, DIRECT_TARGET.wireId, listOf("🔥"), delayedAtMs,
        ).copy(senderBareJid = fixture.account.bareJid.value)
        fixture.connection.emitReaction(attempt, reaction)
    }

    private suspend fun TestScope.completeReconnect(
        fixture: RuntimeFixture,
        oldAttempt: SessionAttemptIdentity,
        onWaiting: () -> Unit = {},
    ): SessionAttemptIdentity {
        assertEquals(oldAttempt, fixture.connection.attemptIdentity)
        fixture.connection.emitLoss(SessionFailureReason.NETWORK)
        assertEquals(
            ConnectionState.ReconnectWait(oldAttempt.accountId, oldAttempt.generation),
            fixture.runtime.state.first { it is ConnectionState.ReconnectWait },
        )
        onWaiting()
        repeat(7) { elapsedSeconds ->
            runCurrent()
            val connected = fixture.runtime.state.value as? ConnectionState.Connected
            if (
                connected?.accountId == oldAttempt.accountId &&
                connected.generation.value > oldAttempt.generation.value
            ) {
                val currentAttempt = fixture.connection.attemptIdentity
                assertEquals(oldAttempt.generation.value + 1, currentAttempt.generation.value)
                assertEquals(connected, ConnectionState.Connected(currentAttempt.accountId, currentAttempt.generation))
                return currentAttempt
            }
            if (elapsedSeconds < 6) advanceTimeBy(1_000L)
        }
        throw AssertionError("Reconnect did not complete within 6 seconds of virtual time")
    }

    private fun account(id: String, bareJid: String = "$id@example.org") = AccountConfiguration.create(
        id = AccountId.require(id),
        bareJid = bareJid,
        authenticationId = id,
        authorizationId = null,
        serviceDomain = "example.org",
        networkEndpoint = null,
    )

    private suspend fun prepareDuplicate(store: MessageStore, accountId: AccountId) {
        fun message(id: String, source: MessageTimeSource) = IncomingMessage(
            accountId.value,
            id,
            "peer@example.org",
            "peer@example.org",
            MessageDirection.INBOUND,
            MessageKind.CHAT,
            null,
            null,
            "same",
            null,
            emptyList(),
            sentAtEpochMs = 1_000,
            sentTimeSource = source,
        )
        store.ingest(message("live", MessageTimeSource.LOCAL))
        store.applyArchivePage(
            ArchivePage(
                ArchiveCursorKey(accountId.value, "${accountId.value}@example.org", "ACCOUNT"),
                ArchiveDirection.BOOTSTRAP,
                null,
                complete = false,
                hasEarlier = false,
                stable = true,
                firstId = "result",
                lastId = "result",
                messages = listOf(ArchivedIncomingMessage("result", message("mam", MessageTimeSource.MAM))),
            ),
        )
    }

    private class MemoryBlobStore : CredentialBlobStore {
        private val values = mutableMapOf<AccountId, WrappedCredential>()

        override fun read(accountId: AccountId): WrappedCredential? = values[accountId]

        override fun write(accountId: AccountId, credential: WrappedCredential) {
            values[accountId] = credential
        }

        override fun delete(accountId: AccountId) {
            values.remove(accountId)
        }
    }

    private class PlaintextTestCipher : CredentialCipher {
        override fun encrypt(accountId: AccountId, plaintext: ByteArray) =
            WrappedCredential(byteArrayOf(1), plaintext.copyOf())

        override fun decrypt(accountId: AccountId, credential: WrappedCredential) =
            credential.ciphertext.copyOf()

        override fun deleteKey(accountId: AccountId) = Unit
    }

    private data class ReactionCallback(
        val attempt: SessionAttemptIdentity,
        val reaction: IncomingReactionEnvelope,
    )

    private data class RuntimeFixture(
        val accounts: AccountRepository,
        val credentials: CredentialVault,
        val store: MessageStore,
        val connections: RecordingConnectionFactory,
        val runtime: SessionRuntime,
        val account: AccountConfiguration,
        val connection: RecordingConnection,
    )

    private data class ReactionEventCase(val localId: String, val wireId: String, val delayedAtMs: Long?)

    private companion object {
        const val REACTION_PEER = "peer@example.org"
        const val REACTION_ROOM = "room@conference.example.org"
        const val REACTION_NOW = 8_000L
        val DIRECT_TARGET = ReactionEventCase("reaction-local", "reaction-wire", null)
        val CORRECTION = ReactionEventCase("correction-local", "correction-wire", null)
        val SECOND_TARGET = ReactionEventCase("second-local", "second-wire", 4_200L)
        val GROUP_TARGET = ReactionEventCase("group-local", "room-sid", null)
        val SECOND_GROUP_TARGET = ReactionEventCase("group-second", "room-sid-2", null)
    }

    private data class ReactionSendStep(
        val entered: CompletableDeferred<Unit> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
        val failure: Throwable? = null,
        val callback: ReactionCallback? = null,
    )

    private class RecordingConnectionFactory(
        private val connectionStarted: CompletableDeferred<Unit>? = null,
        private val releaseConnection: CompletableDeferred<Unit>? = null,
        private val onCreate: ((AccountId) -> Unit)? = null,
        private val firstBookmarks: List<RoomBookmark> = emptyList(),
        private val archiveSupported: Boolean = false,
    ) : SessionConnectionFactory {
        val created = mutableListOf<RecordingConnection>()

        override fun create(
            configuration: AccountConfiguration,
            identity: org.thanosapollo.nema.session.SessionIdentity,
            event: (org.thanosapollo.nema.session.SessionEvent) -> Unit,
        ): RecordingConnection {
            onCreate?.invoke(configuration.id)
            return RecordingConnection(configuration.id, connectionStarted, releaseConnection, event).also {
                if (created.isEmpty()) it.bookmarks = firstBookmarks
                it.archiveSupported = archiveSupported
                created += it
            }
        }
    }

    private class RecordingConnection(
        val accountId: AccountId,
        private val connectionStarted: CompletableDeferred<Unit>?,
        private val releaseConnection: CompletableDeferred<Unit>?,
        private val event: (SessionEvent) -> Unit,
    ) : SessionConnection {
        var directoryRead: suspend (ThreadDirectoryScope) -> ThreadDirectorySnapshot = { throw ThreadDirectoryException.Unsupported() }
        var directoryWrite: suspend (DirectoryAction) -> ThreadDirectoryMutationResult = { throw ThreadDirectoryException.Unsupported() }
        override suspend fun listThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, directory: ThreadDirectoryScope): ThreadDirectorySnapshot {
            assertEquals(this.accountId, accountId)
            assertEquals(attemptIdentity.generation, generation)
            return directoryRead(directory)
        }
        override suspend fun mutateThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, action: DirectoryAction): ThreadDirectoryMutationResult {
            assertEquals(this.accountId, accountId)
            assertEquals(attemptIdentity.generation, generation)
            return directoryWrite(action)
        }
        override var isUsable = true
        var disconnectCalls = 0
        lateinit var attemptIdentity: SessionAttemptIdentity
        var bookmarks: List<RoomBookmark> = emptyList()
        var bookmarkReadComplete = true
        val initialBookmarkReadCompleted = CompletableDeferred<Unit>()
        val gatedBookmarkReadEntered = CompletableDeferred<Unit>()
        var nextBookmarkReadGate: CompletableDeferred<Unit>? = null
        val publishedBookmarks = mutableListOf<RoomBookmark>()
        val joinedRooms = mutableListOf<Triple<String, String?, String?>>()
        val joined = CompletableDeferred<Unit>()
        var nextJoinGate: CompletableDeferred<Unit>? = null
        var nextJoinResult = true
        val published = CompletableDeferred<Unit>()
        val roomArchiveRequested = CompletableDeferred<Unit>()
        val roomArchiveJob = CompletableDeferred<Job>()
        var roomArchiveResponse: suspend (org.thanosapollo.nema.xmpp.transport.ArchivePageRequest) ->
            org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope? = { null }
        val repairRooms = mutableSetOf<String>()
        val repairGate = Any()
        var repairRegistry = org.thanosapollo.nema.xmpp.smack.RoomStableIdAuthorityRegistry()
        val repairArchive = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override fun roomArchiveAuthorization(room: String) =
            org.thanosapollo.nema.xmpp.smack.captureRoomArchiveAuthorization(repairGate, repairRegistry, room) {
                attemptIdentity.takeIf { isUsable }
            }
        var authorizedRoomQuery: (suspend (org.thanosapollo.nema.xmpp.transport.ArchivePageRequest,
            org.thanosapollo.nema.session.RoomArchiveAuthorization) ->
            org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope)? = null
        override suspend fun queryArchive(
            request: org.thanosapollo.nema.xmpp.transport.ArchivePageRequest,
            authorization: org.thanosapollo.nema.session.RoomArchiveAuthorization,
        ): org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope {
            authorizedRoomQuery?.let { return it(request, authorization) }
            if (!authorization.admit()) throw org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException()
            return queryArchive(request)
        }
        var archiveSupported = false
        val archiveRequests = mutableListOf<org.thanosapollo.nema.xmpp.transport.ArchivePageRequest>()
        val sentTyping = mutableListOf<org.thanosapollo.nema.xmpp.transport.OutgoingChatState>()
        override suspend fun sendChatState(state: org.thanosapollo.nema.xmpp.transport.OutgoingChatState) {
            sentTyping += state
        }

        val sentSignals = mutableListOf<OutgoingMessageSignal>()
        val sentReactions = mutableListOf<OutgoingReactionEnvelope>()
        val reactionSteps = ArrayDeque<ReactionSendStep>()

        override fun revoke() {
            isUsable = false
        }

        override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) {
            attemptIdentity = attempt
            repairRegistry.begin(attempt)
            connectionStarted?.complete(Unit)
            releaseConnection?.await()
            isUsable = true
        }

        override suspend fun reconnect(attempt: SessionAttemptIdentity) {
            attemptIdentity = attempt
            repairRegistry.begin(attempt)
            isUsable = true
        }

        override fun updateAttempt(attempt: SessionAttemptIdentity) {
            attemptIdentity = attempt
            repairRegistry.begin(attempt)
        }

        var requireRoomMembership = false
        val sentMessages = mutableListOf<OutgoingMessageEnvelope>()
        override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) {
            if (requireRoomMembership && message.kind == MessageKind.GROUPCHAT &&
                repairRegistry.snapshot(attemptIdentity, message.recipient) == null) {
                throw org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException()
            }
            entered()
            sentMessages += message
        }

        override suspend fun sendSignal(signal: OutgoingMessageSignal) {
            sentSignals += signal
        }

        override suspend fun sendReaction(reaction: OutgoingReactionEnvelope) {
            val step = reactionSteps.removeFirst()
            sentReactions += reaction
            step.entered.complete(Unit)
            step.callback?.let { emitReaction(it.attempt, it.reaction) }
            step.release.await()
            step.failure?.let { throw it }
        }

        fun queueReaction(
            failure: Throwable? = null,
            callback: ReactionCallback? = null,
            released: Boolean = false,
        ): ReactionSendStep = ReactionSendStep(failure = failure, callback = callback).also {
            if (released) it.release.complete(Unit)
            reactionSteps.addLast(it)
        }

        override suspend fun joinMuc(
            accountId: AccountId,
            generation: org.thanosapollo.nema.xmpp.transport.ConnectionGeneration,
            roomJid: String,
            nick: String?,
            password: String?,
        ): Boolean {
            joinedRooms += Triple(roomJid, nick, password)
            joined.complete(Unit)
            val gate = nextJoinGate
            nextJoinGate = null
            gate?.await()
            if (!nextJoinResult) {
                nextJoinResult = true
                return false
            }
            synchronized(repairGate) {
                val lease = requireNotNull(repairRegistry.beginJoin(attemptIdentity, roomJid))
                repairRegistry.publish(lease, stableIds = false, occupantIds = false, mamV2 = roomJid in repairRooms)
            }
            return true
        }

        val firstBookmarkReadJob = CompletableDeferred<Job>()

        override suspend fun bookmarkedRoomDetails(
            accountId: AccountId,
            generation: org.thanosapollo.nema.xmpp.transport.ConnectionGeneration,
        ): RoomBookmarkSnapshot {
            firstBookmarkReadJob.complete(requireNotNull(currentCoroutineContext()[Job]))
            val gate = nextBookmarkReadGate
            nextBookmarkReadGate = null
            if (gate != null) {
                gatedBookmarkReadEntered.complete(Unit)
                gate.await()
            }
            val snapshot = RoomBookmarkSnapshot(bookmarks, complete = bookmarkReadComplete)
            initialBookmarkReadCompleted.complete(Unit)
            return snapshot
        }

        override suspend fun discoverCapabilities(accountId: AccountId, generation: ConnectionGeneration) =
            org.thanosapollo.nema.xmpp.transport.SessionCapabilities(
                archiveSupported, org.thanosapollo.nema.xmpp.transport.CarbonCapabilityState.UNSUPPORTED, false,
            )

        override suspend fun queryArchive(request: org.thanosapollo.nema.xmpp.transport.ArchivePageRequest):
            org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope {
            archiveRequests += request
            if (request.scope != "ACCOUNT") {
                roomArchiveJob.complete(requireNotNull(currentCoroutineContext()[Job]))
                roomArchiveRequested.complete(Unit)
                repairArchive.send(request.scope)
                roomArchiveResponse(request)?.let { return it }
            }
            return org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope(
                request, stable = true, complete = true, hasEarlier = false,
                firstId = null, lastId = null, messages = emptyList(),
            )
        }

        override suspend fun publishRoomBookmark(
            accountId: AccountId,
            generation: org.thanosapollo.nema.xmpp.transport.ConnectionGeneration,
            bookmark: RoomBookmark,
        ): Boolean {
            publishedBookmarks += bookmark
            published.complete(Unit)
            bookmarks = bookmarks.filterNot { it.roomJid == bookmark.roomJid } + bookmark
            return true
        }

        override suspend fun disconnect() {
            disconnectCalls++
            isUsable = false
        }

        fun emitEphemeral(attempt: SessionAttemptIdentity = attemptIdentity) {
            event(SessionEvent.ChatState(attempt, org.thanosapollo.nema.xmpp.transport.IncomingChatState(
                attempt.accountId, attempt.generation, REACTION_PEER, REACTION_PEER, false,
                org.thanosapollo.nema.xmpp.chatstates.ChatActivity.COMPOSING,
            )))
            event(SessionEvent.RealTimeText(attempt, org.thanosapollo.nema.xmpp.transport.IncomingRealTimeText(
                attempt.accountId, attempt.generation, REACTION_PEER,
                org.thanosapollo.nema.xmpp.rtt.RttElement(0, org.thanosapollo.nema.xmpp.rtt.RttEvent.NEW,
                    listOf(org.thanosapollo.nema.xmpp.rtt.RttAction.Insert(null, "draft"))), false,
            )))
            event(SessionEvent.RoomUpdated(attempt, org.thanosapollo.nema.xmpp.muc.RoomView(REACTION_ROOM, subject = "subject")))
        }

        fun emitIncoming(message: IncomingMessageEnvelope) {
            event(SessionEvent.Incoming(attemptIdentity, message))
        }

        fun emitFailure(operationId: String, peer: String) {
            event(
                SessionEvent.OutgoingFailure(
                    attemptIdentity,
                    OutgoingFailureEnvelope(operationId, peer, "remote-server-timeout"),
                ),
            )
        }

        fun emitRoster(
            snapshot: CompleteRosterSnapshot,
            attempt: SessionAttemptIdentity = attemptIdentity,
        ) {
            event(SessionEvent.RosterSnapshot(attempt, snapshot))
        }

        fun emitLoss(reason: SessionFailureReason) {
            event(SessionEvent.ConnectionLost(attemptIdentity, reason))
        }

        fun emitReaction(attempt: SessionAttemptIdentity, reaction: IncomingReactionEnvelope) {
            event(SessionEvent.Reaction(attempt, reaction))
        }

        fun emitRoom(view: org.thanosapollo.nema.xmpp.muc.RoomView) {
            event(SessionEvent.RoomUpdated(attemptIdentity, view))
        }
    }
}
