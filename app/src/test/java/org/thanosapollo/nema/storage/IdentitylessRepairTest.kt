package org.thanosapollo.nema.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentitylessRepairTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "identityless-repair-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().saveBound(
            AccountEntity(ACCOUNT, "account@example.org", ACCOUNT, null, "example.org", null, null),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun pendingRepairMergesUniquePairsAndCompletesAtomicReceipt() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("unique-live", "unique", MessageTimeSource.LOCAL))
        store.ingest(incoming("ambiguous-live", "ambiguous", MessageTimeSource.LOCAL))
        store.applyArchivePage(
            page(
                archived("unique-result", "unique-mam", "unique", 1_500),
                archived("ambiguous-result-1", "ambiguous-mam-1", "ambiguous"),
                archived("ambiguous-result-2", "ambiguous-mam-2", "ambiguous"),
            ),
        )
        database.messageDao().insertTrustedAlias(
            TrustedIdentityAliasEntity(
                ACCOUNT,
                IdentityAliasKind.STANZA_ID,
                "untrusted.example.org",
                "quarantined",
                "unique-live",
                IdentityAliasStatus.QUARANTINED,
            ),
        )
        val cursorBefore = requireNotNull(store.archiveCursor(ArchiveCursorKey(ACCOUNT, "account@example.org", "ACCOUNT")))

        val state = requireNotNull(store.repairIdentitylessDuplicates(ACCOUNT))

        assertEquals(ReconciliationRepairStatus.COMPLETE, state.status)
        assertEquals(5L, state.beforeCount)
        assertEquals(4L, state.afterCount)
        assertEquals(1L, state.matchedCount)
        assertEquals(1L, state.skippedCount)
        assertEquals(
            setOf("unique-live", "ambiguous-live", "ambiguous-mam-1", "ambiguous-mam-2"),
            store.messages(ACCOUNT).map(MessageEntity::localMessageId).toSet(),
        )
        val winner = requireNotNull(database.messageDao().message(ACCOUNT, "unique-live"))
        assertEquals(1L, winner.localSequence)
        assertEquals(1_500L, winner.sentAtEpochMs)
        assertEquals(MessageTimeSource.MAM, winner.sentTimeSource)
        assertTrue(winner.liveDeliveryObserved)
        assertEquals(
            listOf(0L),
            store.archivePositions(ACCOUNT, "unique-live").map(ArchiveMessagePositionEntity::archiveOrdinal),
        )
        assertEquals(
            "unique-live",
            store.aliases(ACCOUNT).single {
                it.kind == IdentityAliasKind.MAM_RESULT &&
                    it.authority == ArchiveCursorKey(ACCOUNT, "account@example.org", "ACCOUNT").aliasAuthority() &&
                    it.value == "unique-result" &&
                    it.status == IdentityAliasStatus.TRUSTED
            }.messageId,
        )
        assertEquals(
            cursorBefore,
            store.archiveCursor(ArchiveCursorKey(ACCOUNT, "account@example.org", "ACCOUNT")),
        )
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
            assertTrue(!it.moveToFirst())
        }
        database.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        assertEquals(state, store.repairIdentitylessDuplicates(ACCOUNT))
    }

    @Test
    fun repairReparentsCorrectionsAndReactions() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("live", "same", MessageTimeSource.LOCAL))
        store.applyArchivePage(page(archived("result", "mam", "same")))
        store.ingest(incoming("correction", "edited", MessageTimeSource.LOCAL))
        val dao = database.messageDao()
        val correction = requireNotNull(dao.message(ACCOUNT, "correction"))
        dao.updateMessage(correction.copy(correctionTargetMessageId = "mam"))
        assertEquals(
            ReactionMutationOutcome.WRITTEN,
            dao.writeReactionFullSet(
                MessageReactionEntity(
                    ACCOUNT, PEER, PEER, reactionTargetKey("mam", "wire"), "mam", "wire", "😀", 2_000,
                ),
            ),
        )

        store.repairIdentitylessDuplicates(ACCOUNT)

        assertEquals("live", dao.message(ACCOUNT, "correction")?.correctionTargetMessageId)
        assertEquals("live", dao.messageReactions(ACCOUNT, PEER).single().localMessageId)
    }

    @Test
    fun outboxAndConflictCandidatesRemainUntouched() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("outbox-live", "outbox", MessageTimeSource.LOCAL))
        store.ingest(incoming("conflict-live", "conflict", MessageTimeSource.LOCAL))
        store.applyArchivePage(
            page(
                archived("outbox-result", "outbox-mam", "outbox"),
                archived("conflict-result", "conflict-mam", "conflict"),
            ),
        )
        val dao = database.messageDao()
        dao.insertOutbox(
            OutboxEntity(
                ACCOUNT,
                "operation",
                "outbox-live",
                "origin",
                OutboxStatus.PENDING,
                null,
                0,
                null,
            ),
        )
        dao.insertConflict(
            IdentityConflictEntity(
                ACCOUNT,
                IdentityAliasKind.MAM_RESULT,
                "authority",
                "conflict",
                "conflict-live",
                "conflict-mam",
                4,
            ),
        )

        val state = requireNotNull(store.repairIdentitylessDuplicates(ACCOUNT))

        assertEquals(4, store.messages(ACCOUNT).size)
        assertEquals(0L, state.matchedCount)
        assertEquals(0L, state.skippedCount)
        assertEquals("outbox-live", dao.outbox(ACCOUNT, "operation")?.messageId)
        assertEquals(1, dao.conflicts(ACCOUNT).size)
    }

    @Test
    fun concurrentRepairRereadsCompleteAfterWriterSerialization() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("live", "same", MessageTimeSource.LOCAL))
        store.applyArchivePage(page(archived("result", "mam", "same")))

        val states = coroutineScope {
            listOf(
                async { store.repairIdentitylessDuplicates(ACCOUNT) },
                async { store.repairIdentitylessDuplicates(ACCOUNT) },
            ).awaitAll()
        }

        assertTrue(states.all { it?.status == ReconciliationRepairStatus.COMPLETE })
        assertEquals(1, store.messages(ACCOUNT).size)
        assertEquals(1L, states.first()?.matchedCount)
    }

    @Test
    fun failedRepairRollsBackMergeAndReceipt() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("live", "same", MessageTimeSource.LOCAL))
        store.applyArchivePage(page(archived("result", "mam", "same")))
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) error("stop during repair")
        }

        val failure = runCatching { faulting.repairIdentitylessDuplicates(ACCOUNT) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(2, store.messages(ACCOUNT).size)
        assertEquals(
            AccountReconciliationStateEntity(accountId = ACCOUNT, wallFloorMs = 1_000),
            database.accountDao().reconciliationState(ACCOUNT),
        )
    }

    @Test
    fun ordinaryRepairFailureIsCountedSeparatelyAndRemainsPending() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("live", "same", MessageTimeSource.LOCAL))
        store.applyArchivePage(page(archived("result", "mam", "same")))
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) error("ordinary failure")
        }

        assertFalse(faulting.attemptIdentitylessRepair(ACCOUNT))

        assertEquals(2, store.messages(ACCOUNT).size)
        assertEquals(
            AccountReconciliationStateEntity(accountId = ACCOUNT, wallFloorMs = 1_000, caughtErrorCount = 1),
            database.accountDao().reconciliationState(ACCOUNT),
        )
    }

    @Test
    fun cancelledRepairIsNotCountedAndPropagates() = runBlocking {
        val store = MessageStore(database, clock = { 1_000 })
        store.ingest(incoming("live", "same", MessageTimeSource.LOCAL))
        store.applyArchivePage(page(archived("result", "mam", "same")))
        val faulting = MessageStore.observingWrites(database) {
            if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) throw CancellationException("cancel")
        }

        val failure = runCatching { faulting.attemptIdentitylessRepair(ACCOUNT) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(2, store.messages(ACCOUNT).size)
        assertEquals(
            AccountReconciliationStateEntity(accountId = ACCOUNT, wallFloorMs = 1_000),
            database.accountDao().reconciliationState(ACCOUNT),
        )
    }

    private fun incoming(
        id: String,
        body: String,
        source: MessageTimeSource,
        sentAtMs: Long = 1_000,
    ) = IncomingMessage(
        accountId = ACCOUNT,
        localMessageId = id,
        peerJid = PEER,
        senderJid = PEER,
        direction = MessageDirection.INBOUND,
        messageKind = MessageKind.CHAT,
        threadId = null,
        parentThreadId = null,
        body = body,
        archiveOrdinal = null,
        aliases = emptyList(),
        sentAtEpochMs = sentAtMs,
        sentTimeSource = source,
    )

    private fun archived(
        resultId: String,
        id: String,
        body: String,
        sentAtMs: Long = 1_000,
    ) = ArchivedIncomingMessage(
        resultId,
        incoming(id, body, MessageTimeSource.MAM, sentAtMs),
    )

    private fun page(vararg messages: ArchivedIncomingMessage) = ArchivePage(
        key = ArchiveCursorKey(ACCOUNT, "account@example.org", "ACCOUNT"),
        direction = ArchiveDirection.BOOTSTRAP,
        boundaryId = null,
        complete = false,
        hasEarlier = false,
        stable = true,
        firstId = messages.first().resultId,
        lastId = messages.last().resultId,
        messages = messages.toList(),
    )

    private companion object {
        const val ACCOUNT = "account"
        const val PEER = "peer@example.org"
    }
}
