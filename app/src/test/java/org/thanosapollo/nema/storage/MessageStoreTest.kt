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
import org.thanosapollo.nema.thread.ThreadIdFactory
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.draftKey
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource
import org.thanosapollo.nema.xmpp.transport.MessageReceiptStage

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
    fun directReceiptStateIsExactScopedMonotonicAndDurable() = runBlocking {
        var store = MessageStore(database)
        val intent = outbound("receipt")
        store.compose(intent)

        val displayed = requireNotNull(
            store.recordReceiptSignal(
                ACCOUNT,
                PEER,
                PEER,
                intent.operationId,
                MessageReceiptStage.DISPLAYED,
            ),
        )
        assertEquals(MessageReceiptStage.DISPLAYED, displayed.receiptStage)
        assertEquals(
            MessageReceiptStage.DISPLAYED,
            store.recordReceiptSignal(
                ACCOUNT,
                PEER,
                PEER,
                intent.operationId,
                MessageReceiptStage.RECEIVED,
            )?.receiptStage,
        )
        assertNull(
            store.recordReceiptSignal(
                ACCOUNT,
                "other@example.org",
                "other@example.org",
                intent.operationId,
                MessageReceiptStage.ACKNOWLEDGED,
            ),
        )

        store = reopenStore()
        assertEquals(
            MessageReceiptStage.DISPLAYED,
            store.outbox(ACCOUNT, intent.operationId)?.receiptStage,
        )
    }

    @Test
    fun archivedReceiptControlAdvancesCursorAndUpdatesExactOutboundMessage() = runBlocking {
        val store = MessageStore(database)
        val intent = outbound("archived-receipt")
        store.compose(intent)
        val control = ArchivedIncomingMessage(
            resultId = "receipt-result",
            message = null,
            signal = ArchivedReceiptSignal(
                peerJid = PEER,
                senderJid = PEER,
                targetId = intent.operationId,
                stage = MessageReceiptStage.DISPLAYED,
            ),
        )

        val result = store.applyArchivePage(
            archivePage(
                key = archiveKey(ACCOUNT),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(control),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals("receipt-result", result.cursor.oldestId)
        assertEquals("receipt-result", result.cursor.newestId)
        assertEquals(
            MessageReceiptStage.DISPLAYED,
            store.outbox(ACCOUNT, intent.operationId)?.receiptStage,
        )
    }

    @Test
    fun roomControlPlaceholderAdvancesCursorWithoutChangingDirectOutbox() = runBlocking {
        val store = MessageStore(database)
        val intent = outbound("room-control")
        store.compose(intent)

        val result = store.applyArchivePage(
            archivePage(
                key = archiveKey(ACCOUNT),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    ArchivedIncomingMessage(
                        resultId = "room-control-result",
                        message = null,
                        signal = null,
                    ),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals("room-control-result", result.cursor.oldestId)
        assertEquals("room-control-result", result.cursor.newestId)
        assertEquals(null, store.outbox(ACCOUNT, intent.operationId)?.receiptStage)
        assertEquals(
            listOf(intent.localMessageId),
            database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first().map(TimelineRow::localMessageId),
        )
    }

    @Test
    fun ownDisplayedMarkerAdvancesLastReadThroughExactInboundOnly() = runBlocking {
        val store = MessageStore(database)
        val firstAlias = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "first-wire")
        val laterAlias = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "later-wire")
        store.ingest(incoming(localId = "first", aliases = listOf(firstAlias)))
        store.ingest(incoming(localId = "later", aliases = listOf(laterAlias)))
        val first = requireNotNull(database.messageDao().message(ACCOUNT, "first"))
        val later = requireNotNull(database.messageDao().message(ACCOUNT, "later"))
        val outbound = store.compose(outbound("own-displayed"))

        assertEquals(0L, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
        assertNull(
            store.recordReceiptSignal(
                ACCOUNT,
                PEER,
                SELF,
                firstAlias.value,
                MessageReceiptStage.RECEIVED,
            ),
        )
        assertEquals(0L, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
        assertNull(
            store.recordReceiptSignal(
                ACCOUNT,
                PEER,
                SELF,
                firstAlias.value,
                MessageReceiptStage.DISPLAYED,
            ),
        )
        assertEquals(first.localSequence, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
        assertNull(
            store.recordReceiptSignal(
                ACCOUNT,
                PEER,
                SELF,
                "missing-wire",
                MessageReceiptStage.DISPLAYED,
            ),
        )
        assertNull(
            store.recordReceiptSignal(
                ACCOUNT,
                PEER,
                SELF,
                outbound.originId,
                MessageReceiptStage.DISPLAYED,
            ),
        )
        assertEquals(first.localSequence, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
        assertNull(
            store.recordReceiptSignal(
                ACCOUNT,
                PEER,
                SELF,
                laterAlias.value,
                MessageReceiptStage.DISPLAYED,
            ),
        )
        assertEquals(later.localSequence, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
        assertNull(store.outbox(ACCOUNT, outbound.operationId)?.receiptStage)
    }

    @Test
    fun archivedOwnDisplayedAdvancesLastReadWithoutTouchingOutbox() = runBlocking {
        val store = MessageStore(database)
        val inboundAlias = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "archived-inbound")
        store.ingest(incoming(localId = "archived-in", aliases = listOf(inboundAlias)))
        val inbound = requireNotNull(database.messageDao().message(ACCOUNT, "archived-in"))
        val intent = outbound("archived-own-displayed")
        store.compose(intent)

        val result = store.applyArchivePage(
            archivePage(
                key = archiveKey(ACCOUNT),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    ArchivedIncomingMessage(
                        resultId = "own-displayed-result",
                        message = null,
                        signal = ArchivedReceiptSignal(
                            peerJid = PEER,
                            senderJid = SELF,
                            targetId = inboundAlias.value,
                            stage = MessageReceiptStage.DISPLAYED,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(inbound.localSequence, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
        assertEquals(null, store.outbox(ACCOUNT, intent.operationId)?.receiptStage)
    }

    @Test
    fun ownOutboundCarbonAdvancesLastReadThroughPriorInbound() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(localId = "seen-on-other-client"))
        val inbound = requireNotNull(database.messageDao().message(ACCOUNT, "seen-on-other-client"))
        assertEquals(0L, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)

        store.ingest(
            incoming(
                localId = "emacs-reply",
                sender = SELF,
                direction = MessageDirection.OUTBOUND,
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, MessageStore.OUTBOUND_ORIGIN_AUTHORITY, "emacs-msg-1"),
                ),
            ),
        )
        val outbound = requireNotNull(database.messageDao().message(ACCOUNT, "emacs-reply"))
        assertEquals(outbound.localSequence, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
        assertTrue(outbound.localSequence > inbound.localSequence)

        store.ingest(incoming(localId = "after-reply"))
        val later = requireNotNull(database.messageDao().message(ACCOUNT, "after-reply"))
        assertEquals(outbound.localSequence, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
        assertTrue(later.localSequence > outbound.localSequence)
    }

    @Test
    fun peerDisplayedMarksCarbonOutboundRead() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(
                localId = "emacs-reply",
                sender = SELF,
                direction = MessageDirection.OUTBOUND,
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, MessageStore.OUTBOUND_ORIGIN_AUTHORITY, "emacs-msg-1"),
                    TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, SELF, "emacs-msg-1"),
                ),
            ),
        )

        val displayed = requireNotNull(
            store.recordReceiptSignal(
                ACCOUNT,
                PEER,
                PEER,
                "emacs-msg-1",
                MessageReceiptStage.DISPLAYED,
            ),
        )
        assertEquals(MessageReceiptStage.DISPLAYED, displayed.receiptStage)
        assertEquals(
            MessageReceiptStage.DISPLAYED,
            store.outbox(ACCOUNT, "emacs-msg-1")?.receiptStage,
        )
    }

    @Test
    fun historicalBeforeOutboundDoesNotAdvanceLastReadPastLaterInbound() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(localId = "recent-in"))
        val inbound = requireNotNull(database.messageDao().message(ACCOUNT, "recent-in"))
        assertEquals(0L, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)

        val key = archiveKey(ACCOUNT)
        val bootstrap = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    archived("recent-result", "recent-in", "body", stanzaAlias("recent-in")),
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, bootstrap.status)
        assertEquals(0L, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)

        val older = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "recent-result",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "old-out-result",
                        localId = "old-emacs-send",
                        body = "old send",
                        alias = TrustedIdentityAlias(
                            IdentityAliasKind.ORIGIN_ID,
                            MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                            "emacs-old",
                        ),
                        direction = MessageDirection.OUTBOUND,
                        sender = SELF,
                    ),
                ),
            ),
        )
        assertEquals(ArchivePageStatus.APPLIED, older.status)
        val historical = requireNotNull(database.messageDao().message(ACCOUNT, "old-emacs-send"))
        assertTrue(historical.localSequence > inbound.localSequence)
        assertEquals(0L, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
    }

    @Test
    fun onlyLiveAndForwardCatchUpInboundMessagesCountAsUnread() = runBlocking {
        val store = MessageStore(database)
        val liveAlias = stanzaAlias("live")
        store.ingest(incoming(localId = "live", body = "live", aliases = listOf(liveAlias)))
        val key = archiveKey(ACCOUNT)

        store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    archived("r1", "history", "history", stanzaAlias("history")),
                    archived("r2", "live-copy", "live", liveAlias),
                    archived("r3", "boundary", "boundary", stanzaAlias("boundary")),
                ),
            ),
        )
        assertEquals(1, database.messageDao().observeConversationSummaries(ACCOUNT).first().single().unreadCount)

        store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.AFTER,
                boundaryId = "r3",
                complete = true,
                hasEarlier = true,
                messages = listOf(
                    archived("r3", "boundary-replay", "boundary", stanzaAlias("boundary")),
                    archived("r4", "catch-up", "catch-up", stanzaAlias("catch-up")),
                ),
            ),
        )
        assertEquals(2, database.messageDao().observeConversationSummaries(ACCOUNT).first().single().unreadCount)

        store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "r1",
                complete = true,
                hasEarlier = false,
                messages = listOf(archived("r0", "older", "older", stanzaAlias("older"))),
            ),
        )
        assertEquals(2, database.messageDao().observeConversationSummaries(ACCOUNT).first().single().unreadCount)
    }

    @Test
    fun directCorrectionsReconcileDeferredIdempotentlyAndPreserveBaseIdentity() = runBlocking {
        var store = MessageStore(database)
        val targetAlias = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "original-wire-id")
        val correctionAlias = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "correction-wire-id")
        store.ingest(
            incoming(
                localId = "correction-first",
                body = "corrected body",
                aliases = listOf(correctionAlias),
                replaceId = "original-wire-id",
            ).copy(sentAtEpochMs = 2_000L, sentTimeSource = MessageTimeSource.MAM),
        )
        assertTrue(database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first().isEmpty())

        store.ingest(
            incoming(
                localId = "original-later",
                body = "original body",
                aliases = listOf(targetAlias),
            ).copy(sentAtEpochMs = 1_000L, sentTimeSource = MessageTimeSource.MAM),
        )
        var row = database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first().single()
        assertEquals("original-later", row.localMessageId)
        assertEquals("corrected body", row.correctedBody)
        assertTrue(row.edited)

        store.ingest(
            incoming(
                localId = "older-correction",
                body = "stale correction",
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "older-correction-id")),
                replaceId = "original-wire-id",
            ).copy(sentAtEpochMs = 1_500L, sentTimeSource = MessageTimeSource.MAM),
        )
        store.ingest(
            incoming(
                localId = "correction-replay",
                body = "corrected body",
                aliases = listOf(correctionAlias),
                replaceId = "original-wire-id",
            ).copy(sentAtEpochMs = 2_000L, sentTimeSource = MessageTimeSource.MAM),
        )
        assertEquals(3, store.messages(ACCOUNT).size)
        store = reopenStore()
        row = database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first().single()
        assertEquals("original-later", row.localMessageId)
        assertEquals("corrected body", row.correctedBody)
        assertTrue(row.edited)
        assertEquals("correction-first", store.messages(ACCOUNT).single { it.body == "corrected body" }.localMessageId)
    }

    @Test
    fun correctionTargetSurvivesDuplicateOriginalMergeAndRestart() = runBlocking {
        var store = MessageStore(database)
        val originAlias = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "original-origin")
        val targetAlias = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "original-message-id")
        store.ingest(
            incoming(localId = "origin-original", body = "original", aliases = listOf(originAlias)),
        )
        store.ingest(
            incoming(localId = "message-original", body = "original", aliases = listOf(targetAlias)),
        )
        store.ingest(
            incoming(
                localId = "correction",
                body = "corrected",
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "correction-message-id"),
                ),
                replaceId = "original-message-id",
            ),
        )

        store.ingest(
            incoming(
                localId = "bridging-replay",
                body = "original",
                aliases = listOf(originAlias, targetAlias),
            ),
        )
        store = reopenStore()

        val row = database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first().single()
        assertEquals("origin-original", row.localMessageId)
        assertEquals("corrected", row.correctedBody)
        assertTrue(row.edited)
        assertEquals(
            "origin-original",
            store.messages(ACCOUNT).single { it.replaceId != null }.correctionTargetMessageId,
        )
    }

    @Test
    fun directCorrectionsFailClosedOutsideExactInboundConversationAuthority() = runBlocking {
        addAccount(OTHER_ACCOUNT)
        val store = MessageStore(database)
        val targetAlias = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "target-wire-id")
        store.ingest(incoming(localId = "target", body = "original", aliases = listOf(targetAlias)))

        val invalid = listOf(
            incoming(
                accountId = OTHER_ACCOUNT,
                localId = "wrong-account",
                body = "wrong account",
                replaceId = "target-wire-id",
            ),
            incoming(
                localId = "wrong-peer",
                peerJid = "other@example.org",
                sender = "other@example.org",
                body = "wrong peer",
                replaceId = "target-wire-id",
            ),
            incoming(
                localId = "wrong-sender",
                sender = "attacker@example.org",
                body = "wrong sender",
                replaceId = "target-wire-id",
            ),
            incoming(
                localId = "wrong-thread",
                threadId = "different-thread",
                body = "wrong thread",
                replaceId = "target-wire-id",
            ),
            incoming(
                localId = "attachment-correction",
                body = "attachment correction",
                replaceId = "target-wire-id",
            ).copy(attachmentUrl = "https://example.org/file"),
            incoming(
                localId = "reply-correction",
                body = "reply correction",
                replaceId = "target-wire-id",
            ).copy(replyToId = "another-message"),
        )
        invalid.forEach { store.ingest(it) }

        val row = database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first().single()
        assertEquals("target", row.localMessageId)
        assertEquals("original", row.body)
        assertEquals(null, row.correctedBody)
        assertFalse(row.edited)
        assertTrue(store.messages(ACCOUNT).filter { it.replaceId != null }.all {
            it.correctionTargetMessageId == null
        })
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
                    "direct_thread_sessions",
                    "message_thread_titles",
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
            threadIds = ThreadIdFactory { ThreadId.require("rolled-back-session") },
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
        assertNull(database.messageDao().directThreadSession(ACCOUNT, PEER))
        assertNull(database.messageDao().thread(ACCOUNT, PEER, MessageKind.CHAT, "rolled-back-session"))

        store = reopenStore()
        assertNull(database.messageDao().directThreadSession(ACCOUNT, PEER))
        assertNull(database.messageDao().thread(ACCOUNT, PEER, MessageKind.CHAT, "rolled-back-session"))

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
    fun directCorrectionAuthoringIsDurableAndProjectsOneStableOriginal() = runBlocking {
        var store = MessageStore(database)
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "original-wire-id",
            localMessageId = "original-local-id",
            originId = "original-origin-id",
            peerJid = PEER,
            senderJid = SELF,
            body = "original body",
        )
        val originalOutbox = requireNotNull(store.outbox(ACCOUNT, "original-wire-id"))
        database.messageDao().updateOutbox(originalOutbox.copy(status = OutboxStatus.ACKNOWLEDGED))

        val correction = store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "correction-wire-id",
            localMessageId = "correction-local-id",
            originId = "correction-origin-id",
            peerJid = PEER,
            senderJid = SELF,
            body = "corrected body",
            replaceId = "original-wire-id",
            correctionTargetMessageId = "original-local-id",
        )

        assertEquals("correction-wire-id", requireNotNull(correction).operationId)
        val pending = store.pendingOutbound(ACCOUNT).single()
        assertEquals("original-wire-id", pending.replaceId)
        assertEquals("correction-local-id", pending.localMessageId)
        val visible = database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first().single()
        assertEquals("original-local-id", visible.localMessageId)
        assertEquals("corrected body", visible.correctedBody)
        assertTrue(visible.edited)
        assertEquals("original-wire-id", visible.operationId)

        store = reopenStore()
        assertEquals("original-wire-id", store.pendingOutbound(ACCOUNT).single().replaceId)
        assertEquals(
            "corrected body",
            database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first().single().correctedBody,
        )
    }

    @Test
    fun directCorrectionAuthoringFailsClosedWithoutExactSentTargetAuthority() = runBlocking {
        val store = MessageStore(database)
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "original-wire-id",
            localMessageId = "original-local-id",
            originId = "original-origin-id",
            peerJid = PEER,
            senderJid = SELF,
            body = "original body",
        )

        suspend fun correction(
            localId: String,
            targetLocalId: String = "original-local-id",
            targetWireId: String = "original-wire-id",
            peer: String = PEER,
            body: String = "corrected body",
        ) = store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "wire-$localId",
            localMessageId = localId,
            originId = "origin-$localId",
            peerJid = peer,
            senderJid = SELF,
            body = body,
            replaceId = targetWireId,
            correctionTargetMessageId = targetLocalId,
        )

        assertSuspendFailure<IllegalArgumentException> { correction("pending-target") }
        val original = requireNotNull(store.outbox(ACCOUNT, "original-wire-id"))
        database.messageDao().updateOutbox(original.copy(status = OutboxStatus.ACKNOWLEDGED))
        assertSuspendFailure<IllegalArgumentException> { correction("wrong-local", "missing-local-id") }
        assertSuspendFailure<IllegalArgumentException> { correction("wrong-peer", peer = "other@example.org") }
        assertSuspendFailure<IllegalArgumentException> { correction("unchanged", body = "original body") }

        suspend fun addSentTarget(
            localId: String,
            wireId: String,
            attachmentUrl: String? = null,
            replyToId: String? = null,
        ) {
            store.composeDirectDraft(
                accountId = ACCOUNT,
                operationId = wireId,
                localMessageId = localId,
                originId = "origin-$localId",
                peerJid = PEER,
                senderJid = SELF,
                body = "target $localId",
                attachmentUrl = attachmentUrl,
                replyToId = replyToId,
                replyToJid = replyToId?.let { PEER },
                replyFallbackBody = replyToId?.let { "quoted body" },
            )
            val outbox = requireNotNull(store.outbox(ACCOUNT, wireId))
            database.messageDao().updateOutbox(outbox.copy(status = OutboxStatus.ACKNOWLEDGED))
        }

        addSentTarget("attachment-local", "attachment-wire", attachmentUrl = "https://example.org/file")
        addSentTarget("reply-local", "reply-wire", replyToId = "quoted-wire")
        database.messageDao().saveDraft(ACCOUNT, PEER, "", "unrelated draft")
        assertSuspendFailure<IllegalArgumentException> {
            correction("attachment-edit", "attachment-local", "attachment-wire")
        }
        assertSuspendFailure<IllegalArgumentException> {
            correction("reply-edit", "reply-local", "reply-wire")
        }

        assertEquals(
            setOf("original-local-id", "attachment-local", "reply-local"),
            store.messages(ACCOUNT).map { it.localMessageId }.toSet(),
        )
        assertTrue(store.pendingOutbound(ACCOUNT).isEmpty())
        assertEquals("unrelated draft", database.messageDao().draft(ACCOUNT, PEER, "")?.body)
    }

    @Test
    fun ordinaryDirectSendsReuseDurableTopLevelSessionAcrossRestart() = runBlocking {
        val ids = ArrayDeque(listOf("session-a", "unused"))
        var store = MessageStore(
            database,
            clock = { 1_000L },
            threadIds = ThreadIdFactory { ThreadId.require(ids.removeFirst()) },
        )

        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "session-first-wire",
            localMessageId = "session-first-local",
            originId = "session-first-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "first",
        )
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "session-second-wire",
            localMessageId = "session-second-local",
            originId = "session-second-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "second",
        )
        store = reopenStore()
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "session-third-wire",
            localMessageId = "session-third-local",
            originId = "session-third-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "third",
        )

        assertEquals(listOf("session-a", "session-a", "session-a"), store.messages(ACCOUNT).map { it.threadId })
        assertTrue(store.messages(ACCOUNT).all { it.parentThreadId == null })
    }

    @Test
    fun liveInboundAdoptsTopLevelRotatesThreadlessAndIgnoresMamSession() = runBlocking {
        val ids = ArrayDeque(listOf("after-threadless"))
        val store = MessageStore(
            database,
            clock = { 1_000L },
            threadIds = ThreadIdFactory { ThreadId.require(ids.removeFirst()) },
        )
        store.ingest(incoming(localId = "live-agent", threadId = "agent-session"))
        store.ingest(
            incoming(localId = "history-agent", threadId = "historical-session").copy(
                sentAtEpochMs = 500L,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "adopted-wire",
            localMessageId = "adopted-local",
            originId = "adopted-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "reply in adopted session",
        )
        store.ingest(incoming(localId = "legacy-live"))
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "rotated-wire",
            localMessageId = "rotated-local",
            originId = "rotated-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "reply after legacy stanza",
        )

        val messages = store.messages(ACCOUNT).associateBy { it.localMessageId }
        assertEquals("agent-session", messages.getValue("adopted-local").threadId)
        assertEquals("after-threadless", messages.getValue("rotated-local").threadId)
        assertNull(messages.getValue("legacy-live").threadId)
    }

    @Test
    fun freshExplicitTopicIsParentedToCurrentSessionButEstablishedLineageIsPreserved() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(localId = "current", threadId = "current-session"))

        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "new-topic-wire",
            localMessageId = "new-topic-local",
            originId = "new-topic-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "new topic",
            thread = ThreadRef(ThreadId.require("new-topic")),
            draftThread = null,
        )
        store.ingest(incoming(localId = "other-top-level", threadId = "other-session"))
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "known-topic-wire",
            localMessageId = "known-topic-local",
            originId = "known-topic-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "known topic",
            thread = ThreadRef(ThreadId.require("current-session")),
        )

        val messages = store.messages(ACCOUNT).associateBy { it.localMessageId }
        assertEquals("current-session", messages.getValue("new-topic-local").parentThreadId)
        assertNull(messages.getValue("known-topic-local").parentThreadId)
    }

    @Test
    fun liveReplyInheritsKnownChildParentWhenParentElementIsNotRepeated() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(localId = "current", threadId = "current-session"))
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "child-wire",
            localMessageId = "child-local",
            originId = "child-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "child request",
            thread = ThreadRef(ThreadId.require("child-session"), ThreadId.require("current-session")),
        )

        store.ingest(incoming(localId = "child-response", threadId = "child-session"))

        val response = store.messages(ACCOUNT).single { it.localMessageId == "child-response" }
        assertEquals("current-session", response.parentThreadId)
    }

    @Test
    fun groupchatDraftSendRemainsThreadlessAndDoesNotCreateDirectSession() = runBlocking {
        val room = "room@conference.example.org"
        MessageStore(database).composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "room-session-wire",
            localMessageId = "room-session-local",
            originId = "room-session-origin",
            peerJid = room,
            senderJid = SELF,
            body = "room",
            messageKind = MessageKind.GROUPCHAT,
        )

        val message = MessageStore(database).messages(ACCOUNT).single()
        assertNull(message.threadId)
        assertNull(database.messageDao().directThreadSession(ACCOUNT, room))
    }

    @Test
    fun replayedLiveIdentityDoesNotRewindOrRerotateDirectSession() = runBlocking {
        val generated = ArrayDeque(listOf("rotated-session", "unexpected-session"))
        val factory = ThreadIdFactory { ThreadId.require(generated.removeFirst()) }
        var store = MessageStore(database, clock = { 1_000L }, threadIds = factory)
        val topLevelAlias = stanzaAlias("top-level-wire")
        val threadlessAlias = stanzaAlias("threadless-wire")
        store.ingest(
            incoming(
                localId = "top-level-live",
                body = "top-level",
                aliases = listOf(topLevelAlias),
                threadId = "remote-session",
            ),
        )
        store.ingest(
            incoming(
                localId = "threadless-live",
                body = "threadless",
                aliases = listOf(threadlessAlias),
            ),
        )
        database.close()
        database = NemaDatabase.create(context, databaseName)
        store = MessageStore(database, clock = { 2_000L }, threadIds = factory)

        store.ingest(
            incoming(
                localId = "top-level-replay",
                body = "top-level",
                aliases = listOf(topLevelAlias),
                threadId = "remote-session",
            ),
        )
        store.ingest(
            incoming(
                localId = "threadless-replay",
                body = "threadless",
                aliases = listOf(threadlessAlias),
            ),
        )
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "after-replay-wire",
            localMessageId = "after-replay-local",
            originId = "after-replay-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "after replay",
        )

        assertEquals("rotated-session", store.messages(ACCOUNT).last().threadId)
        assertEquals(listOf("unexpected-session"), generated.toList())
    }

    @Test
    fun mamOnlyThreadDoesNotCreateSessionBeforeFreshOrdinarySend() = runBlocking {
        val generated = ArrayDeque(listOf("fresh-local-session"))
        val store = MessageStore(
            database,
            clock = { 1_000L },
            threadIds = ThreadIdFactory { ThreadId.require(generated.removeFirst()) },
        )
        store.ingest(
            incoming(localId = "mam-only", threadId = "historical-session").copy(
                sentAtEpochMs = 500L,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        assertNull(database.messageDao().directThreadSession(ACCOUNT, PEER))

        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "fresh-wire",
            localMessageId = "fresh-local",
            originId = "fresh-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "fresh",
        )

        assertEquals("fresh-local-session", store.messages(ACCOUNT).last().threadId)
    }

    @Test
    fun timestampLessArchiveCannotTransitionDirectSessionButCorrelatedLiveCopiesCan() = runBlocking {
        val generated = ArrayDeque(listOf("rotated-live-session", "unexpected-session"))
        val store = MessageStore(
            database,
            clock = { 1_000L },
            threadIds = ThreadIdFactory { ThreadId.require(generated.removeFirst()) },
        )
        store.ingest(incoming(localId = "current", threadId = "current-session"))
        val topLevelAlias = stanzaAlias("archived-top-level")
        val threadlessAlias = stanzaAlias("archived-threadless")

        store.applyArchivePage(
            archivePage(
                key = archiveKey(ACCOUNT),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "archive-top-level",
                        localId = "archive-top-level-local",
                        body = "historical top-level",
                        alias = topLevelAlias,
                        threadId = "historical-session",
                    ),
                    archived(
                        resultId = "archive-threadless",
                        localId = "archive-threadless-local",
                        body = "historical threadless",
                        alias = threadlessAlias,
                    ),
                ),
            ),
        )
        assertEquals("current-session", database.messageDao().directThreadSession(ACCOUNT, PEER)?.threadId)

        store.ingest(
            incoming(
                localId = "live-top-level",
                body = "historical top-level",
                aliases = listOf(topLevelAlias),
                threadId = "historical-session",
            ),
        )
        assertEquals("historical-session", database.messageDao().directThreadSession(ACCOUNT, PEER)?.threadId)
        store.ingest(
            incoming(
                localId = "live-threadless",
                body = "historical threadless",
                aliases = listOf(threadlessAlias),
            ),
        )
        assertEquals("rotated-live-session", database.messageDao().directThreadSession(ACCOUNT, PEER)?.threadId)

        store.ingest(
            incoming(
                localId = "live-threadless-replay",
                body = "historical threadless",
                aliases = listOf(threadlessAlias),
            ),
        )
        assertEquals("rotated-live-session", database.messageDao().directThreadSession(ACCOUNT, PEER)?.threadId)
        assertEquals(listOf("unexpected-session"), generated.toList())
    }

    @Test
    fun archiveCannotReparentMessageEmptyReservedDirectSession() = runBlocking {
        val generated = ArrayDeque(listOf("reserved-session", "unexpected-session"))
        val factory = ThreadIdFactory { ThreadId.require(generated.removeFirst()) }
        var store = MessageStore(database, clock = { 1_000L }, threadIds = factory)
        val session = store.ensureDirectThreadSession(ACCOUNT, PEER)
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "reserved-child-wire",
            localMessageId = "reserved-child-local",
            originId = "reserved-child-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "child request",
            thread = ThreadRef(ThreadId.require("reserved-child"), session.id),
        )

        val result = store.applyArchivePage(
            archivePage(
                key = archiveKey(ACCOUNT),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "reserved-session-result",
                        localId = "reserved-session-archive",
                        body = "conflicting historical lineage",
                        threadId = session.id.value,
                        parentThreadId = "archive-parent",
                    ),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertNull(database.messageDao().thread(ACCOUNT, PEER, MessageKind.CHAT, session.id.value)?.parentThreadId)
        assertNull(database.messageDao().thread(ACCOUNT, PEER, MessageKind.CHAT, "archive-parent"))
        assertEquals(session.id.value, database.messageDao().directThreadSession(ACCOUNT, PEER)?.threadId)
        database.close()
        database = NemaDatabase.create(context, databaseName)
        store = MessageStore(database, clock = { 2_000L }, threadIds = factory)
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "after-reserved-archive-wire",
            localMessageId = "after-reserved-archive-local",
            originId = "after-reserved-archive-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "ordinary send",
        )

        assertEquals(session.id.value, store.messages(ACCOUNT).last().threadId)
        assertEquals(listOf("unexpected-session"), generated.toList())
    }

    @Test
    fun explicitDirectLineageFailuresRollbackMessageOutboxDraftAndSession() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(localId = "current", threadId = "current-session"))
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "known-child-wire",
            localMessageId = "known-child-local",
            originId = "known-child-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "known child",
            thread = ThreadRef(ThreadId.require("known-child"), ThreadId.require("current-session")),
        )
        database.messageDao().saveDraft(ACCOUNT, PEER, "", "keep draft")
        val beforeMessages = store.messages(ACCOUNT)
        val beforeSession = database.messageDao().directThreadSession(ACCOUNT, PEER)
        val invalidThreads = listOf(
            ThreadRef(ThreadId.require("known-child")) to null,
            ThreadRef(ThreadId.require("known-child"), ThreadId.require("wrong-parent")) to null,
            ThreadRef(ThreadId.require("new-child"), ThreadId.require("unknown-parent")) to null,
            ThreadRef(ThreadId.require("reply-child"), ThreadId.require("unknown-parent")) to "reply-wire-id",
        )

        invalidThreads.forEachIndexed { index, (invalid, replyToId) ->
            assertSuspendFailure<IllegalArgumentException> {
                store.composeDirectDraft(
                    accountId = ACCOUNT,
                    operationId = "invalid-$index-wire",
                    localMessageId = "invalid-$index-local",
                    originId = "invalid-$index-origin",
                    peerJid = PEER,
                    senderJid = SELF,
                    body = "invalid",
                    thread = invalid,
                    replyToId = replyToId,
                    replyToJid = replyToId?.let { PEER },
                    replyFallbackBody = replyToId?.let { "unknown root" },
                )
            }
        }

        assertEquals(beforeMessages, store.messages(ACCOUNT))
        assertEquals(beforeSession, database.messageDao().directThreadSession(ACCOUNT, PEER))
        assertEquals("keep draft", database.messageDao().draft(ACCOUNT, PEER, "")?.body)
        assertTrue(store.pendingOutbound(ACCOUNT).all { it.localMessageId == "known-child-local" })
    }

    @Test
    fun conflictingParentReplayRejectsWithoutConsumingEitherLineageDraft() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(localId = "current", threadId = "current-session"))
        val original = ThreadRef(ThreadId.require("replayed-child"), ThreadId.require("current-session"))
        val conflicting = ThreadRef(ThreadId.require("replayed-child"), ThreadId.require("wrong-parent"))
        store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "replayed-child-wire",
            localMessageId = "replayed-child-local",
            originId = "replayed-child-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "child request",
            thread = original,
        )
        database.messageDao().saveDraft(ACCOUNT, PEER, original.draftKey(), "original draft")
        database.messageDao().saveDraft(ACCOUNT, PEER, conflicting.draftKey(), "conflicting draft")
        val beforeMessages = store.messages(ACCOUNT)
        val beforeOutboxes = store.outboxes(ACCOUNT)
        val beforeSession = database.messageDao().directThreadSession(ACCOUNT, PEER)

        assertSuspendFailure<IllegalArgumentException> {
            store.composeDirectDraft(
                accountId = ACCOUNT,
                operationId = "replayed-child-wire",
                localMessageId = "replayed-child-local",
                originId = "replayed-child-origin",
                peerJid = PEER,
                senderJid = SELF,
                body = "child request",
                thread = conflicting,
            )
        }

        assertEquals(beforeMessages, store.messages(ACCOUNT))
        assertEquals(beforeOutboxes, store.outboxes(ACCOUNT))
        assertEquals(beforeSession, database.messageDao().directThreadSession(ACCOUNT, PEER))
        assertEquals("original draft", database.messageDao().draft(ACCOUNT, PEER, original.draftKey())?.body)
        assertEquals("conflicting draft", database.messageDao().draft(ACCOUNT, PEER, conflicting.draftKey())?.body)
    }

    @Test
    fun exactOperationReplayNeverMutatesNewerDrafts() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(localId = "current", threadId = "current-session"))
        val original = ThreadRef(ThreadId.require("replay-child"), ThreadId.require("current-session"))
        val other = ThreadRef(ThreadId.require("other-child"), ThreadId.require("current-session"))
        val existing = requireNotNull(
            store.composeDirectDraft(
                accountId = ACCOUNT,
                operationId = "replay-wire",
                localMessageId = "replay-local",
                originId = "replay-origin",
                peerJid = PEER,
                senderJid = SELF,
                body = "sent body",
                thread = original,
            ),
        )
        database.messageDao().saveDraft(ACCOUNT, PEER, original.draftKey(), "newer original draft")
        database.messageDao().saveDraft(ACCOUNT, PEER, other.draftKey(), "newer other draft")

        val originalReplay = store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "replay-wire",
            localMessageId = "replay-local",
            originId = "replay-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "sent body",
            thread = original,
            draftThread = original,
        )

        assertEquals(existing, originalReplay)
        assertEquals("newer original draft", database.messageDao().draft(ACCOUNT, PEER, original.draftKey())?.body)
        assertEquals("newer other draft", database.messageDao().draft(ACCOUNT, PEER, other.draftKey())?.body)

        val otherReplay = store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "replay-wire",
            localMessageId = "replay-local",
            originId = "replay-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "sent body",
            thread = original,
            draftThread = other,
        )

        assertEquals(existing, otherReplay)
        assertEquals("newer original draft", database.messageDao().draft(ACCOUNT, PEER, original.draftKey())?.body)
        assertEquals("newer other draft", database.messageDao().draft(ACCOUNT, PEER, other.draftKey())?.body)
    }

    @Test
    fun directSessionsRemainIsolatedByAccountAndPeerAcrossRestart() = runBlocking {
        addAccount(OTHER_ACCOUNT)
        val otherPeer = "other-peer@example.org"
        var store = MessageStore(database)
        store.ingest(incoming(localId = "primary", threadId = "primary-session"))
        store.ingest(incoming(localId = "other-peer", peerJid = otherPeer, threadId = "peer-session"))
        store.ingest(
            incoming(
                accountId = OTHER_ACCOUNT,
                localId = "other-account",
                peerJid = PEER,
                sender = PEER,
                threadId = "account-session",
            ),
        )
        store = reopenStore()

        assertEquals("primary-session", database.messageDao().directThreadSession(ACCOUNT, PEER)?.threadId)
        assertEquals("peer-session", database.messageDao().directThreadSession(ACCOUNT, otherPeer)?.threadId)
        assertEquals("account-session", database.messageDao().directThreadSession(OTHER_ACCOUNT, PEER)?.threadId)
        assertEquals(3, store.messages(ACCOUNT).size + store.messages(OTHER_ACCOUNT).size)
    }

    @Test
    fun threadDraftSendPersistsExactLineageWithoutConsumingConversationDraft() = runBlocking {
        val thread = ThreadRef(ThreadId.require("child-thread"), ThreadId.require("parent-thread"))
        database.messageDao().saveDraft(ACCOUNT, PEER, "", "conversation draft")
        database.messageDao().saveDraft(ACCOUNT, PEER, thread.draftKey(), "thread draft")

        var store = MessageStore(database)
        store.ingest(incoming(localId = "parent-message", threadId = "parent-thread"))
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

        val message = store.messages(ACCOUNT).single { it.localMessageId == "message-identity" }
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
    fun identitylessLiveInsertPersistsMonotonicObservationAndFloor() = runBlocking {
        val dao = database.accountDao()
        dao.advanceReconciliationWallFloor(ACCOUNT, 500)
        val store = MessageStore(database, clock = { 100 })

        store.ingest(
            incoming(localId = "live-one", sentAtEpochMs = 100, sentTimeSource = MessageTimeSource.LOCAL),
        )
        store.ingest(
            incoming(localId = "live-two", sentAtEpochMs = 600, sentTimeSource = MessageTimeSource.LOCAL),
        )

        assertEquals(
            listOf(500L, 600L),
            store.messages(ACCOUNT).map(MessageEntity::reconciliationObservedAtMs),
        )
        assertEquals(600L, requireNotNull(dao.reconciliationState(ACCOUNT)).wallFloorMs)
    }

    @Test
    fun identitylessReplayDoesNotMoveObservationOrFloor() = runBlocking {
        val store = MessageStore(database, clock = { 100 })
        store.ingest(
            incoming(localId = "live", sentAtEpochMs = 100, sentTimeSource = MessageTimeSource.LOCAL),
        )

        val replay = store.ingest(
            incoming(localId = "live", sentAtEpochMs = 200, sentTimeSource = MessageTimeSource.LOCAL),
        )

        assertFalse(replay.inserted)
        assertEquals(100L, store.messages(ACCOUNT).single().reconciliationObservedAtMs)
        assertEquals(100L, requireNotNull(database.accountDao().reconciliationState(ACCOUNT)).wallFloorMs)
    }

    @Test
    fun missingReconciliationStateFailsClosedWithoutSynthesizingIt() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM account_reconciliation_state WHERE accountId = ?",
            arrayOf<Any>(ACCOUNT),
        )

        val store = MessageStore(database, clock = { 100 })
        store.ingest(incoming(localId = "live"))

        assertNull(store.messages(ACCOUNT).single().reconciliationObservedAtMs)
        assertNull(database.accountDao().reconciliationState(ACCOUNT))
    }

    @Test
    fun onlyUnpositionedIdentitylessInboundLocalInsertAdvancesReconciliationFloor() = runBlocking {
        val store = MessageStore(database, clock = { 100 })
        listOf(
            incoming(localId = "aliased", aliases = listOf(alias("wire"))),
            incoming(localId = "mam", sentAtEpochMs = 100, sentTimeSource = MessageTimeSource.MAM),
            incoming(localId = "carbon", sentAtEpochMs = 100, sentTimeSource = MessageTimeSource.CARBON),
            incoming(localId = "negative", sentAtEpochMs = -1, sentTimeSource = MessageTimeSource.LOCAL),
            incoming(localId = "outbound", direction = MessageDirection.OUTBOUND),
            incoming(localId = "positioned", archiveOrdinal = 0),
        ).forEach { store.ingest(it) }

        assertTrue(store.messages(ACCOUNT).all { it.reconciliationObservedAtMs == null })
        assertEquals(0L, requireNotNull(database.accountDao().reconciliationState(ACCOUNT)).wallFloorMs)
    }

    @Test
    fun failedIdentitylessLiveInsertRollsBackObservationAndFloor() = runBlocking {
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.AFTER_MESSAGE) error("stop after message")
        }

        assertSuspendFailure<IllegalStateException> {
            faulting.ingest(
                incoming(localId = "rolled-back", sentAtEpochMs = 100, sentTimeSource = MessageTimeSource.LOCAL),
            )
        }

        assertTrue(faulting.messages(ACCOUNT).isEmpty())
        assertEquals(0L, requireNotNull(database.accountDao().reconciliationState(ACCOUNT)).wallFloorMs)
    }

    @Test
    fun boundedReconciliationCandidateQueryScopesSourcesBoundariesAndOrder() = runBlocking {
        addAccount(OTHER_ACCOUNT)
        val store = MessageStore(database, clock = { 0 })
        val expected = mutableListOf<String>()
        suspend fun candidate(message: IncomingMessage) {
            store.ingest(message)
            expected += message.localMessageId
        }

        store.ingest(incoming(localId = "outside-low", sentAtEpochMs = 99, sentTimeSource = MessageTimeSource.LOCAL))
        candidate(incoming(localId = "local-lower", sentAtEpochMs = 100, sentTimeSource = MessageTimeSource.LOCAL))
        store.ingest(incoming(localId = "wrong-peer", peerJid = "other@example.org", sentAtEpochMs = 110, sentTimeSource = MessageTimeSource.LOCAL))
        store.ingest(incoming(accountId = OTHER_ACCOUNT, localId = "wrong-account", sentAtEpochMs = 120, sentTimeSource = MessageTimeSource.LOCAL))
        store.ingest(incoming(localId = "outbound", direction = MessageDirection.OUTBOUND, sentAtEpochMs = 130, sentTimeSource = MessageTimeSource.LOCAL))
        store.ingest(incoming(localId = "carbon", sentAtEpochMs = 140, sentTimeSource = MessageTimeSource.CARBON))
        candidate(incoming(localId = "mam-lower", sentAtEpochMs = 100, sentTimeSource = MessageTimeSource.MAM))
        candidate(incoming(localId = "local-upper", sentAtEpochMs = 200, sentTimeSource = MessageTimeSource.LOCAL))
        candidate(incoming(localId = "mam-upper", sentAtEpochMs = 200, sentTimeSource = MessageTimeSource.MAM))
        candidate(incoming(localId = "local-observed-time", sentAtEpochMs = 1, sentTimeSource = MessageTimeSource.LOCAL))
        repeat(IDENTITYLESS_RECONCILIATION_CANDIDATE_CAP) { index ->
            candidate(incoming(localId = "candidate-$index", sentAtEpochMs = 150, sentTimeSource = MessageTimeSource.LOCAL))
        }
        store.ingest(incoming(localId = "outside-high", sentAtEpochMs = 201, sentTimeSource = MessageTimeSource.LOCAL))
        store.ingest(incoming(localId = "local-sent-in-window", sentAtEpochMs = 150, sentTimeSource = MessageTimeSource.LOCAL))
        store.ingest(incoming(localId = "mam-outside", sentAtEpochMs = 201, sentTimeSource = MessageTimeSource.MAM))

        val actual = database.messageDao().identitylessReconciliationCandidates(
            ACCOUNT,
            PEER,
            100,
            200,
            IDENTITYLESS_RECONCILIATION_CANDIDATE_CAP + 1,
        ).map(MessageEntity::localMessageId)

        assertEquals(expected.take(IDENTITYLESS_RECONCILIATION_CANDIDATE_CAP + 1), actual)
    }

    @Test
    fun batchedReconciliationMetadataScopesCompleteOwnershipAndEmptyLists() = runBlocking {
        addAccount(OTHER_ACCOUNT)
        val store = MessageStore(database)
        listOf("live", "mam", "other").forEach { store.ingest(incoming(localId = it)) }
        listOf("live", "mam", "other").forEach {
            store.ingest(incoming(accountId = OTHER_ACCOUNT, localId = it))
        }
        val dao = database.messageDao()
        val key = archiveKey(ACCOUNT)
        val trusted = TrustedIdentityAliasEntity(
            ACCOUNT,
            IdentityAliasKind.MAM_RESULT,
            key.aliasAuthority(),
            "trusted",
            "mam",
            IdentityAliasStatus.TRUSTED,
        )
        val quarantined = trusted.copy(value = "quarantined", messageId = "live", status = IdentityAliasStatus.QUARANTINED)
        val otherAccountAlias = trusted.copy(accountId = OTHER_ACCOUNT, value = "other-account")
        dao.insertTrustedAlias(trusted)
        dao.insertTrustedAlias(quarantined)
        dao.insertTrustedAlias(otherAccountAlias)
        val positions = listOf(
            ArchiveMessagePositionEntity(ACCOUNT, key.archiveAuthority, key.scope, 7, "mam"),
            ArchiveMessagePositionEntity(ACCOUNT, "room@example.org", "ROOM", 8, "mam"),
        )
        positions.forEach { dao.insertArchivePosition(it) }
        dao.insertArchivePosition(positions.first().copy(accountId = OTHER_ACCOUNT, archiveOrdinal = 9))
        val conflicts = listOf(
            IdentityConflictEntity(ACCOUNT, IdentityAliasKind.MAM_RESULT, key.aliasAuthority(), "first", "live", "other", 2),
            IdentityConflictEntity(ACCOUNT, IdentityAliasKind.MAM_RESULT, key.aliasAuthority(), "second", "other", "mam", 3),
        )
        conflicts.forEach { dao.insertConflict(it) }
        dao.insertConflict(conflicts.first().copy(accountId = OTHER_ACCOUNT, value = "other-account"))
        val ids = listOf("live", "mam")

        assertEquals(listOf(trusted), dao.trustedAliasesForMessages(ACCOUNT, ids))
        assertEquals(positions, dao.archivePositionsForMessages(ACCOUNT, ids))
        assertEquals(conflicts.toSet(), dao.conflictsForMessages(ACCOUNT, ids).toSet())
        assertTrue(dao.trustedAliasesForMessages(ACCOUNT, emptyList()).isEmpty())
        assertTrue(dao.archivePositionsForMessages(ACCOUNT, emptyList()).isEmpty())
        assertTrue(dao.conflictsForMessages(ACCOUNT, emptyList()).isEmpty())
        assertTrue(dao.outboxesForMessages(ACCOUNT, emptyList()).isEmpty())
    }

    @Test
    fun closedArchivePageReconcilesOneIdentitylessLiveMamPair() = runBlocking {
        val now = 31_001L
        val store = MessageStore(database, clock = { now })
        store.ingest(
            incoming(localId = "live", body = "same", sentAtEpochMs = 1_000, sentTimeSource = MessageTimeSource.LOCAL),
        )

        val result = store.applyArchivePage(
            archivePage(
                archiveKey(ACCOUNT),
                ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(identitylessArchived("result", "mam", 1_000)),
            ),
        )

        assertEquals(0, result.inserted)
        assertTrue(result.insertedInbound.isEmpty())
        assertEquals(listOf("live"), store.messages(ACCOUNT).map(MessageEntity::localMessageId))
        assertEquals(listOf(0L), store.archivePositions(ACCOUNT, "live").map { it.archiveOrdinal })
        assertEquals(setOf("live"), store.aliases(ACCOUNT).mapNotNull { it.messageId }.toSet())
        assertEquals(now, requireNotNull(database.accountDao().reconciliationState(ACCOUNT)).wallFloorMs)
    }

    @Test
    fun coherentLaterFinSettlesPersistedBoundaryCandidate() = runBlocking {
        var now = 31_001L
        val store = MessageStore(database, clock = { now })
        store.ingest(
            incoming(localId = "live", body = "same", sentAtEpochMs = 1_000, sentTimeSource = MessageTimeSource.LOCAL),
        )
        val key = archiveKey(ACCOUNT)
        store.applyArchivePage(
            archivePage(
                key,
                ArchiveDirection.BOOTSTRAP,
                complete = false,
                hasEarlier = false,
                messages = listOf(identitylessArchived("result", "mam", 1_000)),
            ),
        )
        assertEquals(2, store.messages(ACCOUNT).size)

        now = 31_002
        store.applyArchivePage(
            archivePage(
                key,
                ArchiveDirection.AFTER,
                boundaryId = "result",
                complete = true,
                hasEarlier = false,
            ),
        )

        assertEquals(listOf("live"), store.messages(ACCOUNT).map(MessageEntity::localMessageId))
    }

    @Test
    fun failedFallbackMergeRollsBackPageAndFloor() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(localId = "live", body = "same", sentAtEpochMs = 1_000, sentTimeSource = MessageTimeSource.LOCAL),
        )
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) error("stop during merge")
        }

        assertSuspendFailure<IllegalStateException> {
            faulting.applyArchivePage(
                archivePage(
                    archiveKey(ACCOUNT),
                    ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(identitylessArchived("result", "mam", 1_000)),
                ),
            )
        }

        assertEquals(listOf("live"), store.messages(ACCOUNT).map(MessageEntity::localMessageId))
        assertTrue(store.aliases(ACCOUNT).isEmpty())
        assertNull(store.archiveCursor(archiveKey(ACCOUNT)))
        assertEquals(1_000L, requireNotNull(database.accountDao().reconciliationState(ACCOUNT)).wallFloorMs)
    }

    @Test
    fun denseWindowSkipsFallbackWithoutRejectingArchivePage() = runBlocking {
        var store = MessageStore(database, clock = { 31_001 })
        store.ingest(
            incoming(localId = "matching-live", body = "same", sentAtEpochMs = 1_000, sentTimeSource = MessageTimeSource.LOCAL),
        )
        repeat(IDENTITYLESS_RECONCILIATION_CANDIDATE_CAP) { index ->
            store.ingest(
                incoming(
                    localId = "unrelated-$index",
                    body = "unrelated-$index",
                    sentAtEpochMs = 1_000,
                    sentTimeSource = MessageTimeSource.LOCAL,
                ),
            )
        }
        val page = archivePage(
            archiveKey(ACCOUNT),
            ArchiveDirection.BOOTSTRAP,
            complete = true,
            hasEarlier = false,
            messages = listOf(identitylessArchived("result", "mam", 1_000)),
        )

        val result = store.applyArchivePage(page)

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(IDENTITYLESS_RECONCILIATION_CANDIDATE_CAP + 2, store.messages(ACCOUNT).size)
        assertEquals(31_001L, requireNotNull(database.accountDao().reconciliationState(ACCOUNT)).wallFloorMs)
        assertEquals("result", store.archiveCursor(archiveKey(ACCOUNT))?.newestId)

        store = reopenStore()
        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, store.applyArchivePage(page).status)
        assertEquals(IDENTITYLESS_RECONCILIATION_CANDIDATE_CAP + 2, store.messages(ACCOUNT).size)
    }

    @Test
    fun repeatedIdentitylessArchiveCopiesRemainSeparate() = runBlocking {
        val store = MessageStore(database, clock = { 31_002 })
        store.ingest(
            incoming(localId = "live", body = "same", sentAtEpochMs = 1_000, sentTimeSource = MessageTimeSource.LOCAL),
        )

        val result = store.applyArchivePage(
            archivePage(
                archiveKey(ACCOUNT),
                ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    identitylessArchived("result-1", "mam-1", 1_000),
                    identitylessArchived("result-2", "mam-2", 1_001),
                ),
            ),
        )

        assertEquals(2, result.inserted)
        assertEquals(setOf("live", "mam-1", "mam-2"), store.messages(ACCOUNT).map { it.localMessageId }.toSet())
    }

    @Test
    fun persistedBoundaryAndCurrentInclusiveDuplicateRemainSeparate() = runBlocking {
        var now = 30_001L
        val store = MessageStore(database, clock = { now })
        store.ingest(
            incoming(localId = "live", body = "same", sentAtEpochMs = 30_000, sentTimeSource = MessageTimeSource.LOCAL),
        )
        val key = archiveKey(ACCOUNT)
        store.applyArchivePage(
            archivePage(
                key,
                ArchiveDirection.BOOTSTRAP,
                complete = false,
                hasEarlier = false,
                messages = listOf(identitylessArchived("old-result", "old-mam", 0)),
            ),
        )

        now = 90_001
        store.applyArchivePage(
            archivePage(
                key,
                ArchiveDirection.AFTER,
                boundaryId = "old-result",
                complete = true,
                hasEarlier = false,
                messages = listOf(identitylessArchived("new-result", "new-mam", 60_000)),
            ),
        )

        assertEquals(
            setOf("live", "old-mam", "new-mam"),
            store.messages(ACCOUNT).map(MessageEntity::localMessageId).toSet(),
        )
    }

    @Test
    fun firstIngestIsInsertedAndAliasReplayIsNot() = runBlocking {
        val store = MessageStore(database)
        val origin = alias("wire-1")
        val first = store.ingest(incoming(localId = "live-1", aliases = listOf(origin)))
        assertTrue(first.inserted)
        assertFalse(first.identityConflict)
        val replay = store.ingest(incoming(localId = "mam-1", aliases = listOf(origin)))
        assertFalse(replay.inserted)
        assertEquals(first.messageId, replay.messageId)
        assertEquals(1, store.messages(ACCOUNT).size)
    }

    @Test
    fun attachmentMetadataEnrichesCompatibleCopiesInEitherOrder() = runBlocking {
        val store = MessageStore(database)
        listOf(false, true).forEachIndexed { index, richFirst ->
            val suffix = if (richFirst) "rich-first" else "sparse-first"
            val url = "https://example.org/$suffix.jpg"
            val identity = TrustedIdentityAlias(
                IdentityAliasKind.ORIGIN_ID,
                MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                "origin-$suffix",
            )
            fun attachment(localId: String, rich: Boolean) = incoming(
                localId = localId,
                sender = SELF,
                direction = MessageDirection.OUTBOUND,
                body = "attachment-$suffix",
                aliases = listOf(identity),
            ).copy(
                attachmentUrl = url,
                attachmentName = if (rich) "$suffix.jpg" else null,
                attachmentMime = if (rich) "image/jpeg" else null,
                attachmentSize = if (rich) 1_024L + index else null,
            )
            val sparse = attachment("sparse-$suffix", false)
            val rich = attachment("rich-$suffix", true)
            val first = if (richFirst) rich else sparse
            val second = if (richFirst) sparse else rich

            store.ingest(first)
            val result = store.ingest(second)

            assertFalse(result.identityConflict)
            val saved = store.messages(ACCOUNT).single { it.attachmentUrl == url }
            assertEquals("$suffix.jpg", saved.attachmentName)
            assertEquals("image/jpeg", saved.attachmentMime)
            assertEquals(1_024L + index, saved.attachmentSize)
        }
        assertTrue(store.conflicts(ACCOUNT).isEmpty())
    }

    @Test
    fun conflictingAttachmentMetadataAndUrlsQuarantineIdentity() = runBlocking {
        data class Variant(
            val name: String,
            val change: (IncomingMessage) -> IncomingMessage,
        )
        val variants = listOf(
            Variant("url") { it.copy(attachmentUrl = "https://example.org/other.jpg") },
            Variant("name") { it.copy(attachmentName = "other.jpg") },
            Variant("mime") { it.copy(attachmentMime = "image/png") },
            Variant("size") { it.copy(attachmentSize = 2_048L) },
        )
        val store = MessageStore(database)
        variants.forEach { variant ->
            val identity = TrustedIdentityAlias(
                IdentityAliasKind.ORIGIN_ID,
                MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                "origin-${variant.name}",
            )
            val first = incoming(
                localId = "first-${variant.name}",
                sender = SELF,
                direction = MessageDirection.OUTBOUND,
                body = "attachment-${variant.name}",
                aliases = listOf(identity),
            ).copy(
                attachmentUrl = "https://example.org/original.jpg",
                attachmentName = "original.jpg",
                attachmentMime = "image/jpeg",
                attachmentSize = 1_024L,
            )
            val second = variant.change(first.copy(localMessageId = "second-${variant.name}"))

            store.ingest(first)
            val result = store.ingest(second)

            assertTrue(result.identityConflict)
            assertEquals(
                setOf("first-${variant.name}", "second-${variant.name}"),
                store.messages(ACCOUNT)
                    .filter { it.body == "attachment-${variant.name}" }
                    .map(MessageEntity::localMessageId)
                    .toSet(),
            )
            val quarantined = store.aliases(ACCOUNT).single { it.value == identity.value }
            assertEquals(IdentityAliasStatus.QUARANTINED, quarantined.status)
            assertNull(quarantined.messageId)
        }
        assertEquals(variants.size, store.conflicts(ACCOUNT).size)
    }

    @Test
    fun sparseBridgePreservesAttachmentMetadataFromRichLoser() = runBlocking {
        val firstAlias = TrustedIdentityAlias(
            IdentityAliasKind.ORIGIN_ID,
            MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
            "origin-sparse",
        )
        val secondAlias = TrustedIdentityAlias(
            IdentityAliasKind.STANZA_ID,
            SELF,
            "stanza-rich",
        )
        fun attachment(
            localId: String,
            aliases: List<TrustedIdentityAlias>,
            rich: Boolean,
        ) = incoming(
            localId = localId,
            sender = SELF,
            direction = MessageDirection.OUTBOUND,
            body = "bridged attachment",
            aliases = aliases,
        ).copy(
            attachmentUrl = "https://example.org/bridged.jpg",
            attachmentName = if (rich) "bridged.jpg" else null,
            attachmentMime = if (rich) "image/jpeg" else null,
            attachmentSize = if (rich) 1_024L else null,
        )
        val store = MessageStore(database)
        store.ingest(attachment("sparse", listOf(firstAlias), false))
        store.ingest(attachment("rich", listOf(secondAlias), true))

        val result = store.ingest(attachment("bridge", listOf(firstAlias, secondAlias), false))

        assertEquals("sparse", result.messageId)
        assertEquals(1, result.mergedRows)
        assertFalse(result.identityConflict)
        val saved = store.messages(ACCOUNT).single()
        assertEquals("bridged.jpg", saved.attachmentName)
        assertEquals("image/jpeg", saved.attachmentMime)
        assertEquals(1_024L, saved.attachmentSize)
    }

    @Test
    fun mergedLiveDeliveryAuthorityPreventsDuplicateReceipt() = runBlocking {
        val archiveAlias = TrustedIdentityAlias(
            IdentityAliasKind.ORIGIN_ID,
            MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
            "receipt-origin",
        )
        val liveAlias = TrustedIdentityAlias(
            IdentityAliasKind.STANZA_ID,
            SELF,
            "receipt-stanza",
        )
        fun message(
            localId: String,
            source: MessageTimeSource,
            aliases: List<TrustedIdentityAlias>,
        ) = incoming(
            localId = localId,
            body = "receipt body",
            sentAtEpochMs = 1_000L,
            sentTimeSource = source,
            aliases = aliases,
        )
        val store = MessageStore(database)

        assertFalse(store.ingest(message("archive", MessageTimeSource.MAM, listOf(archiveAlias))).firstLiveDelivery)
        assertTrue(store.ingest(message("live", MessageTimeSource.LOCAL, listOf(liveAlias))).firstLiveDelivery)
        assertEquals(
            1,
            store.ingest(
                message("bridge", MessageTimeSource.MAM, listOf(archiveAlias, liveAlias)),
            ).mergedRows,
        )

        assertFalse(
            store.ingest(
                message("replay", MessageTimeSource.LOCAL, listOf(archiveAlias)),
            ).firstLiveDelivery,
        )
    }

    @Test
    fun sparseBridgeDoesNotMergeConflictingAttachmentMetadata() = runBlocking {
        val firstAlias = TrustedIdentityAlias(
            IdentityAliasKind.ORIGIN_ID,
            MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
            "origin-first",
        )
        val secondAlias = TrustedIdentityAlias(
            IdentityAliasKind.STANZA_ID,
            SELF,
            "stanza-second",
        )
        fun attachment(
            localId: String,
            aliases: List<TrustedIdentityAlias>,
            name: String?,
        ) = incoming(
            localId = localId,
            sender = SELF,
            direction = MessageDirection.OUTBOUND,
            body = "bridged attachment",
            aliases = aliases,
        ).copy(
            attachmentUrl = "https://example.org/bridged.jpg",
            attachmentName = name,
        )
        val store = MessageStore(database)
        store.ingest(attachment("first", listOf(firstAlias), "first.jpg"))
        store.ingest(attachment("second", listOf(secondAlias), "second.jpg"))

        val result = store.ingest(attachment("bridge", listOf(firstAlias, secondAlias), null))

        assertTrue(result.identityConflict)
        assertEquals(setOf("first", "second"), store.messages(ACCOUNT).map { it.localMessageId }.toSet())
        val quarantined = store.aliases(ACCOUNT).single { it.value == secondAlias.value }
        assertEquals(IdentityAliasStatus.QUARANTINED, quarantined.status)
        assertNull(quarantined.messageId)
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
    fun archiveEchoNormalizesReplyFallbackWithoutPoisoningCatchUp() = runBlocking {
        val store = MessageStore(database)
        val intent = OutboundIntent(
            accountId = ACCOUNT,
            operationId = "reply-operation",
            localMessageId = "reply-local",
            originId = "reply-origin",
            peerJid = PEER,
            senderJid = SELF,
            messageKind = MessageKind.CHAT,
            threadId = null,
            parentThreadId = null,
            body = "answer",
            replyToId = "reply-target",
            replyToJid = SELF,
            replyFallbackBody = "quoted body\n",
        )
        store.compose(intent)
        val stored = store.messages(ACCOUNT).single()

        val result = store.applyArchivePage(
            archivePage(
                key = archiveKey(ACCOUNT),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "reply-result",
                        localId = "reply-archive-copy",
                        body = intent.body,
                        alias = TrustedIdentityAlias(
                            IdentityAliasKind.ORIGIN_ID,
                            MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                            intent.originId,
                        ),
                        direction = MessageDirection.OUTBOUND,
                        sender = SELF,
                        threadId = stored.threadId,
                        parentThreadId = stored.parentThreadId,
                        replyToId = intent.replyToId,
                        replyToJid = intent.replyToJid,
                        replyFallbackBody = "quoted body",
                    ),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals("reply-result", result.cursor.newestId)
        assertEquals(listOf(intent.localMessageId), store.messages(ACCOUNT).map(MessageEntity::localMessageId))
        assertEquals("quoted body\n", store.messages(ACCOUNT).single().replyFallbackBody)
        assertEquals(OutboxStatus.CONFIRMED, store.outbox(ACCOUNT, intent.operationId)?.status)
        assertTrue(store.conflicts(ACCOUNT).isEmpty())
    }

    @Test
    fun conflictingArchiveIdentityDoesNotStarveLaterMessages() = runBlocking {
        val store = MessageStore(database)
        val reused = stanzaAlias("reused")
        store.ingest(incoming(localId = "existing", body = "existing", aliases = listOf(reused)))

        val result = store.applyArchivePage(
            archivePage(
                key = archiveKey(ACCOUNT),
                direction = ArchiveDirection.BOOTSTRAP,
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("r1", "conflicting", "different", reused),
                    archived("r2", "later", "later body", stanzaAlias("later")),
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(2, result.ingested)
        assertEquals("r2", result.cursor.newestId)
        assertEquals(1L, result.cursor.newestOrdinal)
        assertEquals(
            setOf("existing", "conflicting", "later"),
            store.messages(ACCOUNT).map(MessageEntity::localMessageId).toSet(),
        )
        assertEquals(0L, store.archivePositions(ACCOUNT, "conflicting").single().archiveOrdinal)
        assertEquals(1L, store.archivePositions(ACCOUNT, "later").single().archiveOrdinal)
        assertEquals(IdentityAliasStatus.QUARANTINED, store.aliases(ACCOUNT).single { it.value == "reused" }.status)
        assertEquals(1, store.conflicts(ACCOUNT).size)
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
    fun migratedBeforeRejectsPageInternalIdentityRepeatBeforePrefixRebase() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val bridge = stanzaAlias("page-internal-bridge")
        val target = archived("internal-target-result", "internal-target", "same body")
        val tail = archived("internal-tail-result", "internal-tail", "tail")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(target, tail),
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
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = true,
                    messages = listOf(tail),
                ),
            ).status,
        )
        val positionsBefore = store.archivePositions(ACCOUNT, "internal-target")
        val messagesBefore = store.messages(ACCOUNT)

        store = reopenStore()
        val repeatedTarget = ArchivedIncomingMessage(
            resultId = "internal-repeated-result",
            message = incoming(
                localId = "internal-repeated",
                body = "same body",
                aliases = listOf(bridge),
            ),
        )
        val before = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "internal-tail-result",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        "internal-target-new-result",
                        "internal-target",
                        "same body",
                        bridge,
                    ),
                    repeatedTarget,
                    tail,
                ),
            ),
        )

        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, before.status)
        assertEquals("Archive page repeats one logical message", before.cursor.retryableError)
        assertEquals(positionsBefore, store.archivePositions(ACCOUNT, "internal-target"))
        assertEquals(messagesBefore, store.messages(ACCOUNT))
        store = reopenStore()
        assertEquals(positionsBefore, store.archivePositions(ACCOUNT, "internal-target"))
        assertEquals(messagesBefore, store.messages(ACCOUNT))
    }

    @Test
    fun migratedBeforeRejectsUnseededPageInternalIdentityRepeat() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val tail = archived("unseeded-tail-result", "unseeded-tail-message", "tail")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(tail),
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
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = true,
                    messages = listOf(tail),
                ),
            ).status,
        )
        val messagesBefore = store.messages(ACCOUNT)
        val aliasesBefore = store.aliases(ACCOUNT)
        val tailPositionsBefore = store.archivePositions(ACCOUNT, "unseeded-tail-message")
        val bridge = stanzaAlias("unseeded-page-bridge")

        val before = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "unseeded-tail-result",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived("unseeded-first-result", "unseeded-first", "same body", bridge),
                    archived("unseeded-second-result", "unseeded-second", "same body", bridge),
                    tail,
                ),
            ),
        )

        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, before.status)
        assertEquals("Archive page repeats one logical message", before.cursor.retryableError)
        assertEquals(messagesBefore, store.messages(ACCOUNT))
        assertEquals(aliasesBefore, store.aliases(ACCOUNT))
        assertEquals(tailPositionsBefore, store.archivePositions(ACCOUNT, "unseeded-tail-message"))
        store = reopenStore()
        assertEquals(messagesBefore, store.messages(ACCOUNT))
        assertEquals(aliasesBefore, store.aliases(ACCOUNT))
        assertEquals(tailPositionsBefore, store.archivePositions(ACCOUNT, "unseeded-tail-message"))
    }

    @Test
    fun migratedBeforeRejectsIdentityRepeatConnectedByStoredMessageSeed() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val positionedAlias = stanzaAlias("seed-positioned")
        val firstUnpositionedAlias = stanzaAlias("seed-unpositioned-first")
        val secondUnpositionedAlias = stanzaAlias("seed-unpositioned-second")
        val positioned = archived(
            "seed-positioned-result",
            "seed-positioned-message",
            "same body",
            positionedAlias,
        )
        val tail = archived("seed-tail-result", "seed-tail-message", "tail")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(positioned, tail),
                ),
            ).status,
        )
        store.ingest(
            incoming(
                localId = "seed-unpositioned-message",
                body = "same body",
                aliases = listOf(firstUnpositionedAlias, secondUnpositionedAlias),
            ),
        )
        database.messageDao().upsertArchiveCursor(
            key.emptyCursor().copy(
                hasEarlier = true,
                retryableError = "Archive cursor reset during migration",
            ),
        )
        store = reopenStore()
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = true,
                    messages = listOf(tail),
                ),
            ).status,
        )
        val messagesBefore = store.messages(ACCOUNT)
        val aliasesBefore = store.aliases(ACCOUNT)
        val positionedPositionsBefore = store.archivePositions(ACCOUNT, "seed-positioned-message")
        val unpositionedPositionsBefore = store.archivePositions(ACCOUNT, "seed-unpositioned-message")
        val tailPositionsBefore = store.archivePositions(ACCOUNT, "seed-tail-message")

        val before = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "seed-tail-result",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    ArchivedIncomingMessage(
                        resultId = "seed-bridge-result",
                        message = incoming(
                            localId = "seed-bridge-message",
                            body = "same body",
                            aliases = listOf(positionedAlias, firstUnpositionedAlias),
                        ),
                    ),
                    ArchivedIncomingMessage(
                        resultId = "seed-repeat-result",
                        message = incoming(
                            localId = "seed-repeat-message",
                            body = "same body",
                            aliases = listOf(secondUnpositionedAlias),
                        ),
                    ),
                    tail,
                ),
            ),
        )

        assertEquals(ArchivePageStatus.RETRYABLE_ERROR, before.status)
        assertEquals("Archive page repeats one logical message", before.cursor.retryableError)
        assertEquals(messagesBefore, store.messages(ACCOUNT))
        assertEquals(aliasesBefore, store.aliases(ACCOUNT))
        assertEquals(positionedPositionsBefore, store.archivePositions(ACCOUNT, "seed-positioned-message"))
        assertEquals(unpositionedPositionsBefore, store.archivePositions(ACCOUNT, "seed-unpositioned-message"))
        assertEquals(tailPositionsBefore, store.archivePositions(ACCOUNT, "seed-tail-message"))
        store = reopenStore()
        assertEquals(messagesBefore, store.messages(ACCOUNT))
        assertEquals(aliasesBefore, store.aliases(ACCOUNT))
        assertEquals(positionedPositionsBefore, store.archivePositions(ACCOUNT, "seed-positioned-message"))
        assertEquals(unpositionedPositionsBefore, store.archivePositions(ACCOUNT, "seed-unpositioned-message"))
        assertEquals(tailPositionsBefore, store.archivePositions(ACCOUNT, "seed-tail-message"))
    }

    @Test
    fun migratedBeforePreservesStoredThreadParentDuringLocalIdPreflight() = runBlocking {
        var store = MessageStore(database)
        val key = archiveKey(ACCOUNT)
        val threaded = archived(
            resultId = "lineage-threaded-result",
            localId = "lineage-threaded-message",
            body = "threaded",
            threadId = "lineage-child",
            parentThreadId = "lineage-root",
        )
        val tail = archived("lineage-tail-result", "lineage-tail-message", "tail")
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = false,
                    messages = listOf(threaded, tail),
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
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(
                    key = key,
                    direction = ArchiveDirection.BOOTSTRAP,
                    complete = true,
                    hasEarlier = true,
                    messages = listOf(tail),
                ),
            ).status,
        )

        val before = store.applyArchivePage(
            archivePage(
                key = key,
                direction = ArchiveDirection.BEFORE,
                boundaryId = "lineage-tail-result",
                complete = true,
                hasEarlier = false,
                messages = listOf(
                    archived(
                        resultId = "lineage-new-result",
                        localId = "lineage-threaded-message",
                        body = "threaded",
                        threadId = "lineage-child",
                        parentThreadId = null,
                    ),
                    tail,
                ),
            ),
        )

        assertEquals(ArchivePageStatus.APPLIED, before.status)
        assertEquals(0L, store.archivePositions(ACCOUNT, "lineage-threaded-message").single().archiveOrdinal)
        assertEquals(1L, store.archivePositions(ACCOUNT, "lineage-tail-message").single().archiveOrdinal)
        val stored = store.messages(ACCOUNT).single { it.localMessageId == "lineage-threaded-message" }
        assertEquals("lineage-root", stored.parentThreadId)
        assertEquals(2, store.messages(ACCOUNT).size)
        store = reopenStore()
        assertEquals(before.cursor, store.archiveCursor(key))
        assertEquals(0L, store.archivePositions(ACCOUNT, "lineage-threaded-message").single().archiveOrdinal)
        assertEquals("lineage-root", store.messages(ACCOUNT).single {
            it.localMessageId == "lineage-threaded-message"
        }.parentThreadId)
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
        store.ingest(
            incoming(
                localId = "missing-parent-live",
                body = "live omission",
                threadId = "child",
            ),
        )
        assertEquals("root", store.messages(ACCOUNT).last().parentThreadId)
        assertEquals(7, store.messages(ACCOUNT).size)
        assertSuspendFailure<IllegalArgumentException> {
            store.compose(
                outbound("missing-parent").copy(
                    threadId = "child",
                    parentThreadId = null,
                ),
            )
        }
        assertNull(store.outbox(ACCOUNT, "operation-missing-parent"))
        assertEquals(7, store.messages(ACCOUNT).size)
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
    fun roomMamResultAndPersistedStanzaIdsNeverBecomeReplyTargetsWithoutRoomAuthority() = runBlocking {
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
        assertEquals(listOf(null, null), timeline.map { it.replyReferenceId })
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
        database.accountDao().saveBound(
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
        replyToId: String? = null,
        replyToJid: String? = null,
        replyFallbackBody: String? = null,
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
            replyToId = replyToId,
            replyToJid = replyToJid,
            replyFallbackBody = replyFallbackBody,
        ),
    )

    private fun identitylessArchived(resultId: String, localId: String, sentAtMs: Long) =
        archived(resultId, localId, "same").let { archived ->
            archived.copy(
                message = requireNotNull(archived.message).copy(
                    sentAtEpochMs = sentAtMs,
                    sentTimeSource = MessageTimeSource.MAM,
                ),
            )
        }

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
        sentAtEpochMs: Long? = null,
        sentTimeSource: MessageTimeSource? = null,
        aliases: List<TrustedIdentityAlias> = emptyList(),
        threadId: String? = null,
        parentThreadId: String? = null,
        replyToId: String? = null,
        replyToJid: String? = null,
        replyFallbackBody: String? = null,
        replaceId: String? = null,
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
        sentAtEpochMs = sentAtEpochMs,
        sentTimeSource = sentTimeSource,
        aliases = aliases,
        replyToId = replyToId,
        replyToJid = replyToJid,
        replyFallbackBody = replyFallbackBody,
        replaceId = replaceId,
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
