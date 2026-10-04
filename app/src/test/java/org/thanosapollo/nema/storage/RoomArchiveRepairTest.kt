package org.thanosapollo.nema.storage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.room.withTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RoomArchiveRepairTest {
    private lateinit var db: NemaDatabase
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val name = "room-repair-${java.util.UUID.randomUUID()}.db"
    private val dao get() = db.messageDao()
    private val store get() = MessageStore(db)
    private val room = "room@conference.example.org"
    private fun key(r: String = room) = "room-archive-uid-v1:${r.length}:$r"

    @Before fun setup() = runBlocking {
        db = NemaDatabase.create(context, name)
        db.accountDao().saveBound(AccountEntity("a", "a@example.org", "a", null, "example.org", null, null))
    }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    private suspend fun pair(prefix: String = "", r: String = room, reverse: Boolean = false) {
        dao.insertPeer(PeerEntity("a", r))
        val base = (dao.messages("a").maxOfOrNull { it.localSequence } ?: 0L)
        val live = MessageEntity("a", "${prefix}live", r, "$r/nick", MessageDirection.INBOUND,
            MessageKind.GROUPCHAT, null, null, "generic", base + if (reverse) 2 else 1, null,
            sentAtEpochMs = 1000, sentTimeSource = MessageTimeSource.LOCAL, liveDeliveryObserved = true)
        dao.insertMessage(live)
        dao.insertMessage(live.copy(localMessageId = "${prefix}mam", localSequence = base + if (reverse) 1 else 2,
            archiveOrdinal = 0, sentTimeSource = MessageTimeSource.MAM, liveDeliveryObserved = false))
        alias("${prefix}live", IdentityAliasKind.STANZA_ID, r, prefix + "uid")
        alias("${prefix}mam", IdentityAliasKind.MAM_RESULT, ArchiveCursorKey("a", r, r).aliasAuthority(), prefix + "uid")
        val ordinal = (dao.archivePositions("a").filter { it.archiveAuthority == r && it.archiveScope == r }
            .maxOfOrNull { it.archiveOrdinal } ?: -1L) + 1L
        dao.insertArchivePosition(ArchiveMessagePositionEntity("a", r, r, ordinal, "${prefix}mam"))
    }
    private suspend fun alias(id: String?, kind: IdentityAliasKind, authority: String, uid: String,
        status: IdentityAliasStatus = IdentityAliasStatus.TRUSTED) {
        dao.insertTrustedAlias(TrustedIdentityAliasEntity("a", kind, authority, uid, id, status))
    }

    @Test fun protectedRoomRepairRollsBackEvidenceAndDependentsAtWriteFault() = runBlocking {
        org.thanosapollo.nema.xmpp.smack.SmackAndroid.initialize(context)
        org.thanosapollo.nema.xmpp.smack.installNemaOmemoProviders()
        pair()
        val evidence = org.thanosapollo.nema.xmpp.smack.ProtectedFixtures.envelope(
            org.thanosapollo.nema.xmpp.omemo.OmemoProtocol.MODERN).protection!!
        val encoded = org.thanosapollo.nema.xmpp.omemo.ProtectedContentCodec.encode(evidence)
        for (id in listOf("live", "mam")) dao.updateProtectedContent("a", id, evidence.state.name, encoded)
        val before = dao.messages("a")
        val aliases = dao.trustedAliases("a")
        val positions = dao.archivePositions("a")
        val failing = MessageStore.observingWrites(db) {
            if (it == MessageWriteBoundary.AFTER_PROTECTED_CONTENT) error("synthetic protected write fault")
        }
        assertTrue(runCatching { failing.repairRoomArchiveDuplicates("a", room) { true } }.isFailure)
        db.close(); db = NemaDatabase.create(context, name)
        assertEquals(before, dao.messages("a"))
        assertEquals(aliases, dao.trustedAliases("a"))
        assertEquals(positions, dao.archivePositions("a"))
        assertEquals(1L, store.repairRoomArchiveDuplicates("a", room) { true }!!.matchedCount)
        assertEquals(encoded, dao.messages("a").single().protectedEvidence)
    }

    @Test fun protectedRepairRefusesUnknownOrDifferentEvidenceBeforeAnyDependentMutation() = runBlocking {
        org.thanosapollo.nema.xmpp.smack.SmackAndroid.initialize(context)
        org.thanosapollo.nema.xmpp.smack.installNemaOmemoProviders()
        val evidence = org.thanosapollo.nema.xmpp.smack.ProtectedFixtures.envelope(
            org.thanosapollo.nema.xmpp.omemo.OmemoProtocol.LEGACY).protection!!
        val encoded = org.thanosapollo.nema.xmpp.omemo.ProtectedContentCodec.encode(evidence)
        for (mode in listOf("same", "different", "unknown", "ordinary")) {
            val r = "$mode@conference.example.org"
            pair(mode, r)
            dao.updateProtectedContent("a", mode + "live", evidence.state.name, encoded)
            if (mode != "ordinary") {
                val other = when (mode) {
                    "unknown" -> "{\"version\":99}"
                    "different" -> org.thanosapollo.nema.xmpp.omemo.ProtectedContentCodec.encode(
                        evidence.copy(content = evidence.content!!.copy(payload = "BAUG")))
                    else -> encoded
                }
                dao.updateProtectedContent("a", mode + "mam", evidence.state.name, other)
            }
            val before = dao.messages("a")
            val aliases = dao.trustedAliases("a")
            val positions = dao.archivePositions("a")
            val result = requireNotNull(store.repairRoomArchiveDuplicates("a", r) { true })
            if (mode == "same") {
                assertEquals(1L, result.matchedCount)
                assertEquals(encoded, dao.message("a", mode + "live")!!.protectedEvidence)
            } else {
                assertEquals(ReconciliationRepairStatus.PENDING, result.status)
                assertEquals(before, dao.messages("a"))
                assertEquals(aliases, dao.trustedAliases("a"))
                assertEquals(positions, dao.archivePositions("a"))
            }
        }
    }

    @Test fun historicalPairAndIndependentRoomReceiptSurviveReopenAndRaces() = runBlocking {
        pair()
        pair("b", "unavailable@conference.example.org")
        val identityless = db.accountDao().reconciliationState("a")
        val receipts = listOf(async { store.repairRoomArchiveDuplicates("a", room) { true } },
            async { store.repairRoomArchiveDuplicates("a", room) { true } }).awaitAll()
        val receipt = requireNotNull(receipts.first())
        assertEquals(receipt, receipts.last())
        assertEquals(4L, receipt.beforeCount)
        assertEquals(3L, receipt.afterCount)
        assertEquals(1L, receipt.matchedCount)
        assertEquals(0L, receipt.skippedCount)
        assertEquals(ReconciliationRepairStatus.COMPLETE, receipt.status)
        assertEquals(setOf("live", "blive", "bmam"), dao.messages("a").map { it.localMessageId }.toSet())
        assertNull(db.accountDao().reconciliationState("a", key("unavailable@conference.example.org")))
        assertEquals(identityless, db.accountDao().reconciliationState("a"))
        db.close(); db = NemaDatabase.create(context, name)
        assertEquals(receipt, store.repairRoomArchiveDuplicates("a", room) { true })
    }


    @Test fun allReadCombinationsAndSurvivorOrdersPreserveDependents() = runBlocking {
        for (reverse in listOf(false, true)) for (liveRead in listOf(false, true)) for (mamRead in listOf(false, true)) {
            val r = "r$reverse$liveRead$mamRead@conference.example.org"
            val prefix = "$r-"
            pair(prefix, r, reverse)
            val live = requireNotNull(dao.message("a", prefix + "live"))
            val mam = requireNotNull(dao.message("a", prefix + "mam"))
            if (liveRead) dao.markMessageIdsRead("a", r, listOf(live.localMessageId))
            if (mamRead) dao.markMessageIdsRead("a", r, listOf(mam.localMessageId))
            val loser = if (reverse) live else mam
            val winner = if (reverse) mam else live
            dao.insertMessage(live.copy(localMessageId = prefix + "correction", localSequence = dao.messages("a").maxOf { it.localSequence } + 1L,
                body = "edited", correctionTargetMessageId = loser.localMessageId))
            assertEquals(ReactionMutationOutcome.WRITTEN, dao.writeReactionFullSet(MessageReactionEntity(
                "a", r, "$r/nick", loser.localMessageId, loser.localMessageId, "uid", "👍", 2000)))
            // Schema 25 retains a sequence pointer, not a foreign key to the removed row.
            db.openHelper.writableDatabase.execSQL(
                "UPDATE peers SET lastReadLocalSequence = ? WHERE accountId = 'a' AND jid = ?",
                arrayOf<Any>(loser.localSequence, r))
            val cursor = ArchiveCursorEntity("a", r, r, "old", "new", true, "retained", -7, 9)
            dao.upsertArchiveCursor(cursor)
            val receipt = requireNotNull(store.repairRoomArchiveDuplicates("a", r) { true })
            assertEquals(1L, receipt.matchedCount)
            assertEquals(cursor, store.archiveCursor(ArchiveCursorKey("a", r, r)))
            db.openHelper.writableDatabase.query(
                "SELECT lastReadLocalSequence FROM peers WHERE accountId = 'a' AND jid = ?", arrayOf(r)).use {
                assertTrue(it.moveToFirst())
                assertEquals(loser.localSequence, it.getLong(0))
            }
            val result = requireNotNull(dao.message("a", winner.localMessageId))
            assertEquals(liveRead || mamRead, result.locallyRead)
            assertEquals(winner.localSequence, result.localSequence)
            assertTrue(result.liveDeliveryObserved)
            assertEquals(MessageTimeSource.MAM, result.sentTimeSource)
            // The synthetic legacy MUC link has no trusted retained claim; repair revokes it.
            assertNull(dao.message("a", prefix + "correction")?.correctionTargetMessageId)
            assertEquals(winner.localMessageId, dao.messageReactions("a", r).single().localMessageId)
            assertEquals(winner.localMessageId, dao.archivePositions("a").single { it.archiveAuthority == r }.messageId)
        }
    }

    @Test fun hiddenThirdAndCrossUnitTransitiveClaimsBlockWholeComponentInEveryOrder() = runBlocking {
        pair()
        pair("b", "other@conference.example.org")
        alias("blive", IdentityAliasKind.STANZA_ID, room, "bridge")
        alias("mam", IdentityAliasKind.MAM_RESULT, ArchiveCursorKey("a", room, room).aliasAuthority(), "bridge")
        val rows = dao.messages("a")
        val claims = dao.trustedAliases("a")
        for (orderedRows in listOf(rows, rows.reversed())) for (orderedClaims in listOf(claims, claims.reversed())) {
            val component = roomRepairComponents(orderedRows, orderedClaims, emptyList(), emptySet()).single()
            assertEquals(4, component.messages.size)
            assertNull(component.pair(room, dao.archivePositions("a").groupBy { it.messageId }))
        }
        val state = requireNotNull(store.repairRoomArchiveDuplicates("a", room) { true })
        assertEquals(0L, state.matchedCount)
        assertEquals(1L, state.skippedCount)
        assertEquals(rows, dao.messages("a"))
    }

    @Test fun malformedScopeQuarantineOutboxAndConflictAreNotMergeEvidence() = runBlocking {
        for (mode in listOf("encoding", "scope", "outbox", "conflict", "quarantine", "body", "sender", "direction", "thread", "metadata")) {
            val r = "$mode@conference.example.org"
            pair(mode, r)
            val liveId = mode + "live"
            val mamId = mode + "mam"
            val authority = ArchiveCursorKey("a", r, r).aliasAuthority()
            when (mode) {
                "encoding", "scope" -> {
                    db.openHelper.writableDatabase.execSQL(
                        "UPDATE trusted_identity_aliases SET authority = ? WHERE messageId = ?",
                        arrayOf(if (mode == "encoding") "0$authority" else ArchiveCursorKey("a", r, "ACCOUNT").aliasAuthority(), mamId))
                }
                "outbox" -> dao.insertOutbox(OutboxEntity("a", mode, liveId, "origin", OutboxStatus.PENDING, null, 0, null))
                "conflict" -> dao.insertConflict(IdentityConflictEntity("a", IdentityAliasKind.STANZA_ID, r,
                    mode + "uid", liveId, mamId, 1))
                "quarantine" -> alias(liveId, IdentityAliasKind.MESSAGE_ID, r, "blocked", IdentityAliasStatus.QUARANTINED)
                "body" -> dao.updateMessage(requireNotNull(dao.message("a", mamId)).copy(body = "different"))
                "sender" -> dao.updateMessage(requireNotNull(dao.message("a", mamId)).copy(senderJid = "$r/other"))
                "direction" -> dao.updateMessage(requireNotNull(dao.message("a", mamId)).copy(direction = MessageDirection.OUTBOUND))
                "thread" -> {
                    dao.insertThread(MessageThreadEntity("a", r, MessageKind.GROUPCHAT, "other", null))
                    dao.updateMessage(requireNotNull(dao.message("a", mamId)).copy(threadId = "other"))
                }
                "metadata" -> dao.updateMessage(requireNotNull(dao.message("a", mamId)).copy(attachmentUrl = "https://example.org/file"))
            }
            val rows = dao.messages("a")
            val aliases = dao.trustedAliases("a")
            val outboxes = dao.outboxes("a")
            val conflicts = dao.conflicts("a")
            val receipt = requireNotNull(store.repairRoomArchiveDuplicates("a", r) { true })
            assertEquals(mode, 0L, receipt.matchedCount)
            assertEquals(mode, 1L, receipt.skippedCount)
            assertEquals(rows, dao.messages("a"))
            assertEquals(aliases, dao.trustedAliases("a"))
            assertEquals(outboxes, dao.outboxes("a"))
            assertEquals(conflicts, dao.conflicts("a"))
        }
    }

    @Test fun receiptCasAndPoisonedTransferRollbackAndFreshFailureAdmission() = runBlocking {
        pair()
        val rows = dao.messages("a")
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_room_receipt BEFORE UPDATE ON account_reconciliation_state
            WHEN NEW.repairKey != 'identityless-live-mam-v1' BEGIN SELECT RAISE(IGNORE); END""")
        assertTrue(runCatching { store.repairRoomArchiveDuplicates("a", room) { true } }.isFailure)
        assertEquals(rows, dao.messages("a"))
        assertNull(db.accountDao().reconciliationState("a", key()))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_room_receipt")
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.writeReactionFullSet(MessageReactionEntity(
            "a", room, "$room/nick", "mam", "mam", "uid", "👍", 1)))
        db.openHelper.writableDatabase.execSQL("UPDATE message_reactions SET revision = 0")
        assertTrue(runCatching { store.repairRoomArchiveDuplicates("a", room) { true } }.isFailure)
        assertEquals(rows, dao.messages("a"))
        assertEquals("mam", dao.messageReactions("a", room).single().localMessageId)
        assertNull(db.accountDao().reconciliationState("a", key()))
        var admissions = 0
        assertFalse(store.attemptRoomArchiveRepair("a", room) { ++admissions == 1 })
        assertEquals(2, admissions)
        assertNull(db.accountDao().reconciliationState("a", key()))
        assertFalse(store.attemptRoomArchiveRepair("a", room) { true })
        assertEquals(1L, db.accountDao().reconciliationState("a", key())?.caughtErrorCount)
        assertEquals(ReconciliationRepairStatus.PENDING, db.accountDao().reconciliationState("a", key())?.status)
    }

    @Test fun undecodableHiddenClaimantBlocksOtherwiseValidPair() = runBlocking {
        pair()
        val live = requireNotNull(dao.message("a", "live"))
        dao.insertMessage(live.copy(localMessageId = "hidden", localSequence = 3))
        alias("hidden", IdentityAliasKind.MAM_RESULT, "not-an-encoding", "uid")
        val before = dao.messages("a")
        val receipt = requireNotNull(store.repairRoomArchiveDuplicates("a", room) { true })
        assertEquals(0L, receipt.matchedCount)
        assertEquals(before, dao.messages("a"))
    }

    @Test fun exactOpaqueUidsAndUtf16ReceiptKeys() = runBlocking {
        for ((index, values) in listOf("UID" to "uid", " uid" to "uid", "é" to "e\u0301",
            "" to "", "a:b/💬" to "a:b/💬").withIndex()) {
            val r = "💬$index@conference.example.org"
            val prefix = "$index-"
            pair(prefix, r)
            for ((id, value) in listOf(prefix + "live" to values.first, prefix + "mam" to values.second)) {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE trusted_identity_aliases SET value = ? WHERE messageId = ?", arrayOf(value, id))
            }
            val receipt = requireNotNull(store.repairRoomArchiveDuplicates("a", r) { true })
            assertEquals(if (index == 4) 1L else 0L, receipt.matchedCount)
            assertEquals("room-archive-uid-v1:26:💬$index@conference.example.org", receipt.repairKey)
        }
    }

    @Test fun nullOwnerQuarantineAndConflictHiddenThirdPreserveWholeComponent() = runBlocking {
        pair()
        val live = requireNotNull(dao.message("a", "live"))
        dao.insertMessage(live.copy(localMessageId = "hidden", localSequence = 3))
        alias(null, IdentityAliasKind.STANZA_ID, room, "bridge", IdentityAliasStatus.QUARANTINED)
        dao.insertConflict(IdentityConflictEntity("a", IdentityAliasKind.STANZA_ID, room,
            "bridge", "mam", "hidden", 1))
        val rows = dao.messages("a")
        val aliases = dao.trustedAliases("a")
        val conflicts = dao.conflicts("a")
        for (rs in listOf(rows, rows.reversed())) for (als in listOf(aliases, aliases.reversed())) {
            val graph = roomRepairComponents(rs, als, conflicts, emptySet())
            assertEquals(3, graph.single().messages.size)
            assertNull(graph.single().pair(room, dao.archivePositions("a").groupBy { it.messageId }))
        }
        val receipt = requireNotNull(store.repairRoomArchiveDuplicates("a", room) { true })
        assertEquals(0L, receipt.matchedCount)
        assertEquals(1L, receipt.skippedCount)
        assertEquals(rows, dao.messages("a"))
        assertEquals(aliases, dao.trustedAliases("a"))
        assertEquals(conflicts, dao.conflicts("a"))
    }

    @Test fun destinationPoisonRollsBackThenRetryCompletes() = runBlocking {
        pair()
        for (id in listOf("live", "mam")) {
            dao.writeReactionFullSet(MessageReactionEntity("a", room, "$room/nick", id, id, "uid", "👍", 1))
        }
        db.openHelper.writableDatabase.execSQL("UPDATE message_reactions SET revision = 0 WHERE localMessageId = 'live'")
        val rows = dao.messages("a")
        val reactions = dao.messageReactions("a", room)
        assertFalse(store.attemptRoomArchiveRepair("a", room) { true })
        assertEquals(rows, dao.messages("a"))
        assertEquals(reactions, dao.messageReactions("a", room))
        assertEquals(1L, db.accountDao().reconciliationState("a", key())?.caughtErrorCount)
        db.openHelper.writableDatabase.execSQL("UPDATE message_reactions SET revision = 1")
        assertTrue(store.attemptRoomArchiveRepair("a", room) { true })
        assertEquals(1, dao.messages("a").size)
        assertEquals(ReconciliationRepairStatus.COMPLETE, db.accountDao().reconciliationState("a", key())?.status)
    }

    @Test fun exactUidCannotCrossAccountRoomOrAccountArchiveThroughRealDao() = runBlocking {
        db.accountDao().saveBound(AccountEntity("b", "b@example.org", "b", null, "example.org", null, null))
        for (mode in listOf("account", "room", "account-archive")) {
            val r = "$mode@conference.example.org"
            pair(mode, r)
            val mam = requireNotNull(dao.message("a", mode + "mam"))
            dao.deleteMessage(mam)
            val account = if (mode == "account") "b" else "a"
            val authority = if (mode == "room") "other@conference.example.org" else r
            val scope = if (mode == "account-archive") "ACCOUNT" else authority
            dao.insertPeer(PeerEntity(account, authority))
            dao.insertMessage(mam.copy(accountId = account, peerJid = authority))
            dao.insertTrustedAlias(TrustedIdentityAliasEntity(account, IdentityAliasKind.MAM_RESULT,
                ArchiveCursorKey(account, authority, scope).aliasAuthority(), mode + "uid", mam.localMessageId,
                IdentityAliasStatus.TRUSTED))
            dao.insertArchivePosition(ArchiveMessagePositionEntity(account, authority, scope, 0, mam.localMessageId))
            val rows = listOf(dao.messages("a"), dao.messages("b"))
            val claims = listOf(dao.trustedAliases("a"), dao.trustedAliases("b"))
            val positions = listOf(dao.archivePositions("a"), dao.archivePositions("b"))
            val receipt = requireNotNull(store.repairRoomArchiveDuplicates("a", r) { true })
            assertEquals(mode, 0L, receipt.matchedCount)
            assertEquals(rows, listOf(dao.messages("a"), dao.messages("b")))
            assertEquals(claims, listOf(dao.trustedAliases("a"), dao.trustedAliases("b")))
            assertEquals(positions, listOf(dao.archivePositions("a"), dao.archivePositions("b")))
            assertNull(db.accountDao().reconciliationState("b", key(r)))
        }
    }

    @Test fun sourcePoisonRetryPreservesDependentsAndCompletesOnce() = runBlocking {
        pair()
        dao.writeReactionFullSet(MessageReactionEntity("a", room, "$room/nick", "mam", "mam", "uid", "👍", 1))
        db.openHelper.writableDatabase.execSQL("UPDATE message_reactions SET revision = 0")
        val rows = dao.messages("a")
        val claims = dao.trustedAliases("a")
        val positions = dao.archivePositions("a")
        val reactions = dao.messageReactions("a", room)
        assertFalse(store.attemptRoomArchiveRepair("a", room) { true })
        assertEquals(rows, dao.messages("a"))
        assertEquals(claims, dao.trustedAliases("a"))
        assertEquals(positions, dao.archivePositions("a"))
        assertEquals(reactions, dao.messageReactions("a", room))
        val pending = requireNotNull(db.accountDao().reconciliationState("a", key()))
        assertEquals(ReconciliationRepairStatus.PENDING, pending.status)
        assertEquals(1L, pending.caughtErrorCount)
        db.openHelper.writableDatabase.execSQL("UPDATE message_reactions SET revision = 7")
        val receipt = requireNotNull(store.repairRoomArchiveDuplicates("a", room) { true })
        assertEquals(2L, receipt.beforeCount)
        assertEquals(1L, receipt.afterCount)
        assertEquals(1L, receipt.matchedCount)
        assertEquals(1L, receipt.caughtErrorCount)
        assertEquals(ReconciliationRepairStatus.COMPLETE, receipt.status)
        val transferred = dao.messageReactions("a", room).single()
        assertEquals("live", transferred.localMessageId)
        assertEquals("👍", transferred.emojis)
        assertEquals(1L, transferred.revision)
        assertEquals(receipt, store.repairRoomArchiveDuplicates("a", room) { true })
        assertEquals(transferred, dao.messageReactions("a", room).single())
    }

    @Test fun cancellationQueuedBehindActualRoomWriterNeverReachesAdmission() = runBlocking {
        pair()
        val rows = dao.messages("a")
        val claims = dao.trustedAliases("a")
        val positions = dao.archivePositions("a")
        val identityless = db.accountDao().reconciliationState("a")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var admissions = 0
        var writes = 0
        val observed = MessageStore.observingWrites(db) { writes++ }
        val holder = async { db.withTransaction { entered.complete(Unit); release.await() } }
        try {
            withTimeout(10_000) { entered.await() }
            // Test-only observation of Room's serialized executor queue proves actual submission,
            // rather than assuming launch/UNDISPATCHED establishes writer contention.
            val executor = db.transactionExecutor
            val field = executor.javaClass.declaredFields.single {
                java.util.Collection::class.java.isAssignableFrom(it.type)
            }.apply { isAccessible = true }
            fun queued() = synchronized(executor) { (field.get(executor) as Collection<*>).size }
            assertEquals(0, queued())
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                observed.attemptRoomArchiveRepair("a", room) { admissions++; true }
            }
            assertEquals(1, queued())
            assertEquals(0, admissions)
            withTimeout(10_000) { pending.cancelAndJoin() }
            release.complete(Unit)
            withTimeout(10_000) { holder.await() }
            // Drain the same writer executor before making negative assertions.
            withTimeout(10_000) { db.withTransaction { } }
            assertTrue(pending.isCancelled)
            assertEquals(0, admissions)
            assertEquals(0, writes)
            assertEquals(rows, dao.messages("a"))
            assertEquals(claims, dao.trustedAliases("a"))
            assertEquals(positions, dao.archivePositions("a"))
            assertNull(db.accountDao().reconciliationState("a", key()))
            assertEquals(identityless, db.accountDao().reconciliationState("a"))
        } finally {
            release.complete(Unit)
            withTimeout(10_000) { holder.await() }
        }
    }

    @Test fun additionalClaimsAndProvenanceBlockWithoutHidingUnrelatedPairs() = runBlocking {
        for (mode in listOf("sid-uid", "mam-uid", "sid-authority", "mam-scope", "mam-encoding", "mam-live", "no-live",
            "both-live", "live-position", "foreign-position", "chat", "correction")) {
            val r = "$mode@conference.example.org"
            pair(mode, r)
            pair("valid-$mode", r)
            val live = requireNotNull(dao.message("a", mode + "live"))
            val mam = requireNotNull(dao.message("a", mode + "mam"))
            when (mode) {
                "sid-uid" -> alias(live.localMessageId, IdentityAliasKind.STANZA_ID, r, "contradiction")
                "mam-uid" -> alias(mam.localMessageId, IdentityAliasKind.MAM_RESULT, ArchiveCursorKey("a", r, r).aliasAuthority(), "contradiction")
                "sid-authority" -> alias(live.localMessageId, IdentityAliasKind.STANZA_ID, "foreign.example.org", "extra")
                "mam-scope", "mam-encoding" -> alias(mam.localMessageId, IdentityAliasKind.MAM_RESULT,
                    if (mode == "mam-scope") ArchiveCursorKey("a", r, "ACCOUNT").aliasAuthority()
                    else "0" + ArchiveCursorKey("a", r, r).aliasAuthority(), "extra")
                "mam-live" -> alias(live.localMessageId, IdentityAliasKind.MAM_RESULT, ArchiveCursorKey("a", r, r).aliasAuthority(), "extra")
                "no-live" -> dao.updateMessage(live.copy(liveDeliveryObserved = false))
                "both-live" -> dao.updateMessage(mam.copy(liveDeliveryObserved = true))
                "live-position" -> dao.insertArchivePosition(ArchiveMessagePositionEntity("a", r, r, 2, live.localMessageId))
                "foreign-position" -> dao.insertArchivePosition(ArchiveMessagePositionEntity("a", r, "ACCOUNT", 1, mam.localMessageId))
                "chat" -> dao.updateMessage(mam.copy(messageKind = MessageKind.CHAT))
                "correction" -> dao.updateMessage(mam.copy(replaceId = "original"))
            }
            val blockedRows = listOf(dao.message("a", live.localMessageId), dao.message("a", mam.localMessageId))
            val receipt = requireNotNull(store.repairRoomArchiveDuplicates("a", r) { true })
            assertEquals(mode, 1L, receipt.matchedCount)
            assertEquals(mode, 1L, receipt.skippedCount)
            assertEquals(blockedRows, listOf(dao.message("a", live.localMessageId), dao.message("a", mam.localMessageId)))
            assertNull(dao.message("a", "valid-${mode}mam"))
        }
    }

    @Test fun hiddenMalformedScopesBlockInBothPersistedInsertionOrders() = runBlocking {
        for (hiddenFirst in listOf(false, true)) for (malformed in listOf(false, true)) {
            val r = "hidden-$hiddenFirst-$malformed@conference.example.org"
            val prefix = "$r-"
            suspend fun hidden() {
                dao.insertPeer(PeerEntity("a", r))
                dao.insertMessage(MessageEntity("a", prefix + "third", r, "$r/nick", MessageDirection.INBOUND,
                    MessageKind.GROUPCHAT, null, null, "generic",
                    (dao.messages("a").maxOfOrNull { it.localSequence } ?: 0L) + 1, null,
                    sentAtEpochMs = 1000, sentTimeSource = MessageTimeSource.MAM))
                alias(prefix + "third", IdentityAliasKind.MAM_RESULT,
                    if (malformed) "0" + ArchiveCursorKey("a", r, r).aliasAuthority()
                    else ArchiveCursorKey("a", r, "ACCOUNT").aliasAuthority(), prefix + "uid")
            }
            if (hiddenFirst) hidden()
            pair(prefix, r)
            if (!hiddenFirst) hidden()
            val rows = dao.messages("a")
            val claims = dao.trustedAliases("a")
            val component = roomRepairComponents(rows, claims, emptyList(), emptySet())
                .single { it.messages.any { row -> row.peerJid == r } }
            assertEquals(3, component.messages.size)
            val receipt = requireNotNull(store.repairRoomArchiveDuplicates("a", r) { true })
            assertEquals(0L, receipt.matchedCount)
            assertEquals(1L, receipt.skippedCount)
            assertEquals(rows, dao.messages("a"))
            assertEquals(claims, dao.trustedAliases("a"))
        }
    }

    @Test fun cancelledAttemptNeverRecordsAnError() = runBlocking {
        pair()
        val before = dao.messages("a")
        val faulting = MessageStore.observingWrites(db) {
            if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) throw CancellationException("synthetic")
        }
        assertTrue(runCatching { faulting.attemptRoomArchiveRepair("a", room) { true } }.exceptionOrNull() is CancellationException)
        assertEquals(before, dao.messages("a"))
        assertNull(db.accountDao().reconciliationState("a", key()))
    }

    @Test fun rejectedAdmissionDoesNotEvenInsertReceipt() = runBlocking {
        pair()
        val before = dao.messages("a")
        assertNull(store.repairRoomArchiveDuplicates("a", room) { false })
        assertEquals(before, dao.messages("a"))
        assertNull(db.accountDao().reconciliationState("a", key()))
    }

    @Test fun dependentFailureAndCancellationRollbackEverything() = runBlocking {
        pair()
        val messages = dao.messages("a")
        val aliases = dao.trustedAliases("a")
        val positions = dao.archivePositions("a")
        for (cancel in listOf(false, true)) {
            val faulting = MessageStore.observingWrites(db) {
                if (it == MessageWriteBoundary.AFTER_DEPENDENT_REPARENT) {
                    if (cancel) throw CancellationException("synthetic") else error("synthetic")
                }
            }
            val error = runCatching { faulting.repairRoomArchiveDuplicates("a", room) { true } }.exceptionOrNull()
            assertTrue(if (cancel) error is CancellationException else error is IllegalStateException)
            assertEquals(messages, dao.messages("a"))
            assertEquals(aliases, dao.trustedAliases("a"))
            assertEquals(positions, dao.archivePositions("a"))
            assertNull(db.accountDao().reconciliationState("a", key()))
        }
    }
}
