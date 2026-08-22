package org.thanosapollo.nema.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.session.SessionConnection
import org.thanosapollo.nema.session.SessionConnectionFactory
import org.thanosapollo.nema.session.SessionEvent
import org.thanosapollo.nema.storage.AccountRepository
import org.thanosapollo.nema.storage.ArchiveCursorKey
import org.thanosapollo.nema.storage.ArchiveDirection
import org.thanosapollo.nema.storage.ArchivePage
import org.thanosapollo.nema.storage.ArchivedIncomingMessage
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageWriteBoundary
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.OutboundIntent
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.storage.PeerIdentityStore
import org.thanosapollo.nema.storage.ReconciliationRepairStatus
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.bookmarks.RoomBookmark
import org.thanosapollo.nema.xmpp.bookmarks.RoomBookmarkSnapshot
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.MessageReceiptStage
import org.thanosapollo.nema.xmpp.transport.MessageSignalProtocol
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource
import org.thanosapollo.nema.xmpp.transport.OutgoingFailureEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageSignal

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

        fun emitRoom(view: org.thanosapollo.nema.xmpp.muc.RoomView) {
            event(SessionEvent.RoomUpdated(attemptIdentity, view))
        }
    }
}
