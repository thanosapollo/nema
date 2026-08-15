package org.thanosapollo.nema.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.ArchiveCursorKey
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.IdentityAliasKind
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageEntity
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.TrustedIdentityAlias
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.xmpp.transport.ACCOUNT_ARCHIVE_SCOPE
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ArchiveMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.ArchivePageDirection
import org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope
import org.thanosapollo.nema.xmpp.transport.ArchivePageRequest
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.SessionCapabilities
import org.thanosapollo.nema.xmpp.transport.StanzaIdEnvelope

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArchiveSynchronizerTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private lateinit var store: MessageStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "archive-sync-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().upsert(
            AccountEntity(ACCOUNT, BARE_JID, ACCOUNT, null, "example.org", null, null),
        )
        store = MessageStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun synchronizeDiscoversCatchesUpAndBackfillsAllAvailableHistory() = runBlocking {
        val requests = mutableListOf<ArchivePageRequest>()
        val localIds = ArrayDeque(listOf("latest", "middle", "latest-repeat", "older", "middle-repeat"))
        val synchronizer = ArchiveSynchronizer(
            store = store,
            discover = { CAPABILITIES },
            query = { request ->
                requests += request
                when (request.direction) {
                    ArchivePageDirection.BOOTSTRAP -> page(
                        request = request,
                        complete = false,
                        hasEarlier = true,
                        messages = listOf(message("r2", "latest")),
                    )
                    ArchivePageDirection.AFTER -> page(
                        request = request,
                        complete = true,
                        hasEarlier = true,
                    )
                    ArchivePageDirection.BEFORE -> when (request.boundaryId) {
                        "r2" -> page(
                            request = request,
                            complete = false,
                            hasEarlier = true,
                            messages = listOf(
                                message("r1", "middle"),
                                message("r2", "latest"),
                            ),
                        )
                        "r1" -> page(
                            request = request,
                            complete = true,
                            hasEarlier = false,
                            messages = listOf(
                                message("r0", "older"),
                                message("r1", "middle"),
                            ),
                        )
                        else -> error("unexpected boundary ${request.boundaryId}")
                    }
                }
            },
            localIds = { localIds.removeFirst() },
        )

        synchronizer.synchronize(IDENTITY, BARE_JID) { true }
        assertTrue(synchronizer.state.value is ArchiveSyncState.Ready)
        assertEquals(
            listOf(
                ArchivePageDirection.BOOTSTRAP,
                ArchivePageDirection.AFTER,
                ArchivePageDirection.BEFORE,
                ArchivePageDirection.BEFORE,
            ),
            requests.map(ArchivePageRequest::direction),
        )
        requests.forEach {
            assertEquals(IDENTITY.accountId, it.accountId)
            assertEquals(IDENTITY.generation, it.generation)
            assertEquals(BARE_JID, it.archiveAuthority)
            assertEquals(ACCOUNT_ARCHIVE_SCOPE, it.scope)
        }

        assertEquals(ArchivePageDirection.BEFORE, requests.last().direction)
        assertEquals(listOf(null, "r2", "r2", "r1"), requests.map(ArchivePageRequest::boundaryId))
        assertEquals(listOf("older", "middle", "latest"), store.messages(ACCOUNT).map(MessageEntity::body))
        assertEquals(
            "r0",
            store.archiveCursor(ArchiveCursorKey(ACCOUNT, BARE_JID, ACCOUNT_ARCHIVE_SCOPE))?.oldestId,
        )
    }

    @Test
    fun unsupportedMalformedAndRetiredSessionsNeverInstallArchiveData() = runBlocking {
        var queries = 0
        val unsupported = ArchiveSynchronizer(
            store = store,
            discover = { CAPABILITIES.copy(mamV2 = false) },
            query = { error("query must not run") },
        )
        unsupported.synchronize(IDENTITY, BARE_JID) { true }
        assertTrue(unsupported.state.value is ArchiveSyncState.Unsupported)

        val malformed = ArchiveSynchronizer(
            store = store,
            discover = { CAPABILITIES },
            query = { request ->
                queries++
                page(
                    request = request.copy(scope = "wrong"),
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(message("r1", "body")),
                )
            },
        )
        malformed.synchronize(IDENTITY, BARE_JID) { true }
        assertTrue(malformed.state.value is ArchiveSyncState.RetryableError)
        assertTrue(store.messages(ACCOUNT).isEmpty())

        val wrongMessageGeneration = ArchiveSynchronizer(
            store = store,
            discover = { CAPABILITIES },
            query = { request ->
                queries++
                page(
                    request = request,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(
                        message("r1", "body").let {
                            it.copy(
                                message = requireNotNull(it.message).copy(
                                    generation = ConnectionGeneration.require(8),
                                ),
                            )
                        },
                    ),
                )
            },
        )
        wrongMessageGeneration.synchronize(IDENTITY, BARE_JID) { true }
        assertTrue(wrongMessageGeneration.state.value is ArchiveSyncState.RetryableError)
        assertTrue(store.messages(ACCOUNT).isEmpty())

        var authoritative = true
        val retired = ArchiveSynchronizer(
            store = store,
            discover = { CAPABILITIES },
            query = { request ->
                queries++
                page(
                    request,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(message("r2", "stale")),
                ).also {
                    authoritative = false
                }
            },
        )
        retired.synchronize(IDENTITY, BARE_JID) { authoritative }
        assertFalse(authoritative)
        assertTrue(store.messages(ACCOUNT).isEmpty())
        assertEquals(3, queries)
    }

    @Test
    fun replayedArchiveIdentityConflictRemainsRetryableInsteadOfStorageFatal() = runBlocking {
        store.ingest(
            message("live-id", "first").toIncomingMessage("live-id"),
        )
        val rejected = ArchiveSynchronizer(
            store = store,
            discover = { CAPABILITIES },
            query = { request ->
                page(
                    request = request,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(message("mam-live-id", "different", stanzaId = "live-id")),
                )
            },
        )

        try {
            rejected.synchronize(IDENTITY, BARE_JID) { true }
        } catch (failure: ArchiveStorageFailure) {
            fail("archive identity conflict was misclassified as storage failure: ${failure.cause}")
        }

        assertTrue(rejected.state.value is ArchiveSyncState.RetryableError)
    }

    @Test
    fun backfillIdentityConflictRemainsRetryableInsteadOfStorageFatal() = runBlocking {
        store.ingest(
            message("r1", "first").toIncomingMessage("live-id"),
        )
        val rejected = ArchiveSynchronizer(
            store = store,
            discover = { CAPABILITIES },
            query = { request ->
                when (request.direction) {
                    ArchivePageDirection.BOOTSTRAP -> page(
                        request = request,
                        complete = true,
                        hasEarlier = true,
                        messages = listOf(message("r2", "latest")),
                    )
                    ArchivePageDirection.BEFORE -> page(
                        request = request,
                        complete = true,
                        hasEarlier = false,
                        messages = listOf(message("mam-r1", "different", stanzaId = "r1")),
                    )
                    ArchivePageDirection.AFTER -> error("catch-up must not run")
                }
            },
        )
        rejected.synchronize(IDENTITY, BARE_JID) { true }

        val backfilled = try {
            rejected.backfillOnePage(IDENTITY, BARE_JID) { true }
        } catch (failure: ArchiveStorageFailure) {
            fail("backfill identity conflict was misclassified as storage failure: ${failure.cause}")
            true
        }

        assertFalse(backfilled)
        assertTrue(rejected.state.value is ArchiveSyncState.RetryableError)
    }

    @Test
    fun ordinaryCommitIllegalArgumentRemainsStorageFailure() = runBlocking {
        val cause = IllegalArgumentException("database framework failure")
        val failing = ArchiveSynchronizer(
            store = store,
            discover = { CAPABILITIES },
            query = { request ->
                page(
                    request = request,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(message("r1", "body")),
                )
            },
            commit = { _, _, _ -> throw cause },
        )

        val observed = runCatching {
            failing.synchronize(IDENTITY, BARE_JID) { true }
        }.exceptionOrNull()

        assertTrue(observed is ArchiveStorageFailure)
        assertEquals(cause, observed?.cause)
    }

    @Test
    fun mamRecoveryPreservesChildThreadLineage() = runBlocking {
        val thread = ThreadRef(ThreadId.require("child-thread"), ThreadId.require("parent-thread"))
        val synchronizer = ArchiveSynchronizer(
            store = store,
            discover = { CAPABILITIES },
            query = { request ->
                page(
                    request = request,
                    complete = true,
                    hasEarlier = false,
                    messages = if (request.direction == ArchivePageDirection.BOOTSTRAP) {
                        listOf(message("r-thread", "thread body", thread))
                    } else {
                        emptyList()
                    },
                )
            },
        )

        synchronizer.synchronize(IDENTITY, BARE_JID) { true }

        val recovered = store.messages(ACCOUNT).single()
        assertEquals("child-thread", recovered.threadId)
        assertEquals("parent-thread", recovered.parentThreadId)
    }

    private fun page(
        request: ArchivePageRequest,
        complete: Boolean,
        hasEarlier: Boolean,
        messages: List<ArchiveMessageEnvelope> = emptyList(),
    ) = ArchivePageEnvelope(
        request = request,
        stable = true,
        complete = complete,
        hasEarlier = hasEarlier,
        firstId = messages.firstOrNull()?.resultId,
        lastId = messages.lastOrNull()?.resultId,
        messages = messages,
    )

    private fun message(
        resultId: String,
        body: String,
        thread: ThreadRef? = null,
        stanzaId: String? = null,
    ) = ArchiveMessageEnvelope(
        resultId = resultId,
        message = IncomingMessageEnvelope(
            accountId = IDENTITY.accountId,
            generation = IDENTITY.generation,
            peer = PEER,
            sender = PEER,
            outbound = false,
            originId = null,
            body = body,
            thread = thread,
            stanzaIds = stanzaId?.let { listOf(StanzaIdEnvelope(it, BARE_JID)) }.orEmpty(),
        ),
    )

    private fun ArchiveMessageEnvelope.toIncomingMessage(localId: String) =
        IncomingMessage(
            accountId = ACCOUNT,
            localMessageId = localId,
            peerJid = PEER,
            senderJid = PEER,
            direction = MessageDirection.INBOUND,
            messageKind = MessageKind.CHAT,
            threadId = null,
            parentThreadId = null,
            body = requireNotNull(message).body,
            archiveOrdinal = null,
            aliases = listOf(
                TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, BARE_JID, resultId),
            ),
        )

    companion object {
        private const val ACCOUNT = "account"
        private const val BARE_JID = "account@example.org"
        private const val PEER = "peer@example.org"
        private val IDENTITY = SessionIdentity(
            AccountId.require(ACCOUNT),
            ConnectionGeneration.require(7),
        )
        private val CAPABILITIES = SessionCapabilities(
            mamV2 = true,
            carbons = true,
            carbonsEnabled = true,
            stableIds = true,
        )
    }
}
