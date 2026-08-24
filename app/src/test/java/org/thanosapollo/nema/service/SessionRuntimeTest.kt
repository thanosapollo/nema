package org.thanosapollo.nema.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.thanosapollo.nema.storage.AccountRepository
import org.thanosapollo.nema.storage.ArchiveCursorKey
import org.thanosapollo.nema.storage.ArchiveDirection
import org.thanosapollo.nema.storage.ArchivePage
import org.thanosapollo.nema.storage.ArchivedIncomingMessage
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
import org.thanosapollo.nema.storage.TrustedIdentityAlias
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
        val releaseFirstRead = CompletableDeferred<Unit>()
        connection.nextBookmarkReadGate = releaseFirstRead
        val room = "coven@conference.example.org"

        assertTrue(runtime.joinMuc(room, nick = "Old"))
        runCurrent()
        assertTrue(runtime.joinMuc(room, nick = "New"))
        runCurrent()
        assertTrue(connection.publishedBookmarks.isEmpty())

        releaseFirstRead.complete(Unit)
        runCurrent()
        assertEquals(listOf("Old", "New"), connection.publishedBookmarks.map(RoomBookmark::nick))
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
        val releaseOldRead = CompletableDeferred<Unit>()
        oldConnection.nextBookmarkReadGate = releaseOldRead

        assertTrue(runtime.joinMuc("old@conference.example.org", nick = "Old"))
        runCurrent()
        assertEquals(ConnectionCommandOutcome.RUNNING, runtime.activate(second.id))
        val newConnection = connections.created.last()
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
        val same = async { fixture.runtime.reactTo(REACTION_ROOM, GROUP_TARGET.localId, "👍") }
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
        val second = async { fixture.runtime.reactTo(REACTION_PEER, CORRECTION.localId, "👍") }
        runCurrent()
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
        val queued = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            fixture.runtime.reactTo(REACTION_PEER, CORRECTION.localId, "👍")
        }
        fixture.runtime.activeAccount.first()
        runCurrent()
        requireNotNull(fixture.store.resolveDirectReactionTarget(
            fixture.account.id.value, REACTION_PEER, CORRECTION.localId))
        runCurrent()

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

    private suspend fun connectedRuntime(scope: CoroutineScope, id: String): RuntimeFixture {
        val accounts = AccountRepository(database.accountDao())
        val credentials = CredentialVault(MemoryBlobStore(), PlaintextTestCipher())
        val store = MessageStore(database) { REACTION_NOW }
        val connections = RecordingConnectionFactory()
        val runtime = SessionRuntime(
            accounts, credentials, store, PeerIdentityStore(database.messageDao()), scope, connections,
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
    ): SessionAttemptIdentity {
        assertEquals(oldAttempt, fixture.connection.attemptIdentity)
        fixture.connection.emitLoss(SessionFailureReason.NETWORK)
        assertEquals(
            ConnectionState.ReconnectWait(oldAttempt.accountId, oldAttempt.generation),
            fixture.runtime.state.first { it is ConnectionState.ReconnectWait },
        )
        repeat(3) {
            runCurrent()
            advanceTimeBy(1_000L)
        }
        runCurrent()
        val currentAttempt = fixture.connection.attemptIdentity
        assertEquals(oldAttempt.generation.value + 1, currentAttempt.generation.value)
        assertEquals(
            ConnectionState.Connected(currentAttempt.accountId, currentAttempt.generation),
            fixture.runtime.state.value,
        )
        return currentAttempt
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
    ) : SessionConnectionFactory {
        val created = mutableListOf<RecordingConnection>()

        override fun create(
            configuration: AccountConfiguration,
            identity: org.thanosapollo.nema.session.SessionIdentity,
            event: (org.thanosapollo.nema.session.SessionEvent) -> Unit,
        ): RecordingConnection {
            onCreate?.invoke(configuration.id)
            return RecordingConnection(configuration.id, connectionStarted, releaseConnection, event).also(created::add)
        }
    }

    private class RecordingConnection(
        val accountId: AccountId,
        private val connectionStarted: CompletableDeferred<Unit>?,
        private val releaseConnection: CompletableDeferred<Unit>?,
        private val event: (SessionEvent) -> Unit,
    ) : SessionConnection {
        override var isUsable = true
        var disconnectCalls = 0
        lateinit var attemptIdentity: SessionAttemptIdentity
        var bookmarks: List<RoomBookmark> = emptyList()
        var bookmarkReadComplete = true
        var nextBookmarkReadGate: CompletableDeferred<Unit>? = null
        val publishedBookmarks = mutableListOf<RoomBookmark>()
        val sentSignals = mutableListOf<OutgoingMessageSignal>()
        val sentReactions = mutableListOf<OutgoingReactionEnvelope>()
        val reactionSteps = ArrayDeque<ReactionSendStep>()

        override fun revoke() {
            isUsable = false
        }

        override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) {
            attemptIdentity = attempt
            connectionStarted?.complete(Unit)
            releaseConnection?.await()
            isUsable = true
        }

        override suspend fun reconnect(attempt: SessionAttemptIdentity) {
            attemptIdentity = attempt
            isUsable = true
        }

        override fun updateAttempt(attempt: SessionAttemptIdentity) {
            attemptIdentity = attempt
        }

        override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) = entered()

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
        ): Boolean = true

        override suspend fun bookmarkedRoomDetails(
            accountId: AccountId,
            generation: org.thanosapollo.nema.xmpp.transport.ConnectionGeneration,
        ): RoomBookmarkSnapshot {
            val gate = nextBookmarkReadGate
            nextBookmarkReadGate = null
            gate?.await()
            return RoomBookmarkSnapshot(bookmarks, complete = bookmarkReadComplete)
        }

        override suspend fun publishRoomBookmark(
            accountId: AccountId,
            generation: org.thanosapollo.nema.xmpp.transport.ConnectionGeneration,
            bookmark: RoomBookmark,
        ): Boolean {
            publishedBookmarks += bookmark
            bookmarks = bookmarks.filterNot { it.roomJid == bookmark.roomJid } + bookmark
            return true
        }

        override suspend fun disconnect() {
            disconnectCalls++
            isUsable = false
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
