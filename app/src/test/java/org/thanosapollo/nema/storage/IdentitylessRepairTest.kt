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
import org.junit.Assert.assertNull
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

    @Test
    fun archiveReconciliationRevisionFailuresRollBackThenRetryExactlyOnce() = runBlocking {
        corruptions().forEach { (name, sourceRevision, destinationRevision) ->
            resetAccount()
            val store = MessageStore(database, clock = { 70_001 })
            val pair = seedPair(store, name)
            val rows = corruptPair(pair, sourceRevision, destinationRevision)
            val messages = store.messages(ACCOUNT)
            val positions = database.messageDao().archivePositions(ACCOUNT)
            val aliases = store.aliases(ACCOUNT)
            val cursor = requireNotNull(store.archiveCursor(pair.key))
            assertEquals(70_001L, requireNotNull(database.accountDao().reconciliationState(ACCOUNT)).wallFloorMs)
            val next = archivePage(
                pair.key,
                ArchiveDirection.AFTER,
                pair.resultId,
                complete = true,
                archived("$name-tail-result", "$name-tail", "tail", 80_000),
            )

            val failure = requireNotNull(runCatching { store.applyArchivePage(next) }.exceptionOrNull())
            val failureMethods = failure.stackTrace.mapTo(mutableSetOf()) { it.methodName }
            assertTrue(
                setOf("moveMessageReaction", "reparentReactions", "mergePair").all(failureMethods::contains),
            )
            assertEquals(messages, store.messages(ACCOUNT))
            assertEquals(positions, database.messageDao().archivePositions(ACCOUNT))
            assertEquals(aliases, store.aliases(ACCOUNT))
            assertEquals(rows, reactions())
            assertEquals(cursor, store.archiveCursor(pair.key))

            val corrected = correctPair(rows)
            corrected.forEach(::raw)
            assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(next).status)
            val final = reactions().single()
            assertEquals(pair.liveId, final.localMessageId)
            assertEquals(if (corrected.size == 1) 1 else 10, final.revision)
            assertNull(database.messageDao().message(ACCOUNT, pair.mamId))
            assertEquals(ArchivePageStatus.RETRYABLE_ERROR, store.applyArchivePage(next).status)
            assertEquals(final, reactions().single())
        }
    }

    @Test
    fun explicitRepairRevisionFailuresPreserveEvidenceAndRetry() = runBlocking {
        corruptions().forEach { (name, sourceRevision, destinationRevision) ->
            resetAccount()
            val store = MessageStore(database, clock = { 0 })
            val pair = seedPair(store, name)
            val malformed = corruptPair(pair, sourceRevision, destinationRevision)
            val owners = store.messages(ACCOUNT)
            val pending = requireNotNull(database.accountDao().reconciliationState(ACCOUNT))

            assertTrue(runCatching { store.repairIdentitylessDuplicates(ACCOUNT) }.isFailure)
            assertEquals(owners, store.messages(ACCOUNT))
            assertEquals(malformed, reactions())
            assertEquals(pending, database.accountDao().reconciliationState(ACCOUNT))
            assertFalse(store.attemptIdentitylessRepair(ACCOUNT))
            assertEquals(malformed, reactions())
            assertEquals(owners, store.messages(ACCOUNT))
            assertEquals(pending.copy(caughtErrorCount = 1), database.accountDao().reconciliationState(ACCOUNT))

            val corrected = correctPair(malformed)
            corrected.forEach(::raw)
            val cancelling = MessageStore.observingWrites(database) {
                if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) throw CancellationException("cancel")
            }
            assertTrue(runCatching { cancelling.attemptIdentitylessRepair(ACCOUNT) }.exceptionOrNull() is CancellationException)
            assertEquals(corrected, reactions())
            assertEquals(pending.copy(caughtErrorCount = 1), database.accountDao().reconciliationState(ACCOUNT))

            assertEquals(ReconciliationRepairStatus.COMPLETE, store.repairIdentitylessDuplicates(ACCOUNT)?.status)
            assertEquals(setOf(pair.liveId), store.messages(ACCOUNT).mapTo(mutableSetOf()) { it.localMessageId })
            val final = reactions().single()
            assertEquals("😀\u001F👍", final.emojis)
            assertEquals(if (corrected.size == 1) 1 else 10, final.revision)
        }
    }

    @Test
    fun accountRemovalCascadesEveryReactionShape() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming("owner", "body", MessageTimeSource.LOCAL))
        listOf(
            MessageReactionEntity(ACCOUNT, PEER, PEER, "owner", "owner", "visible", "👍", 1),
            MessageReactionEntity(ACCOUNT, PEER, "pending@example.org", "pending:pending", null, "pending", "❤️", 2),
            MessageReactionEntity(ACCOUNT, PEER, "tombstone@example.org", "pending:tombstone", null, "tombstone", "", 3),
        ).forEach { assertEquals(ReactionMutationOutcome.WRITTEN, database.messageDao().writeReactionFullSet(it)) }

        database.accountDao().remove(ACCOUNT)

        assertTrue(reactions().isEmpty())
        assertNull(database.accountDao().account(ACCOUNT))
    }

    private data class PairSeed(
        val key: ArchiveCursorKey,
        val liveId: String,
        val mamId: String,
        val resultId: String,
    )

    private fun corruptions() = listOf(
        Triple("source", 0L, null),
        Triple("destination", 7L, 0L),
        Triple("exhausted", 7L, Long.MAX_VALUE - 1),
    )

    private suspend fun resetAccount() {
        database.accountDao().account(ACCOUNT)?.let { database.accountDao().remove(ACCOUNT) }
        database.accountDao().saveBound(
            AccountEntity(ACCOUNT, "account@example.org", ACCOUNT, null, "example.org", null, null),
        )
    }

    private suspend fun seedPair(store: MessageStore, name: String): PairSeed {
        val live = "$name-live"
        val mam = "$name-mam"
        val result = "$name-result"
        val key = ArchiveCursorKey(ACCOUNT, "account@example.org", name)
        store.ingest(incoming(live, name, MessageTimeSource.LOCAL, 10_000))
        assertEquals(
            ArchivePageStatus.APPLIED,
            store.applyArchivePage(
                archivePage(key, ArchiveDirection.BOOTSTRAP, null, false, archived(result, mam, name, 40_000)),
            ).status,
        )
        return PairSeed(key, live, mam, result)
    }

    private fun corruptPair(pair: PairSeed, sourceRevision: Long, destinationRevision: Long?): List<MessageReactionEntity> {
        val source = MessageReactionEntity(
            ACCOUNT, PEER, PEER, pair.mamId, pair.mamId, "wire", "😀\u001F👍", 5_000, sourceRevision,
        )
        val rows = listOfNotNull(
            destinationRevision?.let {
                source.copy(targetKey = pair.liveId, localMessageId = pair.liveId, revision = it)
            },
            source,
        )
        rows.forEach(::raw)
        return rows
    }

    private fun correctPair(rows: List<MessageReactionEntity>) = rows.mapIndexed { index, row ->
        row.copy(revision = if (rows.size == 1) 7 else if (index == 0) 9 else 7)
    }

    private fun raw(row: MessageReactionEntity) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO message_reactions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(row.accountId, row.peerJid, row.senderBareJid, row.targetKey, row.localMessageId,
                row.wireTargetId, row.emojis, row.updatedAtMs, row.revision),
        )
    }

    private suspend fun reactions() = database.messageDao().messageReactions(ACCOUNT, PEER)

    private fun archivePage(
        key: ArchiveCursorKey,
        direction: ArchiveDirection,
        boundaryId: String?,
        complete: Boolean,
        vararg messages: ArchivedIncomingMessage,
    ) = ArchivePage(
        key, direction, boundaryId, complete, false, true,
        messages.first().resultId, messages.last().resultId, messages.toList(),
    )

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
