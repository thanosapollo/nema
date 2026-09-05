package org.thanosapollo.nema.storage

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class MucCorrectionResolutionTest : ReactionStoreTestFixture() {
    private val key = ArchiveCursorKey(ACCOUNT, ROOM, ROOM)
    private fun event(id: String, target: String? = null, actor: String = "a", sender: String = "$ROOM/nick") =
        IncomingMessage(ACCOUNT, id, ROOM, sender, MessageDirection.INBOUND, MessageKind.GROUPCHAT,
            null, null, "body-$id", null, listOf(
                TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, sender, id),
                TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, ROOM, "sid-$id")),
            mucFacts = MucEventFacts(id, target, if (target == null) MucClaimState.NONE else MucClaimState.VALID,
                actor, MucOccupantEvidence.LIVE_ROOM, MucPayloadState.PLAIN), mucLiveOrderEpoch = "epoch")

    private suspend fun page(vararg messages: IncomingMessage): ArchivePageResult {
        val current = store.archiveCursor(key)
        return store.applyArchivePage(ArchivePage(key,
            if (current == null) ArchiveDirection.BOOTSTRAP else ArchiveDirection.AFTER,
            current?.newestId, true, false, true, messages.first().localMessageId, messages.last().localMessageId,
            messages.map { ArchivedIncomingMessage(it.localMessageId, it.copy(sentAtEpochMs = 1234, sentTimeSource = MessageTimeSource.MAM)) }))
    }
    private suspend fun visible() = database.messageDao().observeDirectTimeline(ACCOUNT, ROOM).first()
    private suspend fun rootBody(body: String?) {
        val row = visible().single { it.localMessageId == "root" }
        assertEquals(body, row.correctedBody)
        assertEquals(body != null, row.edited)
        assertEquals("body-root", database.messageDao().message(ACCOUNT, "root")!!.body)
    }
    private fun reopen() {
        database.close()
        database = NemaDatabase.create(context, databaseName)
        store = MessageStore(database)
    }

    @Test fun liveRepeatedOriginalTargetAndAllSummaryPathsSurviveReopen() = runBlocking {
        installFacts(event("root"), event("first", "root"), event("last", "root"))
        val original = database.messageDao().message(ACCOUNT, "root")!!
        repeat(2) {
            rootBody("body-last")
            assertEquals(listOf("root"), visible().map { it.localMessageId })
            assertEquals("body-last", database.messageDao().observeConversationSummaries(ACCOUNT).first().single().preview)
            assertEquals("body-last", database.messageDao().cachedConversationSummaries(ACCOUNT).single().preview)
            assertEquals(original, database.messageDao().message(ACCOUNT, "root"))
            reopen()
        }
    }

    @Test fun wrongActorFullSenderRoomPayloadAndUnknownRootRemainVisible() = runBlocking {
        val root = event("root")
        installFacts(root, event("valid", "root"))
        listOf(event("actor", "root", actor = "other"), event("nick", "root", sender = "$ROOM/other"),
            event("room", "root").copy(peerJid = "other@conference.example.org"),
            event("payload", "root").copy(attachmentUrl = "https://example.org/a")).forEach { store.ingest(it) }
        rootBody("body-valid")
        assertEquals(setOf("root", "actor", "nick", "payload"), visible().map { it.localMessageId }.toSet())
        resetStore()
        installFacts(root.copy(mucFacts = root.mucFacts!!.copy(claim = MucClaimState.UNKNOWN)), event("edit", "root"))
        rootBody(null)
        assertEquals(2, visible().size)
    }

    @Test fun missingOriginalArrivesLaterAndDuplicateObservationsDoNotDuplicate() = runBlocking {
        val edit = event("edit", "root")
        installFacts(edit)
        assertNull(database.messageDao().message(ACCOUNT, "edit")!!.replaceId)
        installFacts(event("root"), edit.copy(localMessageId = "replayed"))
        rootBody("body-edit")
        assertEquals(2, store.messages(ACCOUNT).size)
    }

    @Test fun actorConflictRevokesWinnerAndSelectsRemainingAuthorizedEditWithoutNewArrival() = runBlocking {
        val last = event("last", "root")
        installFacts(event("root"), event("first", "root"), last)
        rootBody("body-last")
        val result = store.ingest(last.copy(localMessageId = "replay", mucFacts = last.mucFacts!!.copy(occupantId = "changed")))
        assertFalse(result.inserted)
        assertFalse(result.firstLiveDelivery)
        rootBody("body-first")
        assertEquals(setOf("root", "last"), visible().map { it.localMessageId }.toSet())
        reopen()
        rootBody("body-first")
    }

    @Test fun targetAliasQuarantineRevokesPriorAcceptance() = runBlocking {
        installFacts(event("root"), event("edit", "root"))
        rootBody("body-edit")
        val collision = event("collision").let { it.copy(aliases = it.aliases +
            TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, "$ROOM/nick", "root")) }
        assertTrue(store.ingest(collision).identityConflict)
        rootBody(null)
        assertNull(database.messageDao().message(ACCOUNT, "edit")!!.replaceId)
        reopen()
        rootBody(null)
    }

    @Test fun unknownOrderFallsBackUntilFinalPagePositionsProveWinner() = runBlocking {
        installFacts(event("root"), event("first", "root"))
        installFacts(event("last", "root").copy(mucLiveOrderEpoch = "other-epoch"))
        rootBody(null)
        assertEquals(3, visible().size)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE selections (id TEXT)")
        sql.execSQL("CREATE TRIGGER record_selection AFTER UPDATE ON messages WHEN NEW.mucCorrectionSelected = 1 BEGIN INSERT INTO selections VALUES (NEW.localMessageId); END")
        assertEquals(ArchivePageStatus.APPLIED, page(event("root"), event("last", "root"), event("first", "root")).status)
        rootBody("body-first")
        sql.query("SELECT id FROM selections").use { c -> assertEquals(1, c.count); assertTrue(c.moveToFirst()); assertEquals("first", c.getString(0)) }
        assertEquals("first", store.archiveCursor(key)!!.newestId)
        reopen()
        rootBody("body-first")
    }

    @Test fun capPlusOneClearsPriorAcceptedSetAndStillAdvancesCursor() = runBlocking {
        val edits = (1..128).map { event("edit-$it", "root") }
        assertEquals(ArchivePageStatus.APPLIED, page(event("root"), *edits.toTypedArray()).status)
        rootBody("body-edit-128")
        assertEquals(128, database.messageDao().acceptedMucCorrections(ACCOUNT, "root").size)
        assertEquals(ArchivePageStatus.APPLIED, page(event("overflow", "root")).status)
        rootBody(null)
        assertTrue(database.messageDao().acceptedMucCorrections(ACCOUNT, "root").isEmpty())
        assertEquals(130, visible().size)
        assertEquals("overflow", store.archiveCursor(key)!!.newestId)
        val extra = (1..150).map { event("pending-$it", "root") }
        assertEquals(ArchivePageStatus.APPLIED, page(*extra.toTypedArray()).status)
        assertEquals(129, database.messageDao().mucCorrectionClaims(ACCOUNT, ROOM, "$ROOM/nick", "root").size)
        assertEquals("pending-150", store.archiveCursor(key)!!.newestId)
        reopen()
        rootBody(null)
    }

    @Test fun selectionFailureRollsBackMessagesAliasesPositionsAndCursor() = runBlocking {
        installFacts(event("root"))
        val before = state()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_selection BEFORE UPDATE ON messages WHEN NEW.mucCorrectionSelected = 1 BEGIN SELECT RAISE(ABORT, 'selection fault'); END")
        assertTrue(runCatching { page(event("edit", "root")) }.isFailure)
        assertEquals(before.messages, state().messages)
        assertEquals(before.aliases, state().aliases)
        assertTrue(database.messageDao().archivePositions(ACCOUNT).isEmpty())
        assertNull(store.archiveCursor(key))
        reopen()
        rootBody(null)
    }

    @Test fun rejectedPinnedPageReplayStillReconcilesFinalPositions() = runBlocking {
        installFacts(event("root"), event("first", "root"), event("last", "root"))
        rootBody("body-last")
        val replay = listOf("last", "first").map { id ->
            event(id, "root").copy(localMessageId = "replay-$id", body = "incompatible")
        }
        assertEquals(ArchivePageStatus.APPLIED, page(*replay.toTypedArray()).status)
        assertEquals(3, store.messages(ACCOUNT).size)
        assertEquals(listOf("last", "first"), database.messageDao().archivePositions(ACCOUNT)
            .sortedBy { it.archiveOrdinal }.map { it.messageId })
        rootBody("body-first")
        reopen()
        rootBody("body-first")
    }

    @Test fun mergeBothSurvivorOrdersRevokesLiveWinnerUntilArchiveOrderReturns() = runBlocking {
        for (duplicateFirst in listOf(false, true)) {
            resetStore()
            val edit = event("edit", "root")
            val origin = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, "$ROOM/nick", "bridge")
            val duplicate = edit.copy(localMessageId = "duplicate", aliases = listOf(origin))
            installFacts(event("root"))
            if (duplicateFirst) installFacts(duplicate) else installFacts(edit)
            installFacts(event("middle", "root"))
            if (duplicateFirst) installFacts(edit) else installFacts(duplicate)
            rootBody("body-edit")
            val result = store.ingest(edit.copy(aliases = listOf(origin)))
            assertEquals(1, result.mergedRows)
            assertEquals(if (duplicateFirst) "duplicate" else "edit", result.messageId)
            assertNull(database.messageDao().message(ACCOUNT, result.messageId)!!.mucLiveOrderEpoch)
            rootBody(null)
            assertTrue(database.messageDao().acceptedMucCorrections(ACCOUNT, "root").isEmpty())
            assertEquals(ArchivePageStatus.APPLIED, page(event("middle", "root"), edit).status)
            rootBody("body-edit")
            assertEquals(2, database.messageDao().acceptedMucCorrections(ACCOUNT, "root").size)
            assertEquals(result.messageId, database.messageDao().trustedAlias(ACCOUNT,
                IdentityAliasKind.STANZA_ID, ROOM, "sid-edit")!!.messageId)
            reopen()
            rootBody("body-edit")
        }
    }

    @Test fun richAndMalformedRootsNeverAuthorizeAndForeignAccountCannotSupplyRoot() = runBlocking {
        val root = event("root")
        val roots = listOf(root.copy(attachmentName = "file"), root.copy(attachmentMime = "text/plain"),
            root.copy(attachmentSize = 1), root.copy(replyToId = "reply"), root.copy(body = " "),
            root.copy(mucFacts = root.mucFacts!!.copy(payload = MucPayloadState.UNSUPPORTED))) +
            listOf(MucClaimState.UNKNOWN, MucClaimState.INVALID, MucClaimState.VALID, MucClaimState.CONFLICT)
                .map { root.copy(mucFacts = root.mucFacts!!.copy(claim = it)) }
        for (invalid in roots) {
            resetStore()
            installFacts(invalid, event("edit", "root"))
            assertNull(database.messageDao().message(ACCOUNT, "edit")!!.replaceId)
            assertEquals(2, visible().size)
        }
        resetStore()
        database.accountDao().upsert(AccountEntity("foreign", "foreign@example.org", "foreign", null, "example.org", null, null))
        installFacts(root.copy(accountId = "foreign"), event("edit", "root"))
        assertNull(database.messageDao().message(ACCOUNT, "edit")!!.replaceId)
        assertEquals(1, visible().size)
    }

    @Test fun roomOrderingCyclePermutationsFallBackThenFullPositionsConverge() = runBlocking {
        val orders = listOf(listOf("a", "b", "c"), listOf("a", "c", "b"), listOf("b", "a", "c"),
            listOf("b", "c", "a"), listOf("c", "a", "b"), listOf("c", "b", "a"))
        for (order in orders) {
            resetStore()
            installFacts(event("root"))
            order.forEach { installFacts(event(it, "root")) }
            // Live first < middle < last, but archive last < first: no universal dominator.
            val first = order.first(); val last = order.last()
            database.messageDao().insertArchivePosition(ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, 0, last))
            database.messageDao().insertArchivePosition(ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, 1, first))
            store.ingest(event(first, "root"))
            rootBody(null)
            assertEquals(4, visible().size)
            database.messageDao().insertArchivePosition(ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, 2, order[1]))
            store.ingest(event(order[1], "root"))
            rootBody("body-${order[1]}")
        }
    }

    @Test fun quarantinedPinnedPageReplayStillReconcilesFinalPositions() = runBlocking {
        installFacts(event("root"), event("first", "root"), event("last", "root"))
        rootBody("body-last")
        insertAliasFact(TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.STANZA_ID, ROOM,
            "blocked", null, IdentityAliasStatus.QUARANTINED))
        val replay = listOf("last", "first").map { id -> event(id, "root").let {
            it.copy(localMessageId = "replay-$id", aliases = it.aliases +
                TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, ROOM, "blocked"))
        } }
        assertEquals(ArchivePageStatus.APPLIED, page(*replay.toTypedArray()).status)
        assertEquals(3, store.messages(ACCOUNT).size)
        assertEquals(listOf("last", "first"), database.messageDao().archivePositions(ACCOUNT)
            .sortedBy { it.archiveOrdinal }.map { it.messageId })
        rootBody("body-first")
        reopen()
        rootBody("body-first")
    }

    @Test fun positionedMergeRestoresCapAndSchemaRejectsRedundantEqualPosition() = runBlocking {
        for (atCap in listOf(false, true)) for (redundant in listOf(false, true)) {
            resetStore()
            val edit = event("edit", "root")
            val origin = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, "$ROOM/nick", "bridge")
            installFacts(event("root"), edit)
            val others = (1..if (atCap) 127 else 1).map { event("other-$it", "root") }
            installFacts(*others.toTypedArray(), edit.copy(localMessageId = "duplicate", aliases = listOf(origin)))
            if (atCap) rootBody(null) else rootBody("body-edit")
            val dao = database.messageDao()
            others.forEachIndexed { index, incoming -> dao.insertArchivePosition(
                ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, index.toLong(), incoming.localMessageId)) }
            dao.insertArchivePosition(ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, 200, "duplicate"))
            // The redundant equal-position deletion branch is unreachable under the real PK.
            if (redundant) assertTrue(runCatching {
                dao.insertArchivePosition(ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, 200, "edit"))
            }.exceptionOrNull() is android.database.sqlite.SQLiteConstraintException)
            val result = store.ingest(edit.copy(aliases = listOf(origin)))
            assertEquals(1, result.mergedRows)
            assertEquals("edit", result.messageId)
            assertNull(dao.message(ACCOUNT, "duplicate"))
            assertEquals(200L, dao.archivePosition(ACCOUNT, ROOM, ROOM, "edit")!!.archiveOrdinal)
            assertTrue(dao.archivePositions(ACCOUNT, "duplicate").isEmpty())
            assertEquals(if (atCap) 128 else 2, dao.acceptedMucCorrections(ACCOUNT, "root").size)
            rootBody("body-edit")
            reopen()
            rootBody("body-edit")
        }
    }

    @Test fun eventConflictProvenanceSurvivesOwnerMergeAndReopen() = runBlocking {
        for (kind in listOf(IdentityAliasKind.STANZA_ID, IdentityAliasKind.MAM_RESULT)) {
            resetStore()
            val edit = event("edit", "root")
            val origin = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, "$ROOM/nick", "bridge")
            val eventAuthority = if (kind == IdentityAliasKind.STANZA_ID) ROOM else key.aliasAuthority()
            val eventIdentity = TrustedIdentityAlias(kind, eventAuthority, "revoked")
            installFacts(event("root"), edit.copy(aliases = edit.aliases + eventIdentity))
            rootBody("body-edit")
            // Exercise the real storage quarantine writer, not a DAO-only invalidation.
            val collision = event("collision").copy(peerJid = "other@conference.example.org",
                senderJid = "other@conference.example.org/nick", aliases = listOf(eventIdentity))
            assertTrue(store.ingest(collision).identityConflict)
            rootBody(null)
            assertEquals(IdentityAliasStatus.QUARANTINED,
                database.messageDao().identityAlias(ACCOUNT, kind, eventAuthority, "revoked")!!.status)
            reopen()
            rootBody(null)
            resetStore()
            installFacts(event("root"), edit,
                edit.copy(localMessageId = "duplicate", aliases = listOf(origin)))
            rootBody("body-edit")
            // Historical quarantine with two compatible owners; ordinary identity merge remains reachable.
            val authority = if (kind == IdentityAliasKind.STANZA_ID) ROOM else key.aliasAuthority()
            insertAliasFact(TrustedIdentityAliasEntity(ACCOUNT, kind, authority, "blocked", null,
                IdentityAliasStatus.QUARANTINED))
            database.messageDao().insertConflict(IdentityConflictEntity(ACCOUNT, kind, authority,
                "blocked", "duplicate", "edit", 1))
            store.ingest(edit)
            rootBody(null)
            assertTrue(database.messageDao().acceptedMucCorrections(ACCOUNT, "root").isEmpty())
            val result = store.ingest(edit.copy(aliases = listOf(origin)))
            assertEquals(1, result.mergedRows)
            assertNull(database.messageDao().message(ACCOUNT, "duplicate"))
            rootBody(null)
            val conflicts = database.messageDao().conflictsForMessage(ACCOUNT, "edit")
            assertEquals(1, conflicts.size)
            assertEquals(kind, conflicts.single().kind)
            assertEquals("edit", conflicts.single().firstMessageId)
            assertEquals("edit", conflicts.single().secondMessageId)
            assertEquals(IdentityAliasStatus.QUARANTINED,
                database.messageDao().identityAlias(ACCOUNT, kind, authority, "blocked")!!.status)
            reopen()
            store.ingest(edit)
            rootBody(null)
        }
    }

    @Test fun realBackwardPageRebasesUnreturnedPrefixAndUsesFinalMixedChronology() = runBlocking {
        installFacts(event("root"), event("first", "root"), event("last", "root"))
        assertEquals(ArchivePageStatus.APPLIED, page(event("tail")).status)
        val dao = database.messageDao()
        dao.insertArchivePosition(ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, -2, "last"))
        dao.insertArchivePosition(ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, -1, "first"))
        store.ingest(event("first", "root"))
        rootBody("body-first")
        val replay = event("last", "root").copy(sentAtEpochMs = 1234, sentTimeSource = MessageTimeSource.MAM)
        val result = store.applyArchivePage(ArchivePage(key, ArchiveDirection.BEFORE, "tail",
            true, false, true, "last", "last", listOf(ArchivedIncomingMessage("last", replay))))
        assertEquals(ArchivePageStatus.APPLIED, result.status)
        assertEquals(listOf("first" to -2L, "last" to -1L, "tail" to 0L),
            dao.archivePositions(ACCOUNT).sortedBy { it.archiveOrdinal }.map { it.messageId to it.archiveOrdinal })
        assertEquals("last", store.archiveCursor(key)!!.oldestId)
        rootBody("body-last")
        reopen()
        rootBody("body-last")
    }

    @Test fun sameRootPageEvaluatesOnceWithBatchedPositionsAndNoUnrelatedRootReads() = runBlocking {
        database.close()
        val queries = java.util.Collections.synchronizedList(mutableListOf<Pair<String, List<Any?>>>() )
        database = androidx.room.Room.databaseBuilder(context, NemaDatabase::class.java, databaseName)
            .setQueryCallback({ sql, args -> queries.add(sql to args.toList()) }, java.util.concurrent.Executor { it.run() })
            .build()
        store = MessageStore(database)
        installFacts(event("root"), event("foreign-root"), event("foreign-edit", "foreign-root"))
        val foreign = database.messageDao().message(ACCOUNT, "foreign-edit")!!
        for (count in listOf(1, 16, 64)) {
            queries.clear()
            val edits = (1..count).map { event("page-$count-$it", "root") }
            assertEquals(ArchivePageStatus.APPLIED, page(*edits.toTypedArray()).status)
            val captured = synchronized(queries) { queries.toList() }
            val claims = captured.filter { (sql, args) -> sql.contains("mucReplaceId =") && args.lastOrNull() == "root" }
            assertEquals("one final claim evaluation for $count page items: $claims", 1, claims.size)
            val positions = captured.filter { (sql, _) -> sql.contains("archive_message_positions") && sql.contains("messageId IN (") }
            val rootBatches = positions.filter { (_, args) -> "root" in args }
            assertEquals("one batched position read for root: $rootBatches", 1, rootBatches.size)
            assertTrue(captured.none { (_, args) -> "foreign-root" in args || "foreign-edit" in args })
            assertEquals(foreign, database.messageDao().message(ACCOUNT, "foreign-edit"))
            rootBody("body-page-$count-$count")
        }
    }

    @Test fun prefixOnlyRootIsReconciledWhenPageContainsOnlyDifferentRoot() = runBlocking {
        installFacts(event("root"), event("first", "root"), event("last", "root"), event("other-root"))
        assertEquals(ArchivePageStatus.APPLIED, page(event("tail")).status)
        val dao = database.messageDao()
        dao.insertArchivePosition(ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, -2, "last"))
        dao.insertArchivePosition(ArchiveMessagePositionEntity(ACCOUNT, ROOM, ROOM, -1, "first"))
        store.ingest(event("first", "root"))
        rootBody("body-first")
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE prefix_selections (id TEXT)")
        sql.execSQL("CREATE TRIGGER prefix_selection AFTER UPDATE ON messages WHEN NEW.mucCorrectionSelected = 1 BEGIN INSERT INTO prefix_selections VALUES (NEW.localMessageId); END")
        val incoming = event("other-edit", "other-root")
        assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(ArchivePage(key,
            ArchiveDirection.BEFORE, "tail", true, false, true, "other-edit", "other-edit",
            listOf(ArchivedIncomingMessage("other-edit", incoming)))).status)
        assertEquals(listOf("last" to -3L, "first" to -2L, "other-edit" to -1L, "tail" to 0L),
            dao.archivePositions(ACCOUNT).sortedBy { it.archiveOrdinal }.map { it.messageId to it.archiveOrdinal })
        sql.query("SELECT id FROM prefix_selections WHERE id = 'first'").use { assertEquals(1, it.count) }
        rootBody("body-first")
        reopen()
        rootBody("body-first")
    }

    @Test fun candidateAndLinkQueriesUseExistingIndexes() {
        val sql = database.openHelper.writableDatabase
        listOf(
            "SELECT * FROM messages WHERE accountId='a' AND peerJid='r' AND senderJid='s' AND mucReplaceId='t' LIMIT 129" to "index_messages_accountId_peerJid_senderJid_mucReplaceId",
            "SELECT * FROM messages WHERE accountId='a' AND correctionTargetMessageId='r' AND messageKind='GROUPCHAT' LIMIT 129" to "index_messages_accountId_correctionTargetMessageId",
        ).forEach { (query, index) ->
            sql.query("EXPLAIN QUERY PLAN $query").use { c ->
                val detail = buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString()
                assertTrue(detail, detail.contains(index))
            }
        }
    }
}
