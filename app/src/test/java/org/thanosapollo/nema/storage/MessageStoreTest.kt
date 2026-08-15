package org.thanosapollo.nema.storage

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.draftKey
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "message-store-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        addAccount(ACCOUNT)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun groupchatIngestMarksPeerAsRoom() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            IncomingMessage(
                accountId = ACCOUNT,
                localMessageId = "room-msg",
                peerJid = "room@conference.example.org",
                senderJid = "room@conference.example.org/alice",
                direction = MessageDirection.INBOUND,
                messageKind = MessageKind.GROUPCHAT,
                threadId = null,
                parentThreadId = null,
                body = "hello room",
                archiveOrdinal = null,
                aliases = emptyList(),
            ),
        )

        val peer = requireNotNull(database.messageDao().peer(ACCOUNT, "room@conference.example.org"))
        assertTrue(peer.room)
    }

    @Test
    fun ownGroupchatEchoReconcilesAcrossAccountAndOccupantSenderForms() = runBlocking {
        val room = "room@conference.example.org"
        val store = MessageStore(database)
        store.compose(
            OutboundIntent(
                accountId = ACCOUNT,
                operationId = "room-operation",
                localMessageId = "local-room-message",
                originId = "room-origin",
                peerJid = room,
                senderJid = SELF,
                messageKind = MessageKind.GROUPCHAT,
                threadId = null,
                parentThreadId = null,
                body = "hello room",
                replyToId = "room-target",
                replyToJid = "$room/Alice",
                replyFallbackBody = "original room message",
            ),
        )

        store.ingest(
            IncomingMessage(
                accountId = ACCOUNT,
                localMessageId = "echo-room-message",
                peerJid = room,
                senderJid = "$room/ChosenNick",
                direction = MessageDirection.OUTBOUND,
                messageKind = MessageKind.GROUPCHAT,
                threadId = null,
                parentThreadId = null,
                body = "hello room",
                archiveOrdinal = null,
                replyToId = "room-target",
                replyToJid = "$room/Alice",
                replyFallbackBody = "original room message",
                aliases = listOf(
                    TrustedIdentityAlias(
                        IdentityAliasKind.ORIGIN_ID,
                        MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                        "room-origin",
                    ),
                ),
            ),
        )

        assertEquals(listOf("local-room-message"), store.messages(ACCOUNT).map(MessageEntity::localMessageId))
        assertEquals(OutboxStatus.CONFIRMED, store.outbox(ACCOUNT, "room-operation")?.status)
    }

    @Test
    fun freshSchemaHasAccountQualifiedTablesAndConstraints() = runBlocking {
        val sqlite = database.openHelper.writableDatabase
        val tables = buildSet {
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue(
            tables.containsAll(
                setOf(
                    "peers",
                    "message_threads",
                    "messages",
                    "trusted_identity_aliases",
                    "identity_conflicts",
                    "message_outbox",
                    "archive_cursors",
                    "message_drafts",
                    "account_message_sequences",
                    "chat_navigation",
                ),
            ),
        )
        sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        assertFailure<SQLiteConstraintException> {
            sqlite.execSQL("INSERT INTO peers(accountId, jid) VALUES('missing', 'peer@example.org')")
        }

        addAccount(OTHER_ACCOUNT)
        val store = MessageStore(database)
        store.ingest(incoming(accountId = ACCOUNT, localId = "a"))
        store.ingest(incoming(accountId = OTHER_ACCOUNT, localId = "b"))
        assertEquals(1L, store.messages(ACCOUNT).single().localSequence)
        assertEquals(1L, store.messages(OTHER_ACCOUNT).single().localSequence)
        assertFailure<SQLiteConstraintException> {
            sqlite.execSQL(
                """
                INSERT INTO messages(
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, threadId, parentThreadId, body, localSequence, archiveOrdinal
                ) VALUES('$ACCOUNT', 'duplicate-sequence', '$PEER', '$PEER',
                    'INBOUND', 'CHAT', NULL, NULL, 'body', 1, NULL)
                """.trimIndent(),
            )
        }
        Unit
    }

    @Test
    fun versionOneDatabaseMigratesWithoutLosingAccount() = runBlocking {
        database.close()
        context.deleteDatabase(databaseName)
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE accounts (
                    id TEXT NOT NULL PRIMARY KEY,
                    bareJid TEXT NOT NULL,
                    authenticationId TEXT NOT NULL,
                    authorizationId TEXT,
                    serviceDomain TEXT NOT NULL,
                    networkHost TEXT,
                    networkPort INTEGER
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                CREATE TABLE active_account (
                    singletonId INTEGER NOT NULL PRIMARY KEY,
                    accountId TEXT NOT NULL,
                    FOREIGN KEY(accountId) REFERENCES accounts(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                "CREATE UNIQUE INDEX index_active_account_accountId ON active_account(accountId)",
            )
            legacy.execSQL(
                """
                INSERT INTO accounts(
                    id, bareJid, authenticationId, authorizationId,
                    serviceDomain, networkHost, networkPort
                ) VALUES('$ACCOUNT', 'account@example.org', 'account', NULL, 'example.org', NULL, NULL)
                """.trimIndent(),
            )
            legacy.version = 1
        }

        database = NemaDatabase.create(context, databaseName)
        assertEquals(ACCOUNT, database.accountDao().account(ACCOUNT)?.id)
        assertTrue(MessageStore(database).messages(ACCOUNT).isEmpty())
    }

    @Test
    fun composeRollsBackAtEveryWriteBoundary() = runBlocking {
        for (boundary in listOf(
            MessageWriteBoundary.AFTER_MESSAGE,
            MessageWriteBoundary.AFTER_ALIAS,
            MessageWriteBoundary.AFTER_OUTBOX,
        )) {
            val faulting = MessageStore.observingWrites(
                database = database,
                observer = { if (it == boundary) error("fault at $boundary") },
            )
            val suffix = boundary.name
            assertSuspendFailure<IllegalStateException> { faulting.compose(outbound(suffix)) }
            val store = MessageStore(database)
            assertTrue(store.messages(ACCOUNT).isEmpty())
            assertTrue(store.aliases(ACCOUNT).isEmpty())
            assertTrue(store.outboxes(ACCOUNT).isEmpty())
        }
    }

    @Test
    fun directDraftAndOutboxCommitAtomicallyAcrossReopen() = runBlocking {
        database.messageDao().saveDirectDraft(ACCOUNT, PEER, "persisted stale body")
        val faulting = MessageStore.observingWrites(
            database = database,
            observer = {
                if (it == MessageWriteBoundary.AFTER_OUTBOX) error("post-compose fault")
            },
        )

        assertSuspendFailure<IllegalStateException> {
            faulting.composeDirectDraft(
                accountId = ACCOUNT,
                operationId = "operation-draft",
                localMessageId = "local-draft",
                originId = "origin-draft",
                peerJid = PEER,
                senderJid = SELF,
                body = "captured body",
            )
        }

        var store = reopenStore()
        assertEquals("persisted stale body", database.messageDao().directDraft(ACCOUNT, PEER)?.body)
        assertTrue(store.messages(ACCOUNT).isEmpty())
        assertTrue(store.outboxes(ACCOUNT).isEmpty())

        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "operation-draft",
            localMessageId = "local-draft",
            originId = "origin-draft",
            peerJid = PEER,
            senderJid = SELF,
            body = "captured body",
        )
        store = reopenStore()

        assertNull(database.messageDao().directDraft(ACCOUNT, PEER))
        assertEquals(listOf("captured body"), store.messages(ACCOUNT).map(MessageEntity::body))
        assertEquals(listOf("operation-draft"), store.outboxes(ACCOUNT).map(OutboxEntity::operationId))
        assertNull(
            store.composeDirectDraft(
                accountId = ACCOUNT,
                operationId = "operation-duplicate",
                localMessageId = "local-duplicate",
                originId = "origin-duplicate",
                peerJid = PEER,
                senderJid = SELF,
                body = "",
            ),
        )
        assertEquals(1, store.outboxes(ACCOUNT).size)
    }

    @Test
    fun semanticReplyMetadataSurvivesTheDurableOutboxBoundary() = runBlocking {
        val store = MessageStore(database)
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "reply-operation",
            localMessageId = "reply-message",
            originId = "reply-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "answer",
            replyToId = "target-wire-id",
            replyToJid = PEER,
            replyFallbackBody = "original body",
        )

        val pending = store.pendingOutbound(ACCOUNT).single()
        assertEquals("target-wire-id", pending.replyToId)
        assertEquals(PEER, pending.replyToJid)
        assertEquals("original body", pending.replyFallbackBody)
    }

    @Test
    fun threadDraftSendPersistsExactLineageWithoutConsumingConversationDraft() = runBlocking {
        val thread = ThreadRef(ThreadId.require("child-thread"), ThreadId.require("parent-thread"))
        database.messageDao().saveDraft(ACCOUNT, PEER, "", "conversation draft")
        database.messageDao().saveDraft(ACCOUNT, PEER, thread.draftKey(), "thread draft")

        var store = MessageStore(database)
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "operation-thread",
            localMessageId = "message-identity",
            originId = "origin-thread",
            peerJid = PEER,
            senderJid = SELF,
            body = "thread draft",
            thread = thread,
        )
        store = reopenStore()

        val message = store.messages(ACCOUNT).single()
        val pending = store.pendingOutbound(ACCOUNT).single()
        assertEquals("child-thread", message.threadId)
        assertEquals("parent-thread", message.parentThreadId)
        assertEquals("child-thread", pending.threadId)
        assertEquals("parent-thread", pending.parentThreadId)
        assertEquals("conversation draft", database.messageDao().draft(ACCOUNT, PEER, "")?.body)
        assertNull(database.messageDao().draft(ACCOUNT, PEER, thread.draftKey()))
    }

    @Test
    fun conversationDraftCanSendThreadRootAndConsumeOnlyConversationDraft() = runBlocking {
        val thread = ThreadRef(ThreadId.require("new-thread"))
        database.messageDao().saveDraft(ACCOUNT, PEER, "", "thread root")

        MessageStore(database).composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "operation-new-thread",
            localMessageId = "message-new-thread",
            originId = "origin-new-thread",
            peerJid = PEER,
            senderJid = SELF,
            body = "thread root",
            thread = thread,
            draftThread = null,
        )

        assertEquals("new-thread", MessageStore(database).messages(ACCOUNT).single().threadId)
        assertNull(database.messageDao().draft(ACCOUNT, PEER, ""))
        assertNull(database.messageDao().draft(ACCOUNT, PEER, thread.draftKey()))
    }

    @Test
    fun compatibleAliasBridgeMergesOldestWithoutDuplicateRow() = runBlocking {
        val firstAlias = alias("first")
        val secondAlias = alias("second")
        val store = MessageStore(database)
        store.ingest(incoming(localId = "first", aliases = listOf(firstAlias)))
        store.ingest(
            incoming(localId = "second", archiveOrdinal = 7, aliases = listOf(secondAlias)),
        )

        val bridge = store.ingest(
            incoming(
                localId = "bridge",
                aliases = listOf(firstAlias, secondAlias),
            ),
        )
        assertEquals("first", bridge.messageId)
        assertEquals(1, bridge.mergedRows)
        assertFalse(bridge.identityConflict)
        assertEquals(listOf("first"), store.messages(ACCOUNT).map(MessageEntity::localMessageId))
        assertEquals(7L, store.messages(ACCOUNT).single().archiveOrdinal)
        assertEquals(setOf("first"), store.aliases(ACCOUNT).mapNotNull { it.messageId }.toSet())
        assertEquals(
            listOf(7L),
            store.archivePositions(ACCOUNT, "first").map(ArchiveMessagePositionEntity::archiveOrdinal),
        )
        assertTrue(store.archivePositions(ACCOUNT, "second").isEmpty())

        store.ingest(
            incoming(localId = "repeat", archiveOrdinal = 7, aliases = listOf(firstAlias)),
        )
        assertEquals(1, store.messages(ACCOUNT).size)
    }

    @Test
    fun dependentReparentFaultRollsBackWholeMerge() = runBlocking {
        val firstAlias = alias("first")
        val secondAlias = alias("second")
        val store = MessageStore(database)
        store.ingest(
            incoming(
                localId = "first",
                archiveOrdinal = 0,
                aliases = listOf(firstAlias),
            ),
        )
        store.ingest(
            incoming(
                localId = "second",
                archiveOrdinal = 5,
                archiveAuthority = "room@conference.example.org",
                archiveScope = "room@conference.example.org",
                aliases = listOf(secondAlias),
            ),
        )
        val aliasesBefore = store.aliases(ACCOUNT)
        val firstPositions = store.archivePositions(ACCOUNT, "first")
        val secondPositions = store.archivePositions(ACCOUNT, "second")

        val faulting = MessageStore.observingWrites(
            database = database,
            observer = {
                if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) error("merge fault")
            },
        )
        assertSuspendFailure<IllegalStateException> {
            faulting.ingest(
                incoming(localId = "bridge", aliases = listOf(firstAlias, secondAlias)),
            )
        }
        assertEquals(listOf("first", "second"), store.messages(ACCOUNT).map { it.localMessageId })
        assertEquals(aliasesBefore, store.aliases(ACCOUNT))
        assertEquals(firstPositions, store.archivePositions(ACCOUNT, "first"))
        assertEquals(secondPositions, store.archivePositions(ACCOUNT, "second"))
    }

    @Test
    fun incompatibleAliasReuseRetainsRowsAndQuarantinesIdentity() = runBlocking {
        val reused = alias("reused")
        val store = MessageStore(database)
        store.ingest(incoming(localId = "first", body = "first body", aliases = listOf(reused)))
        val result = store.ingest(
            incoming(localId = "second", body = "second body", aliases = listOf(reused)),
        )

        assertTrue(result.identityConflict)
        assertEquals(setOf("first", "second"), store.messages(ACCOUNT).map { it.localMessageId }.toSet())
        val quarantined = store.aliases(ACCOUNT).single()
        assertEquals(IdentityAliasStatus.QUARANTINED, quarantined.status)
        assertNull(quarantined.messageId)
        assertEquals(1, store.conflicts(ACCOUNT).size)
    }

    @Test
    fun compatibleMergeReparentsExistingConflictEvidence() = runBlocking {
        val winnerAlias = alias("winner")
        val reusedAlias = alias("reused")
        val loserAlias = alias("loser")
        val store = MessageStore(database)
        store.ingest(incoming(localId = "winner", body = "same", aliases = listOf(winnerAlias)))
        store.ingest(
            incoming(
                localId = "loser",
                body = "same",
                aliases = listOf(reusedAlias, loserAlias),
            ),
        )
        store.ingest(incoming(localId = "other", body = "different", aliases = listOf(reusedAlias)))

        store.ingest(
            incoming(
                localId = "bridge",
                body = "same",
                aliases = listOf(winnerAlias, loserAlias),
            ),
        )
        val reopened = reopenStore()
        assertEquals(setOf("winner", "other"), reopened.messages(ACCOUNT).map { it.localMessageId }.toSet())
        val conflict = reopened.conflicts(ACCOUNT).single()
        assertEquals(setOf("winner", "other"), setOf(conflict.firstMessageId, conflict.secondMessageId))
        val quarantined = reopened.aliases(ACCOUNT).single { it.value == reusedAlias.value }
        assertEquals(IdentityAliasStatus.QUARANTINED, quarantined.status)
        assertNull(quarantined.messageId)
    }

    @Test
    fun archivePagesMergeLiveAndOutboxCopiesIntoContiguousOrder() = runBlocking {
        val store = MessageStore(database)
        val intent = outbound("archive")
        store.compose(intent)
        store.ingest(
            incoming(
                localId = "live-middle",
                body = "middle",
                aliases = listOf(stanzaAlias("middle")),
            ),
        )
        val key = archiveKey(ACCOUNT)

        store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    archived("r1", "archive-first", "first", stanzaAlias("first")),
                    archived("r2", "archive-middle", "middle", stanzaAlias("middle")),
                    archived(
                        resultId = "r3",
                        localId = "archive-own",
                        body = intent.body,
                        alias = TrustedIdentityAlias(
                            IdentityAliasKind.ORIGIN_ID,
                            MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                            intent.originId,
                        ),
                        direction = MessageDirection.OUTBOUND,
                        sender = SELF,
                    ),
                ),
            ),
        )
        store.ingest(
            incoming(
                localId = "live-fourth",
                body = "fourth",
                aliases = listOf(stanzaAlias("fourth")),
            ),
        )
        store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.AFTER,
                boundaryId = "r3",
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    archived(
                        resultId = "r3",
                        localId = "repeat-own",
                        body = intent.body,
                        alias = TrustedIdentityAlias(
                            IdentityAliasKind.ORIGIN_ID,
                            MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                            intent.originId,
                        ),
                        direction = MessageDirection.OUTBOUND,
                        sender = SELF,
                    ),
                    archived("r4", "archive-fourth", "fourth", stanzaAlias("fourth")),
                ),
            ),
        )
        store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "r1",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("r0", "archive-zero", "zero", stanzaAlias("zero")),
                    archived("r1", "repeat-first", "first", stanzaAlias("first")),
                ),
            ),
        )

        assertEquals(
            listOf("archive-zero", "archive-first", "live-middle", intent.localMessageId, "live-fourth"),
            store.messages(ACCOUNT).map(MessageEntity::localMessageId),
        )
        assertEquals(listOf(-1L, 0L, 1L, 2L, 3L), store.messages(ACCOUNT).map(MessageEntity::archiveOrdinal))
        assertEquals(OutboxStatus.CONFIRMED, store.outbox(ACCOUNT, intent.operationId)?.status)
        assertEquals(
            ArchiveCursorEntity(
                ACCOUNT,
                SELF,
                "ACCOUNT",
                "r0",
                "r4",
                false,
                null,
                oldestOrdinal = -1,
                newestOrdinal = 3,
            ),
            store.archiveCursor(key),
        )
    }

    @Test
    fun archiveBoundaryOrdinalsPreserveUnsupportedResultGaps() = runBlocking {
        var store = MessageStore(database)
        val afterKey = archiveKey(ACCOUNT)
        store.applyArchivePage(
            archivePage(
                key = afterKey,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("after-r1", "after-visible", "visible"),
                    ArchivedIncomingMessage("after-r2", null),
                    ArchivedIncomingMessage("after-r3", null),
                ),
            ),
        )
        store = reopenStore()
        store.applyArchivePage(
            archivePage(
                key = afterKey,
                direction = ArchiveDirection.AFTER,
                boundaryId = "after-r3",
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("after-r4", "after-next", "next")),
            ),
        )
        assertEquals(
            listOf(0L, 3L),
            listOf("after-visible", "after-next").map { messageId ->
                store.archivePositions(ACCOUNT, messageId).single().archiveOrdinal
            },
        )
        assertEquals(3L, store.archiveCursor(afterKey)?.newestOrdinal)

        val beforeAccount = "gap-before"
        addAccount(beforeAccount)
        val beforeKey = archiveKey(beforeAccount)
        store.applyArchivePage(
            archivePage(
                key = beforeKey,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    ArchivedIncomingMessage("before-r1", null),
                    ArchivedIncomingMessage("before-r2", null),
                    archived(
                        "before-r3",
                        "before-visible",
                        "visible",
                        accountId = beforeAccount,
                    ),
                ),
            ),
        )
        store = reopenStore()
        store.applyArchivePage(
            archivePage(
                key = beforeKey,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "before-r1",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("before-r0", "before-previous", "previous", accountId = beforeAccount),
                ),
            ),
        )
        assertEquals(
            listOf(-1L, 2L),
            listOf("before-previous", "before-visible").map { messageId ->
                store.archivePositions(beforeAccount, messageId).single().archiveOrdinal
            },
        )
        assertEquals(-1L, store.archiveCursor(beforeKey)?.oldestOrdinal)
    }

    @Test
    fun resetCursorBootstrapAcceptsFullyMappedArchivePage() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val page = archivePage(
            key = key,
            direction = ArchiveDirection.BOOTSTRAP,
            complete = true,
            hasEarlier = false,
            messages = listOf(archived("migrated-r1", "migrated-message", "history")),
        )
        assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page).status)
        database.messageDao().upsertArchiveCursor(
            key.emptyCursor().copy(
                hasEarlier = true,
                retryableError = "Archive cursor reset during migration",
            ),
        )

        store = reopenStore()
        val result = store.applyArchivePage(page)

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals("migrated-r1", result.cursor.oldestId)
        assertEquals("migrated-r1", result.cursor.newestId)
        assertEquals(0L, result.cursor.oldestOrdinal)
        assertEquals(0L, result.cursor.newestOrdinal)
        assertNull(result.cursor.retryableError)
        store = reopenStore()
        assertEquals(result.cursor, store.archiveCursor(key))
    }

    @Test
    fun resetCursorBootstrapRebasesCollapsedLegacyGap() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val older = archived("migrated-r0", "migrated-message-0", "older")
        val first = archived("migrated-r1", "migrated-message-1", "first")
        val second = archived("migrated-r2", "migrated-message-2", "second")
        val third = archived("migrated-r3", "migrated-message-3", "third")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(older, first, second, third),
                ),
            ).status,
        )
        database.messageDao().upsertArchiveCursor(
            key.emptyCursor().copy(
                hasEarlier = true,
                retryableError = "Archive cursor reset during migration",
            ),
        )

        store = reopenStore()
        val result = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    first,
                    ArchivedIncomingMessage("unsupported-result", null),
                    second,
                    third,
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(0L, store.archivePositions(ACCOUNT, "migrated-message-0").single().archiveOrdinal)
        assertEquals(1L, store.archivePositions(ACCOUNT, "migrated-message-1").single().archiveOrdinal)
        assertEquals(3L, store.archivePositions(ACCOUNT, "migrated-message-2").single().archiveOrdinal)
        assertEquals(4L, store.archivePositions(ACCOUNT, "migrated-message-3").single().archiveOrdinal)
        assertEquals(1L, result.cursor.oldestOrdinal)
        assertEquals(4L, result.cursor.newestOrdinal)
        assertNull(result.cursor.retryableError)
        store = reopenStore()
        assertEquals(result.cursor, store.archiveCursor(key))
    }

    @Test
    fun migratedResetBeforeRebasesOlderCollapsedGap() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val earliest = archived("migrated-earliest", "migrated-message-earliest", "earliest")
        val olderFirst = archived("migrated-r0", "migrated-message-0", "older first")
        val olderSecond = archived("migrated-r1", "migrated-message-1", "older second")
        val tailFirst = archived("migrated-r2", "migrated-message-2", "tail first")
        val tailSecond = archived("migrated-r3", "migrated-message-3", "tail second")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(earliest, olderFirst, olderSecond, tailFirst, tailSecond),
                ),
            ).status,
        )
        database.messageDao().upsertArchiveCursor(
            key.emptyCursor().copy(
                hasEarlier = true,
                retryableError = "Archive cursor reset during migration",
            ),
        )

        store = reopenStore()
        val bootstrap = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    tailFirst,
                    ArchivedIncomingMessage("unsupported-tail", null),
                    tailSecond,
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, bootstrap.status)
        assertEquals(3L, bootstrap.cursor.oldestOrdinal)
        assertEquals(5L, bootstrap.cursor.newestOrdinal)

        store = reopenStore()
        val before = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "migrated-r2",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    olderFirst,
                    ArchivedIncomingMessage("unsupported-older", null),
                    olderSecond,
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, before.status)
        assertEquals(-1L, store.archivePositions(ACCOUNT, "migrated-message-earliest").single().archiveOrdinal)
        assertEquals(0L, store.archivePositions(ACCOUNT, "migrated-message-0").single().archiveOrdinal)
        assertEquals(2L, store.archivePositions(ACCOUNT, "migrated-message-1").single().archiveOrdinal)
        assertEquals(3L, store.archivePositions(ACCOUNT, "migrated-message-2").single().archiveOrdinal)
        assertEquals(5L, store.archivePositions(ACCOUNT, "migrated-message-3").single().archiveOrdinal)
        assertEquals(0L, before.cursor.oldestOrdinal)
        assertEquals(5L, before.cursor.newestOrdinal)
        assertNull(before.cursor.retryableError)
        store = reopenStore()
        assertEquals(before.cursor, store.archiveCursor(key))
    }

    @Test
    fun migratedBeforeUsesTrustedIdentityBeforePrefixRebase() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val stableAlias = stanzaAlias("stable-older-second")
        val earliest = archived("stable-earliest", "stable-message-earliest", "earliest")
        val olderFirst = archived("stable-r0", "stable-message-0", "older first")
        val olderSecond = archived(
            "stable-r1",
            "stable-message-1",
            "older second",
            stableAlias,
        )
        val tailFirst = archived("stable-r2", "stable-message-2", "tail first")
        val tailSecond = archived("stable-r3", "stable-message-3", "tail second")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(earliest, olderFirst, olderSecond, tailFirst, tailSecond),
                ),
            ).status,
        )
        database.messageDao().upsertArchiveCursor(
            key.emptyCursor().copy(
                hasEarlier = true,
                retryableError = "Archive cursor reset during migration",
            ),
        )
        store = reopenStore()
        val bootstrap = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    tailFirst,
                    ArchivedIncomingMessage("stable-unsupported-tail", null),
                    tailSecond,
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, bootstrap.status)

        store = reopenStore()
        val before = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "stable-r2",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    olderFirst,
                    ArchivedIncomingMessage("stable-unsupported-older", null),
                    archived(
                        "stable-new-result",
                        "stable-replayed-message-1",
                        "older second",
                        stableAlias,
                    ),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, before.status)
        assertEquals(-1L, store.archivePositions(ACCOUNT, "stable-message-earliest").single().archiveOrdinal)
        assertEquals(0L, store.archivePositions(ACCOUNT, "stable-message-0").single().archiveOrdinal)
        assertEquals(2L, store.archivePositions(ACCOUNT, "stable-message-1").single().archiveOrdinal)
        assertEquals(5L, store.archivePositions(ACCOUNT, "stable-message-3").single().archiveOrdinal)
        assertEquals(5, store.messages(ACCOUNT).size)
        store = reopenStore()
        assertEquals(before.cursor, store.archiveCursor(key))
    }

    @Test
    fun migratedResetFullyMappedBeforePagesAdvanceAcrossBoundaryForms() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val earliest = archived("fully-earliest", "fully-message-earliest", "earliest")
        val olderFirst = archived("fully-r0", "fully-message-0", "older first")
        val olderSecond = archived("fully-r1", "fully-message-1", "older second")
        val tailFirst = archived("fully-r2", "fully-message-2", "tail first")
        val tailSecond = archived("fully-r3", "fully-message-3", "tail second")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(earliest, olderFirst, olderSecond, tailFirst, tailSecond),
                ),
            ).status,
        )
        database.messageDao().upsertArchiveCursor(
            key.emptyCursor().copy(
                hasEarlier = true,
                retryableError = "Archive cursor reset during migration",
            ),
        )
        store = reopenStore()
        val bootstrap = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    tailFirst,
                    ArchivedIncomingMessage("fully-unsupported-tail", null),
                    tailSecond,
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, bootstrap.status)
        assertEquals(3L, bootstrap.cursor.oldestOrdinal)

        store = reopenStore()
        val boundaryIncluded = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "fully-r2",
                complete = true,
                hasEarlier = true,
                messages = listOf(olderFirst, olderSecond, tailFirst),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, boundaryIncluded.status)
        assertEquals(1L, boundaryIncluded.cursor.oldestOrdinal)
        assertEquals(1L, store.archivePositions(ACCOUNT, "fully-message-0").single().archiveOrdinal)
        assertEquals(2L, store.archivePositions(ACCOUNT, "fully-message-1").single().archiveOrdinal)
        assertEquals(3L, store.archivePositions(ACCOUNT, "fully-message-2").single().archiveOrdinal)

        store = reopenStore()
        val boundaryExcluded = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "fully-r0",
                complete = true,
                hasEarlier = false,
                messages = listOf(earliest),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, boundaryExcluded.status)
        assertEquals(0L, boundaryExcluded.cursor.oldestOrdinal)
        assertEquals(0L, store.archivePositions(ACCOUNT, "fully-message-earliest").single().archiveOrdinal)
        store = reopenStore()
        assertEquals(boundaryExcluded.cursor, store.archiveCursor(key))
    }

    @Test
    fun migratedResetBeforeRebaseRollsBackPrefixAndCursor() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val earliest = archived("rollback-earliest", "rollback-message-earliest", "earliest")
        val olderFirst = archived("rollback-r0", "rollback-message-0", "older first")
        val olderSecond = archived("rollback-r1", "rollback-message-1", "older second")
        val tailFirst = archived("rollback-r2", "rollback-message-2", "tail first")
        val tailSecond = archived("rollback-r3", "rollback-message-3", "tail second")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(earliest, olderFirst, olderSecond, tailFirst, tailSecond),
                ),
            ).status,
        )
        database.messageDao().upsertArchiveCursor(
            key.emptyCursor().copy(
                hasEarlier = true,
                retryableError = "Archive cursor reset during migration",
            ),
        )
        store = reopenStore()
        val bootstrap = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    tailFirst,
                    ArchivedIncomingMessage("rollback-unsupported-tail", null),
                    tailSecond,
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, bootstrap.status)
        val positionsBefore = listOf(
            "rollback-message-earliest",
            "rollback-message-0",
            "rollback-message-1",
            "rollback-message-2",
            "rollback-message-3",
        ).associateWith { id -> store.archivePositions(ACCOUNT, id).single().archiveOrdinal }
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.BEFORE_ARCHIVE_CURSOR) error("before rebase fault")
        }

        assertSuspendFailure<IllegalStateException> {
            faulting.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BEFORE,
                    boundaryId = "rollback-r2",
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(
                        olderFirst,
                        ArchivedIncomingMessage("rollback-unsupported-older", null),
                        olderSecond,
                    ),
                ),
            )
        }

        store = reopenStore()
        assertEquals(bootstrap.cursor, store.archiveCursor(key))
        positionsBefore.forEach { (id, ordinal) ->
            assertEquals(ordinal, store.archivePositions(ACCOUNT, id).single().archiveOrdinal)
        }
    }

    @Test
    fun resetCursorBootstrapAppendsUnmappedTailAfterPreservedHistory() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val older = archived("migrated-r0", "migrated-message-0", "older")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(older),
                ),
            ).status,
        )
        database.messageDao().upsertArchiveCursor(
            key.emptyCursor().copy(
                hasEarlier = true,
                retryableError = "Archive cursor reset during migration",
            ),
        )

        store = reopenStore()
        val result = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("new-r1", "new-message-1", "new first"),
                    ArchivedIncomingMessage("unsupported-result", null),
                    archived("new-r2", "new-message-2", "new last"),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(0L, store.archivePositions(ACCOUNT, "migrated-message-0").single().archiveOrdinal)
        assertEquals(1L, store.archivePositions(ACCOUNT, "new-message-1").single().archiveOrdinal)
        assertEquals(3L, store.archivePositions(ACCOUNT, "new-message-2").single().archiveOrdinal)
        assertEquals(1L, result.cursor.oldestOrdinal)
        assertEquals(3L, result.cursor.newestOrdinal)
    }

    @Test
    fun resetCursorBootstrapRemainsRebasableAfterRetryablePage() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val existing = archived("migrated-r1", "migrated-message-1", "existing")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(existing),
                ),
            ).status,
        )
        database.messageDao().upsertArchiveCursor(
            key.emptyCursor().copy(
                hasEarlier = true,
                retryableError = "Archive cursor reset during migration",
            ),
        )

        store = reopenStore()
        val malformed = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(existing),
            ).copy(stable = false),
        )
        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, malformed.status)
        assertEquals(0L, store.archivePositions(ACCOUNT, "migrated-message-1").single().archiveOrdinal)

        store = reopenStore()
        val applied = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    ArchivedIncomingMessage("unsupported-result", null),
                    existing,
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, applied.status)
        assertEquals(1L, store.archivePositions(ACCOUNT, "migrated-message-1").single().archiveOrdinal)
        assertEquals(0L, applied.cursor.oldestOrdinal)
        assertEquals(1L, applied.cursor.newestOrdinal)
        assertNull(applied.cursor.retryableError)
    }

    @Test
    fun resetCursorBootstrapRebaseRollsBackWithCursor() = runBlocking {
        val store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val older = archived("migrated-r0", "migrated-message-0", "older")
        val first = archived("migrated-r1", "migrated-message-1", "first")
        val second = archived("migrated-r2", "migrated-message-2", "second")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(older, first, second),
                ),
            ).status,
        )
        val reset = key.emptyCursor().copy(
            hasEarlier = true,
            retryableError = "Archive cursor reset during migration",
        )
        database.messageDao().upsertArchiveCursor(reset)
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.BEFORE_ARCHIVE_CURSOR) error("rebase fault")
        }

        assertSuspendFailure<IllegalStateException> {
            faulting.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(
                        first,
                        ArchivedIncomingMessage("unsupported-result", null),
                        second,
                    ),
                ),
            )
        }

        val reopened = reopenStore()
        assertEquals(reset, reopened.archiveCursor(key))
        assertEquals(0L, reopened.archivePositions(ACCOUNT, "migrated-message-0").single().archiveOrdinal)
        assertEquals(1L, reopened.archivePositions(ACCOUNT, "migrated-message-1").single().archiveOrdinal)
        assertEquals(2L, reopened.archivePositions(ACCOUNT, "migrated-message-2").single().archiveOrdinal)
    }

    @Test
    fun accountAndRoomBootstrapsHaveIndependentOrdinalNamespaces() = runBlocking {
        val store = MessageStore(database)
        val accountKey = archiveKey(ACCOUNT)
        val roomJid = "room@conference.example.org"
        val roomKey = ArchiveCursorKey(ACCOUNT, roomJid, roomJid)

        val accountResult = store.applyArchivePage(
            archivePage(
                key = accountKey,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("account-r1", "account-message", "account history")),
            ),
        )
        val roomResult = store.applyArchivePage(
            archivePage(
                key = roomKey,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("room-r1", "room-message", "room history")),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, accountResult.status)
        assertEquals(ArchivePageStatus.APPLIED, roomResult.status)
        assertEquals(
            setOf("account-message", "room-message"),
            store.messages(ACCOUNT).map(MessageEntity::localMessageId).toSet(),
        )
        assertEquals(0L, store.messages(ACCOUNT).single { it.localMessageId == "account-message" }.archiveOrdinal)
        assertEquals(0L, store.messages(ACCOUNT).single { it.localMessageId == "room-message" }.archiveOrdinal)
        assertEquals("account-r1", store.archiveCursor(accountKey)?.newestId)
        assertEquals("room-r1", store.archiveCursor(roomKey)?.newestId)

        store.applyArchivePage(
            archivePage(
                key = accountKey,
                direction = ArchiveDirection.AFTER,
                boundaryId = "account-r1",
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("account-r2", "account-message-2", "account later")),
            ),
        )
        store.applyArchivePage(
            archivePage(
                key = roomKey,
                direction = ArchiveDirection.AFTER,
                boundaryId = "room-r1",
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("room-r2", "room-message-2", "room later")),
            ),
        )
        val positions = store.messages(ACCOUNT).flatMap { message ->
            store.archivePositions(ACCOUNT, message.localMessageId)
        }
        assertEquals(
            listOf(0L, 1L),
            positions
                .filter { it.archiveScope == "ACCOUNT" }
                .map(ArchiveMessagePositionEntity::archiveOrdinal),
        )
        assertEquals(
            listOf(0L, 1L),
            positions
                .filter { it.archiveScope == roomJid }
                .map(ArchiveMessagePositionEntity::archiveOrdinal),
        )

        val accountB = "account-b"
        addAccount(accountB)
        val accountBKey = archiveKey(accountB)
        val roomBKey = ArchiveCursorKey(accountB, roomJid, roomJid)
        val roomFirst = store.applyArchivePage(
            archivePage(
                key = roomBKey,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("room-b-r1", "room-b-message", "room B", accountId = accountB),
                ),
            ),
        )
        val accountSecond = store.applyArchivePage(
            archivePage(
                key = accountBKey,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("account-b-r1", "account-b-message", "account B", accountId = accountB),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, roomFirst.status)
        assertEquals(ArchivePageStatus.APPLIED, accountSecond.status)
        assertEquals(
            setOf("room-b-message", "account-b-message"),
            store.messages(accountB).map(MessageEntity::localMessageId).toSet(),
        )
        assertTrue(store.messages(accountB).all { it.archiveOrdinal == 0L })
        assertEquals("room-b-r1", store.archiveCursor(roomBKey)?.newestId)
        assertEquals("account-b-r1", store.archiveCursor(accountBKey)?.newestId)
    }

    @Test
    fun concurrentAccountAndRoomBootstrapsCommitBothScopes() = runBlocking {
        val store = MessageStore(database)
        val roomJid = "room@conference.example.org"
        val pages = listOf(
            archivePage(
                key = archiveKey(ACCOUNT),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("account-concurrent", "account-concurrent", "account")),
            ),
            archivePage(
                key = ArchiveCursorKey(ACCOUNT, roomJid, roomJid),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("room-concurrent", "room-concurrent", "room")),
            ),
        )

        val results = coroutineScope {
            pages.map { page -> async { store.applyArchivePage(page) } }.awaitAll()
        }

        assertTrue(results.all { it.status == ArchivePageStatus.APPLIED })
        assertEquals(
            setOf("account-concurrent", "room-concurrent"),
            store.messages(ACCOUNT).map(MessageEntity::localMessageId).toSet(),
        )
        assertTrue(store.messages(ACCOUNT).all { it.archiveOrdinal == 0L })
    }

    @Test
    fun concurrentSameScopeBootstrapsCommitOnlyOnePage() = runBlocking {
        val store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val pages = listOf(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("same-scope-a", "message-a", "A")),
            ),
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("same-scope-b", "message-b", "B")),
            ),
        )

        val results = coroutineScope {
            pages.map { page -> async { store.applyArchivePage(page) } }.awaitAll()
        }
        assertEquals(1, results.count { it.status == ArchivePageStatus.APPLIED })
        assertEquals(1, results.count { it.status == ArchivePageStatus.RETRYABLE_ERROR })
        val appliedIndex = results.indexOfFirst { it.status == ArchivePageStatus.APPLIED }
        val expectedResultId = pages[appliedIndex].messages.single().resultId
        val expectedMessageId = requireNotNull(pages[appliedIndex].messages.single().message).localMessageId
        assertEquals(listOf(expectedMessageId), store.messages(ACCOUNT).map(MessageEntity::localMessageId))
        assertEquals(expectedResultId, store.archiveCursor(key)?.oldestId)
        assertEquals(expectedResultId, store.archiveCursor(key)?.newestId)
        assertEquals(
            listOf(0L),
            store.archivePositions(ACCOUNT, expectedMessageId)
                .map(ArchiveMessagePositionEntity::archiveOrdinal),
        )
    }

    @Test
    fun oneLogicalMessageCanOccupyIndependentAccountAndRoomArchivePositions() = runBlocking {
        val store = MessageStore(database)
        val roomJid = "room@conference.example.org"
        val accountKey = archiveKey(ACCOUNT)
        val roomKey = ArchiveCursorKey(ACCOUNT, roomJid, roomJid)
        val sharedIdentity = stanzaAlias("shared-stanza")

        store.applyArchivePage(
            archivePage(
                key = accountKey,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        "account-shared",
                        "account-copy",
                        "shared",
                        sharedIdentity,
                        sender = "$roomJid/Alice",
                        peerJid = roomJid,
                        messageKind = MessageKind.GROUPCHAT,
                    ),
                ),
            ),
        )
        store.applyArchivePage(
            archivePage(
                key = roomKey,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        "room-first",
                        "room-first",
                        "first",
                        sender = "$roomJid/Bob",
                        peerJid = roomJid,
                        messageKind = MessageKind.GROUPCHAT,
                    ),
                    archived(
                        "room-shared",
                        "room-copy",
                        "shared",
                        sharedIdentity,
                        sender = "$roomJid/Alice",
                        peerJid = roomJid,
                        messageKind = MessageKind.GROUPCHAT,
                    ),
                ),
            ),
        )
        store.ingest(
            incoming(
                localId = "account-copy",
                sender = "$roomJid/Alice",
                peerJid = roomJid,
                messageKind = MessageKind.GROUPCHAT,
                body = "shared",
                archiveOrdinal = -1,
                archiveAuthority = "alternate.example.org",
                archiveScope = roomJid,
                aliases = listOf(sharedIdentity),
            ),
        )

        val shared = store.messages(ACCOUNT).single { it.body == "shared" }
        assertEquals(
            setOf(
                Triple(accountKey.archiveAuthority, accountKey.scope, 0L),
                Triple(roomKey.archiveAuthority, roomKey.scope, 1L),
                Triple("alternate.example.org", roomKey.scope, -1L),
            ),
            store.archivePositions(ACCOUNT, shared.localMessageId)
                .map { Triple(it.archiveAuthority, it.archiveScope, it.archiveOrdinal) }
                .toSet(),
        )
        assertEquals(
            listOf("room-first", shared.localMessageId),
            database.messageDao().observeDirectTimeline(ACCOUNT, roomJid).first()
                .map(TimelineRow::localMessageId),
        )
        assertEquals("account-shared", store.archiveCursor(accountKey)?.newestId)
        assertEquals("room-shared", store.archiveCursor(roomKey)?.newestId)
    }

    @Test
    fun roomTimelineIgnoresAccountAndWrongAuthorityArchivePositions() = runBlocking {
        val store = MessageStore(database)
        val roomJid = "room@conference.example.org"
        val sharedIdentity = stanzaAlias("account-only-room-message")
        store.applyArchivePage(
            archivePage(
                key = archiveKey(ACCOUNT),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "account-room-r1",
                        localId = "account-room-message",
                        body = "account archived",
                        alias = sharedIdentity,
                        sender = "$roomJid/Alice",
                        peerJid = roomJid,
                        messageKind = MessageKind.GROUPCHAT,
                    ),
                ),
            ),
        )
        store.ingest(
            incoming(
                localId = "wrong-authority-copy",
                sender = "$roomJid/Alice",
                peerJid = roomJid,
                messageKind = MessageKind.GROUPCHAT,
                body = "account archived",
                archiveOrdinal = -10,
                archiveAuthority = "wrong.example.org",
                archiveScope = roomJid,
                aliases = listOf(sharedIdentity),
            ),
        )
        store.ingest(
            incoming(
                localId = "live-room-message",
                sender = "$roomJid/Bob",
                peerJid = roomJid,
                messageKind = MessageKind.GROUPCHAT,
                body = "live later",
                aliases = listOf(stanzaAlias("live-room-message")),
            ),
        )

        assertEquals(
            listOf("account-room-message", "live-room-message"),
            database.messageDao().observeDirectTimeline(ACCOUNT, roomJid).first()
                .map(TimelineRow::localMessageId),
        )
    }

    @Test
    fun directTimelineUsesOnlyExactAccountArchiveAuthorityAndScope() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(
                localId = "direct-first",
                body = "first",
                archiveOrdinal = 10,
                archiveAuthority = "wrong.example.org",
                archiveScope = "ACCOUNT",
                aliases = listOf(stanzaAlias("direct-first")),
            ),
        )
        store.ingest(
            incoming(
                localId = "direct-second",
                body = "second",
                archiveOrdinal = -10,
                archiveAuthority = SELF,
                archiveScope = "wrong-scope",
                aliases = listOf(stanzaAlias("direct-second")),
            ),
        )
        store.ingest(
            incoming(
                localId = "direct-archived",
                body = "archived",
                archiveOrdinal = -20,
                aliases = listOf(stanzaAlias("direct-archived")),
            ),
        )

        assertEquals(
            listOf("direct-archived", "direct-first", "direct-second"),
            database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first()
                .map(TimelineRow::localMessageId),
        )
    }

    @Test
    fun distinctMessagesCannotShareAnExactArchiveOrdinal() = runBlocking {
        var store = MessageStore(database)
        val originalAlias = stanzaAlias("position-original")
        store.ingest(
            incoming(
                localId = "position-original",
                body = "original",
                archiveOrdinal = 0,
                aliases = listOf(originalAlias),
            ),
        )

        assertSuspendFailure<SQLiteConstraintException> {
            store.ingest(
                incoming(
                    localId = "position-collision",
                    body = "different",
                    archiveOrdinal = 0,
                    aliases = listOf(stanzaAlias("position-collision")),
                ),
            )
        }
        store = reopenStore()
        assertEquals(listOf("position-original"), store.messages(ACCOUNT).map(MessageEntity::localMessageId))
        assertEquals(listOf(originalAlias.value), store.aliases(ACCOUNT).map(TrustedIdentityAliasEntity::value))
        assertEquals(
            listOf(0L),
            store.archivePositions(ACCOUNT, "position-original")
                .map(ArchiveMessagePositionEntity::archiveOrdinal),
        )
        assertTrue(store.archivePositions(ACCOUNT, "position-collision").isEmpty())
    }

    @Test
    fun sameScopeArchiveRepositionIsRejectedAtomically() = runBlocking {
        val store = MessageStore(database)
        val identity = stanzaAlias("stable-message")
        store.ingest(
            incoming(
                localId = "stable",
                body = "stable",
                archiveOrdinal = 0,
                aliases = listOf(identity),
            ),
        )

        assertSuspendFailure<IllegalArgumentException> {
            store.ingest(
                incoming(
                    localId = "duplicate",
                    body = "stable",
                    archiveOrdinal = 1,
                    aliases = listOf(identity),
                ),
            )
        }

        assertEquals(listOf("stable"), store.messages(ACCOUNT).map(MessageEntity::localMessageId))
        assertEquals(
            listOf(0L),
            store.archivePositions(ACCOUNT, "stable")
                .map(ArchiveMessagePositionEntity::archiveOrdinal),
        )
    }

    @Test
    fun archiveBackfillResolvesParentPlaceholderWithoutAcceptingConflictingLineage() = runBlocking {
        val store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val bootstrap = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    archived(
                        resultId = "r3",
                        localId = "grandchild-message",
                        body = "grandchild",
                        threadId = "grandchild",
                        parentThreadId = "child",
                    ),
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, bootstrap.status)

        val backfill = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "r3",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("r1", "root-message", "root", threadId = "root"),
                    archived(
                        resultId = "r2",
                        localId = "child-message",
                        body = "child",
                        threadId = "child",
                        parentThreadId = "root",
                    ),
                    archived(
                        resultId = "r3",
                        localId = "grandchild-copy",
                        body = "grandchild",
                        threadId = "grandchild",
                        parentThreadId = "child",
                    ),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, backfill.status)
        assertEquals("r1", store.archiveCursor(key)?.oldestId)
        assertEquals(
            listOf("root" to null, "child" to "root", "grandchild" to "child"),
            store.messages(ACCOUNT).map { it.threadId to it.parentThreadId },
        )
        val omittedParent = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.AFTER,
                boundaryId = "r3",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "r4",
                        localId = "child-with-omitted-parent",
                        body = "later child",
                        threadId = "child",
                    ),
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, omittedParent.status)
        assertEquals("root", store.messages(ACCOUNT).last().parentThreadId)
        val conflictingArchiveParent = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.AFTER,
                boundaryId = "r4",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "r5",
                        localId = "child-with-conflicting-parent",
                        body = "conflicting archive child",
                        threadId = "child",
                        parentThreadId = "different-root",
                    ),
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, conflictingArchiveParent.status)
        assertEquals("root", store.messages(ACCOUNT).last().parentThreadId)
        assertNull(database.messageDao().thread(ACCOUNT, PEER, MessageKind.CHAT, "different-root"))
        val populatedRoot = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.AFTER,
                boundaryId = "r5",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "r6",
                        localId = "root-with-parent",
                        body = "archive root parent claim",
                        threadId = "root",
                        parentThreadId = "different-root",
                    ),
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, populatedRoot.status)
        assertEquals(null, store.messages(ACCOUNT).last().parentThreadId)
        assertNull(database.messageDao().thread(ACCOUNT, PEER, MessageKind.CHAT, "different-root"))
        assertSuspendFailure<IllegalArgumentException> {
            store.ingest(
                incoming(
                    localId = "missing-parent-live",
                    body = "live omission",
                    threadId = "child",
                ),
            )
        }
        assertEquals(6, store.messages(ACCOUNT).size)
        assertSuspendFailure<IllegalArgumentException> {
            store.compose(
                outbound("missing-parent").copy(
                    threadId = "child",
                    parentThreadId = null,
                ),
            )
        }
        assertNull(store.outbox(ACCOUNT, "operation-missing-parent"))
        assertEquals(6, store.messages(ACCOUNT).size)
        assertSuspendFailure<IllegalArgumentException> {
            store.ingest(
                incoming(
                    localId = "conflicting-child",
                    body = "conflict",
                    threadId = "child",
                    parentThreadId = "different-root",
                ),
            )
        }
        Unit
    }

    @Test
    fun archiveUidBridgesLiveCopyAndUnsupportedResultsStillAdvanceExactOrder() = runBlocking {
        val store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        store.ingest(
            incoming(
                localId = "live",
                body = "live",
                aliases = listOf(stanzaAlias("r1")),
            ),
        )

        store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = false,
                hasEarlier = false,
                messages = listOf(archived("r1", "archive-copy", "live", alias = stanzaAlias("r1"))),
            ),
        )
        val result = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.AFTER,
                boundaryId = "r1",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("r2", "normal", "normal"),
                    ArchivedIncomingMessage("r3", null),
                    archived("r4", "chat", "chat"),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(2, result.ingested)
        assertEquals(listOf("live", "normal", "chat"), store.messages(ACCOUNT).map { it.body })
        assertEquals(listOf(0L, 1L, 3L), store.messages(ACCOUNT).map { it.archiveOrdinal })
        assertEquals("r4", store.archiveCursor(key)?.newestId)
        assertEquals(
            setOf(IdentityAliasKind.MAM_RESULT, IdentityAliasKind.STANZA_ID),
            store.aliases(ACCOUNT).filter { it.value == "r1" }.map { it.kind }.toSet(),
        )
        assertEquals(1, store.messages(ACCOUNT).count { it.body == "live" })
    }

    @Test
    fun roomMamResultIdsNeverBecomeReplyTargetsWithoutRoomStanzaId() = runBlocking {
        val room = "room@conference.example.org"
        val key = ArchiveCursorKey(ACCOUNT, room, room)
        fun roomMessage(localId: String, body: String, aliases: List<TrustedIdentityAlias>) =
            IncomingMessage(
                accountId = ACCOUNT,
                localMessageId = localId,
                peerJid = room,
                senderJid = "$room/alice",
                direction = MessageDirection.INBOUND,
                messageKind = MessageKind.GROUPCHAT,
                threadId = null,
                parentThreadId = null,
                body = body,
                archiveOrdinal = null,
                aliases = aliases,
            )
        MessageStore(database).applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    ArchivedIncomingMessage("mam-only", roomMessage("without", "without", emptyList())),
                    ArchivedIncomingMessage(
                        "different-mam-id",
                        roomMessage(
                            "with",
                            "with",
                            listOf(TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, room, "room-stanza-id")),
                        ),
                    ),
                ),
            ),
        )

        val timeline = database.messageDao().observeDirectTimeline(ACCOUNT, room).first()
        assertEquals(listOf(null, "room-stanza-id"), timeline.map { it.replyReferenceId })
        assertEquals(
            setOf(IdentityAliasKind.MAM_RESULT),
            database.messageDao().trustedAliases(ACCOUNT)
                .filter { it.value in setOf("mam-only", "different-mam-id") }
                .map { it.kind }
                .toSet(),
        )
    }

    @Test
    fun redactedFirstAndLastResultsOwnCursorBoundariesWithoutContentOrAliases() = runBlocking {
        val store = MessageStore(database)
        val key = archiveKey(ACCOUNT)

        val result = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    ArchivedIncomingMessage("redacted-first", null),
                    archived("visible", "visible-message", "body"),
                    ArchivedIncomingMessage("redacted-last", null),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals("redacted-first", result.cursor.oldestId)
        assertEquals("redacted-last", result.cursor.newestId)
        assertEquals(listOf(1L), store.messages(ACCOUNT).map { it.archiveOrdinal })
        assertEquals(setOf("visible"), store.aliases(ACCOUNT).map { it.value }.toSet())
    }

    @Test
    fun emptyFinalAndAccountScopeApplyWhileInvalidAndRepeatedPagesStop() = runBlocking {
        addAccount(OTHER_ACCOUNT)
        val store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val otherKey = archiveKey(OTHER_ACCOUNT)
        store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("same-result", "account-message", "account")),
            ),
        )

        val emptyFinal = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.AFTER,
                boundaryId = "same-result",
                complete = true,
                hasEarlier = false,
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, emptyFinal.status)
        assertEquals("same-result", emptyFinal.cursor.newestId)

        val invalidEmpty = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.AFTER,
                boundaryId = "same-result",
                complete = false,
                hasEarlier = false,
            ),
        )
        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, invalidEmpty.status)
        assertEquals("same-result", invalidEmpty.cursor.newestId)

        val repeated = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.AFTER,
                boundaryId = "same-result",
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("same-result", "repeat", "account")),
            ),
        )
        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, repeated.status)
        assertEquals(1, store.messages(ACCOUNT).size)

        store.applyArchivePage(
            archivePage(
                key = otherKey,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "same-result",
                        localId = "other-message",
                        body = "other",
                        accountId = OTHER_ACCOUNT,
                    ),
                ),
            ),
        )
        assertEquals(1, store.messages(OTHER_ACCOUNT).size)
        assertEquals("same-result", store.archiveCursor(otherKey)?.newestId)
        assertEquals("same-result", store.archiveCursor(key)?.newestId)
    }

    @Test
    fun archivePageFaultBeforeOrAfterCursorRollsBackWholePage() = runBlocking {
        val key = archiveKey(ACCOUNT)
        for (boundary in listOf(
            MessageWriteBoundary.BEFORE_ARCHIVE_CURSOR,
            MessageWriteBoundary.AFTER_ARCHIVE_CURSOR,
        )) {
            val faulting = MessageStore.observingWrites(database) {
                if (it == boundary) error("fault at $boundary")
            }
            assertSuspendFailure<IllegalStateException> {
                faulting.applyArchivePage(
                    archivePage(
                        key = key,
                        direction = ArchiveDirection.BOOTSTRAP,
                        complete = true,
                        hasEarlier = false,
                        messages = listOf(archived("result-$boundary", "message-$boundary", "body")),
                    ),
                )
            }
            assertTrue(MessageStore(database).messages(ACCOUNT).isEmpty())
            assertTrue(MessageStore(database).aliases(ACCOUNT).isEmpty())
            assertNull(MessageStore(database).archiveCursor(key))
        }
    }

    @Test
    fun crashBoundariesPreserveOriginAndConservativeOutboxState() = runBlocking {
        var store = MessageStore(database)
        assertTrue(store.messages(ACCOUNT).isEmpty()) // before intent commit

        val intent = outbound("stable")
        var outbox = store.compose(intent)
        assertOutbox(outbox, OutboxStatus.PENDING, attempt = 0)
        store = reopenStore() // after intent commit
        assertOutbox(requireNotNull(store.outbox(ACCOUNT, intent.operationId)), OutboxStatus.PENDING, 0)

        store.claim(ACCOUNT, intent.operationId, generation = 9)
        store = reopenStore() // after claim, before socket call
        outbox = requireNotNull(store.outbox(ACCOUNT, intent.operationId))
        assertOutbox(outbox, OutboxStatus.UNCERTAIN, attempt = 1)

        store.retryUncertain(retryKey(requireNotNull(store.outbox(ACCOUNT, intent.operationId))))
        val handedOff = requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 10))
        store.recordPotentialDelivery(handedOff)
        store = reopenStore() // after socket handoff, before callback
        assertOutbox(requireNotNull(store.outbox(ACCOUNT, intent.operationId)), OutboxStatus.UNCERTAIN, 2)

        store.retryUncertain(retryKey(requireNotNull(store.outbox(ACCOUNT, intent.operationId))))
        val retried = requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 11))
        store.recordPotentialDelivery(retried)
        store = reopenStore() // after explicit retry transport entry
        assertOutbox(requireNotNull(store.outbox(ACCOUNT, intent.operationId)), OutboxStatus.UNCERTAIN, 3)

        store.ingest(
            incoming(
                localId = "server-copy",
                direction = MessageDirection.OUTBOUND,
                sender = SELF,
                aliases = listOf(
                    TrustedIdentityAlias(
                        IdentityAliasKind.ORIGIN_ID,
                        MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                        intent.originId,
                    ),
                ),
            ),
        )
        store = reopenStore() // after confirmation transaction
        assertOutbox(requireNotNull(store.outbox(ACCOUNT, intent.operationId)), OutboxStatus.CONFIRMED, 3)
        assertEquals(listOf(intent.localMessageId), store.messages(ACCOUNT).map { it.localMessageId })
    }

    @Test
    fun onlyDefinitePreHandoffFailureReturnsToPending() = runBlocking {
        val store = MessageStore(database)
        val intent = outbound("failure")
        store.compose(intent)
        val firstClaim = requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 1))
        val pending = requireNotNull(store.recordDefinitePreHandoffFailure(firstClaim))
        assertOutbox(pending, OutboxStatus.PENDING, attempt = 1, originId = intent.originId)

        val secondClaim = requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 2))
        val uncertain = requireNotNull(store.recordPotentialDelivery(secondClaim))
        assertOutbox(uncertain, OutboxStatus.UNCERTAIN, attempt = 2, originId = intent.originId)
        assertNull(store.recordDefinitePreHandoffFailure(secondClaim))
        Unit
    }

    @Test
    fun staleExplicitRetryIsIdempotentAndPreservesIntentIdentity() = runBlocking {
        val store = MessageStore(database)
        val intent = outbound("idempotent-retry")
        store.compose(intent)
        store.recordPotentialDelivery(requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 1)))

        val key = retryKey(requireNotNull(store.outbox(ACCOUNT, intent.operationId)))
        val applied = store.retryUncertain(key)
        val stale = store.retryUncertain(key)

        assertTrue(applied != null)
        assertNull(stale)
        val pending = requireNotNull(store.outbox(ACCOUNT, intent.operationId))
        assertOutbox(pending, OutboxStatus.PENDING, attempt = 1, originId = intent.originId)
        assertEquals(intent.operationId, pending.operationId)
        assertEquals(intent.originId, pending.originId)

        store.recordPotentialDelivery(
            requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 2)),
        )
        val newerAttempt = requireNotNull(store.outbox(ACCOUNT, intent.operationId))
        assertNull(store.retryUncertain(key))
        assertEquals(newerAttempt, store.outbox(ACCOUNT, intent.operationId))
    }

    @Test
    fun staleClaimOutcomesCannotMutateExplicitRetry() = runBlocking {
        val store = MessageStore(database)
        val intent = outbound("stale")
        store.compose(intent)
        val stale = requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 1))
        store.recordPotentialDelivery(stale)
        store.retryUncertain(retryKey(requireNotNull(store.outbox(ACCOUNT, intent.operationId))))
        val current = requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 2))
        val expected = requireNotNull(store.outbox(ACCOUNT, intent.operationId))

        listOf<suspend () -> OutboxEntity?>(
            { store.recordDefinitePreHandoffFailure(stale) },
            { store.recordPotentialDelivery(stale) },
            { store.recordDefiniteFailure(stale, "stale result") },
        ).forEach { staleOutcome ->
            assertNull(staleOutcome())
            assertEquals(expected, store.outbox(ACCOUNT, intent.operationId))
        }
        assertEquals(2L, current.generation)
        assertEquals(2, current.attempt)
    }

    @Test
    fun protocolFailureRequiresExactFirstAttemptDirectMessageAuthority() = runBlocking {
        val store = MessageStore(database)
        val exact = outbound("protocol-exact")
        store.compose(exact)
        store.recordPotentialDelivery(requireNotNull(store.claim(ACCOUNT, exact.operationId, generation = 7)))
        val messageCount = store.messages(ACCOUNT).size

        val failed = requireNotNull(
            store.recordProtocolFailure(
                accountId = ACCOUNT,
                generation = 7,
                operationId = exact.operationId,
                peer = PEER,
                reason = "remote-server-timeout",
            ),
        )
        assertEquals(OutboxStatus.FAILED, failed.status)
        assertEquals("remote-server-timeout", failed.failureReason)
        assertEquals(messageCount, store.messages(ACCOUNT).size)

        val fenced = outbound("protocol-fenced")
        store.compose(fenced)
        store.recordPotentialDelivery(requireNotNull(store.claim(ACCOUNT, fenced.operationId, generation = 8)))
        assertNull(store.recordProtocolFailure(ACCOUNT, 9, fenced.operationId, PEER, "forbidden"))
        assertNull(store.recordProtocolFailure(ACCOUNT, 8, fenced.operationId, "other@example.org", "forbidden"))
        assertEquals(OutboxStatus.UNCERTAIN, store.outbox(ACCOUNT, fenced.operationId)?.status)

        store.retryUncertain(retryKey(requireNotNull(store.outbox(ACCOUNT, fenced.operationId))))
        store.recordPotentialDelivery(requireNotNull(store.claim(ACCOUNT, fenced.operationId, generation = 9)))
        assertNull(store.recordProtocolFailure(ACCOUNT, 9, fenced.operationId, PEER, "forbidden"))
        assertEquals(2, store.outbox(ACCOUNT, fenced.operationId)?.attempt)

        val room = outbound("protocol-room").copy(
            peerJid = "room@conference.example.org",
            messageKind = MessageKind.GROUPCHAT,
        )
        store.compose(room)
        store.recordPotentialDelivery(requireNotNull(store.claim(ACCOUNT, room.operationId, generation = 10)))
        assertNull(
            store.recordProtocolFailure(
                ACCOUNT,
                10,
                room.operationId,
                room.peerJid,
                "service-unavailable",
            ),
        )
        assertEquals(OutboxStatus.UNCERTAIN, store.outbox(ACCOUNT, room.operationId)?.status)
    }

    @Test
    fun trustedEchoPreservesLocalComposeTimeAndSurvivesReopen() = runBlocking {
        val localTime = 2_000L
        val archiveTime = 1_000L
        val store = MessageStore(database, clock = { localTime })
        val intent = outbound("timestamp")
        store.compose(intent)
        val composed = store.messages(ACCOUNT).single()
        assertEquals(localTime, composed.sentAtEpochMs)
        assertEquals(MessageTimeSource.LOCAL, composed.sentTimeSource)

        store.ingest(
            incoming(
                localId = "timestamp-echo",
                sender = SELF,
                direction = MessageDirection.OUTBOUND,
                aliases = listOf(
                    TrustedIdentityAlias(
                        IdentityAliasKind.ORIGIN_ID,
                        MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                        intent.originId,
                    ),
                ),
            ).copy(
                sentAtEpochMs = archiveTime,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )

        assertEquals(OutboxStatus.CONFIRMED, store.outbox(ACCOUNT, intent.operationId)?.status)
        val reconciled = store.messages(ACCOUNT).single()
        assertEquals(localTime, reconciled.sentAtEpochMs)
        assertEquals(MessageTimeSource.LOCAL, reconciled.sentTimeSource)

        database.close()
        database = NemaDatabase.create(context, databaseName)
        val reopened = MessageStore(database).messages(ACCOUNT).single()
        assertEquals(localTime, reopened.sentAtEpochMs)
        assertEquals(MessageTimeSource.LOCAL, reopened.sentTimeSource)
    }

    @Test
    fun outboundEchoWithoutDelayKeepsFirstLocalTimeAfterClockMovesBackward() = runBlocking {
        var now = 2_000L
        val store = MessageStore(database, clock = { now })
        val intent = outbound("sticky-local-time")
        store.compose(intent)
        now = 1_000L

        store.ingest(
            incoming(
                localId = "sticky-local-time-echo",
                sender = SELF,
                direction = MessageDirection.OUTBOUND,
                aliases = listOf(
                    TrustedIdentityAlias(
                        IdentityAliasKind.ORIGIN_ID,
                        MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                        intent.originId,
                    ),
                ),
            ),
        )

        val reconciled = store.messages(ACCOUNT).single()
        assertEquals(2_000L, reconciled.sentAtEpochMs)
        assertEquals(MessageTimeSource.LOCAL, reconciled.sentTimeSource)
    }

    @Test
    fun inboundDuplicateWithoutDelayKeepsFirstLocalFallbackAfterClockMovesBackward() = runBlocking {
        var now = 2_000L
        val alias = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "sticky-inbound-time")
        val store = MessageStore(database, clock = { now })
        store.ingest(incoming(localId = "first-local-copy", aliases = listOf(alias)))
        now = 1_000L

        store.ingest(incoming(localId = "duplicate-local-copy", aliases = listOf(alias)))

        val reconciled = store.messages(ACCOUNT).single()
        assertEquals("first-local-copy", reconciled.localMessageId)
        assertEquals(2_000L, reconciled.sentAtEpochMs)
        assertEquals(MessageTimeSource.LOCAL, reconciled.sentTimeSource)
    }

    @Test
    fun trustedArchiveUpgradesInboundLocalFallbackWithoutDuplication() = runBlocking {
        val localTime = 2_000L
        val archiveTime = 1_000L
        val alias = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "incoming-time")
        val store = MessageStore(database, clock = { localTime })
        store.ingest(incoming(localId = "live", aliases = listOf(alias)))
        val local = store.messages(ACCOUNT).single()
        assertEquals(localTime, local.sentAtEpochMs)
        assertEquals(MessageTimeSource.LOCAL, local.sentTimeSource)

        store.ingest(
            incoming(localId = "archive", aliases = listOf(alias)).copy(
                sentAtEpochMs = archiveTime,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )

        val upgraded = store.messages(ACCOUNT).single()
        assertEquals("live", upgraded.localMessageId)
        assertEquals(archiveTime, upgraded.sentAtEpochMs)
        assertEquals(MessageTimeSource.MAM, upgraded.sentTimeSource)
    }

    @Test
    fun outboundLocalTimeWinsWhenArchiveCopyArrivesFirst() = runBlocking {
        val alias = TrustedIdentityAlias(
            IdentityAliasKind.ORIGIN_ID,
            MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
            "reverse-time",
        )
        val store = MessageStore(database)
        fun outboundCopy(localId: String, time: Long, source: MessageTimeSource) = incoming(
            localId = localId,
            sender = SELF,
            direction = MessageDirection.OUTBOUND,
            aliases = listOf(alias),
        ).copy(sentAtEpochMs = time, sentTimeSource = source)

        store.ingest(outboundCopy("archive-first", 1_000L, MessageTimeSource.MAM))
        store.ingest(outboundCopy("local-later", 2_000L, MessageTimeSource.LOCAL))

        val reconciled = store.messages(ACCOUNT).single()
        assertEquals("archive-first", reconciled.localMessageId)
        assertEquals(2_000L, reconciled.sentAtEpochMs)
        assertEquals(MessageTimeSource.LOCAL, reconciled.sentTimeSource)
    }

    private suspend fun addAccount(id: String) {
        database.accountDao().upsert(
            AccountEntity(
                id = id,
                bareJid = "$id@example.org",
                authenticationId = id,
                authorizationId = null,
                serviceDomain = "example.org",
                networkHost = null,
                networkPort = null,
            ),
        )
    }

    private fun alias(value: String) = TrustedIdentityAlias(
        kind = IdentityAliasKind.STANZA_ID,
        authority = "archive.example.org",
        value = value,
    )

    private fun stanzaAlias(value: String) = TrustedIdentityAlias(
        kind = IdentityAliasKind.STANZA_ID,
        authority = SELF,
        value = value,
    )

    private fun archiveKey(accountId: String) = ArchiveCursorKey(
        accountId = accountId,
        archiveAuthority = "$accountId@example.org",
        scope = "ACCOUNT",
    )

    private fun archivePage(
        key: ArchiveCursorKey,
        direction: ArchiveDirection,
        boundaryId: String? = null,
        complete: Boolean,
        hasEarlier: Boolean,
        messages: List<ArchivedIncomingMessage> = emptyList(),
    ) = ArchivePage(
        key = key,
        direction = direction,
        boundaryId = boundaryId,
        complete = complete,
        hasEarlier = hasEarlier,
        stable = true,
        firstId = messages.firstOrNull()?.resultId,
        lastId = messages.lastOrNull()?.resultId,
        messages = messages,
    )

    private fun archived(
        resultId: String,
        localId: String,
        body: String,
        alias: TrustedIdentityAlias? = null,
        direction: MessageDirection = MessageDirection.INBOUND,
        sender: String = PEER,
        peerJid: String = PEER,
        messageKind: MessageKind = MessageKind.CHAT,
        accountId: String = ACCOUNT,
        threadId: String? = null,
        parentThreadId: String? = null,
    ) = ArchivedIncomingMessage(
        resultId = resultId,
        message = incoming(
            accountId = accountId,
            localId = localId,
            sender = sender,
            peerJid = peerJid,
            messageKind = messageKind,
            direction = direction,
            body = body,
            aliases = listOfNotNull(alias),
            threadId = threadId,
            parentThreadId = parentThreadId,
        ),
    )

    private fun incoming(
        accountId: String = ACCOUNT,
        localId: String,
        sender: String = PEER,
        peerJid: String = PEER,
        messageKind: MessageKind = MessageKind.CHAT,
        direction: MessageDirection = MessageDirection.INBOUND,
        body: String = "body",
        archiveOrdinal: Long? = null,
        archiveAuthority: String? = archiveOrdinal?.let { SELF },
        archiveScope: String? = archiveOrdinal?.let { "ACCOUNT" },
        aliases: List<TrustedIdentityAlias> = emptyList(),
        threadId: String? = null,
        parentThreadId: String? = null,
    ) = IncomingMessage(
        accountId = accountId,
        localMessageId = localId,
        peerJid = peerJid,
        senderJid = sender,
        direction = direction,
        messageKind = messageKind,
        threadId = threadId,
        parentThreadId = parentThreadId,
        body = body,
        archiveOrdinal = archiveOrdinal,
        archiveAuthority = archiveAuthority,
        archiveScope = archiveScope,
        aliases = aliases,
    )

    private fun outbound(suffix: String) = OutboundIntent(
        accountId = ACCOUNT,
        operationId = "operation-$suffix",
        localMessageId = "local-$suffix",
        originId = "origin-$suffix",
        peerJid = PEER,
        senderJid = SELF,
        messageKind = MessageKind.CHAT,
        threadId = null,
        parentThreadId = null,
        body = "body",
    )

    private fun retryKey(outbox: OutboxEntity) = RetryUncertainKey(
        accountId = outbox.accountId,
        operationId = outbox.operationId,
        generation = requireNotNull(outbox.generation),
        attempt = outbox.attempt,
    )

    private suspend fun reopenStore(): MessageStore {
        database.close()
        database = NemaDatabase.create(context, databaseName)
        return MessageStore(database).also { it.messages(ACCOUNT) }
    }

    private fun assertOutbox(
        outbox: OutboxEntity,
        status: OutboxStatus,
        attempt: Int,
        originId: String = "origin-stable",
    ) {
        assertEquals(status, outbox.status)
        assertEquals(originId, outbox.originId)
        assertEquals(attempt, outbox.attempt)
    }

    private inline fun <reified T : Throwable> assertFailure(block: () -> Unit): T {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
        return failure as T
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFailure(
        crossinline block: suspend () -> Unit,
    ): T {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
        return failure as T
    }

    companion object {
        private const val ACCOUNT = "account"
        private const val OTHER_ACCOUNT = "other"
        private const val PEER = "peer@example.org"
        private const val SELF = "account@example.org"
    }
}
