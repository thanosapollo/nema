package org.thanosapollo.nema.storage

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class MessageReactionStoreTest : ReactionStoreTestFixture() {

    @Test
    fun directReplyAndReactionUseOperationSpecificTargets() = runBlocking {
        store.ingest(incoming("both", origin("origin"), message("message")))
        store.ingest(incoming("origin-only", origin("only-origin")))
        store.ingest(incoming("ambiguous", message("first"), message("second")))
        store.ingest(
            incoming("room", TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, ROOM, "room-message"))
                .copy(peerJid = ROOM, senderJid = "$ROOM/alice", messageKind = MessageKind.GROUPCHAT),
        )

        assertEquals(
            "origin",
            database.messageDao().observeDirectTimeline(ACCOUNT, PEER).first()
                .single { it.localMessageId == "both" }
                .replyReferenceId,
        )
        assertEquals("message", store.reactionWireTarget(ACCOUNT, PEER, "both"))
        assertNull(store.reactionWireTarget(ACCOUNT, PEER, "origin-only"))
        assertNull(store.reactionWireTarget(ACCOUNT, PEER, "ambiguous"))
        assertNull(store.reactionWireTarget(ACCOUNT, "other@example.org", "both"))
        assertNull(store.reactionWireTarget(ACCOUNT, ROOM, "room"))
    }

    @Test
    fun pendingRowsSurviveWhenLocalMessageIdAliasIsAbsent() = runBlocking {
        val dao = database.messageDao()
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("late-origin", listOf("⚠️"))))
        store.ingest(incoming("origin-owner", origin("late-origin")))
        assertEquals(setOf("pending:late-origin" to 1L), dao.messageReactions(ACCOUNT, PEER).map { it.targetKey to it.revision }.toSet())

        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("late-stanza", listOf("⚠️"))))
        store.ingest(incoming("stanza-owner", stanza("late-stanza")))
        assertEquals(
            setOf("pending:late-origin" to 1L, "pending:late-stanza" to 1L),
            dao.messageReactions(ACCOUNT, PEER).map { it.targetKey to it.revision }.toSet(),
        )
    }

    @Test
    fun directReactionMessageIdResolutionIsScopedAndAmbiguityFailsClosed() = runBlocking {
        database.accountDao().upsert(
            AccountEntity(OTHER_ACCOUNT, "other-account@example.org", OTHER_ACCOUNT, null, "example.org", null, null),
        )
        store.ingest(incoming("inbound", message("inbound-id")))
        store.ingest(
            incoming("outbound", TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, SELF, "outbound-id"))
                .copy(senderJid = SELF, direction = MessageDirection.OUTBOUND),
        )
        assertEquals(ReactionApplyOutcome.APPLIED, store.applyIncomingReaction(react("inbound-id", listOf("👍"))))
        assertEquals(
            ReactionApplyOutcome.APPLIED,
            store.applyIncomingReaction(react("outbound-id", listOf("❤️"), sender = SELF)),
        )
        assertEquals(listOf(chip("inbound", "👍")), chips("inbound"))
        assertEquals(listOf(chip("outbound", "❤️", SELF)), chips("outbound"))

        store.ingest(incoming("other-account", message("account-scoped")).copy(accountId = OTHER_ACCOUNT))
        store.ingest(incoming("other-peer", message("peer-scoped")).copy(peerJid = OTHER_PEER, senderJid = OTHER_PEER))
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("account-scoped", listOf("⚠️"))))
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("peer-scoped", listOf("⚠️"))))

        store.ingest(incoming("collision-in", message("collision")))
        store.ingest(
            incoming("collision-out", TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, SELF, "collision"))
                .copy(senderJid = SELF, direction = MessageDirection.OUTBOUND),
        )
        assertEquals(ReactionApplyOutcome.IGNORED, store.applyIncomingReaction(react("collision", listOf("⚠️"))))
        assertTrue(chips("collision-in").isEmpty())
        assertTrue(chips("collision-out").isEmpty())
    }

    @Test
    fun resolvedCorrectionReactionsShareOneOriginalState() = runBlocking {
        store.ingest(incoming("original", message("original-id")).copy(body = "original"))
        val correction = incoming("correction", message("correction-id"))
            .copy(body = "edited", replaceId = "original-id")
        store.ingest(correction)

        assertEquals("original-id", store.reactionWireTarget(ACCOUNT, PEER, "correction"))
        assertEquals(ReactionApplyOutcome.APPLIED, store.applyIncomingReaction(react("correction-id", listOf("👍"))))
        assertEquals(listOf(chip("original", "👍")), chips("original"))
        assertEquals(ReactionApplyOutcome.APPLIED, store.applyIncomingReaction(react("original-id", emptyList())))
        assertTrue(chips("original").isEmpty())

        store.applyIncomingReaction(react("original-id", listOf("❤️"), sender = SELF))
        val previous = store.ownReactionEmojis(ACCOUNT, PEER, "correction", SELF)
        assertEquals(listOf("❤️"), previous)
        store.applyIncomingReaction(react("original-id", listOf("❤️", "👍"), sender = SELF))
        store.applyIncomingReaction(react("original-id", previous, sender = SELF))
        assertEquals(previous, store.ownReactionEmojis(ACCOUNT, PEER, "correction", SELF))
        assertEquals(ReactionMutationOutcome.WRITTEN, database.messageDao().writeReactionFullSet(
            MessageReactionEntity(
                ACCOUNT,
                PEER,
                PEER,
                "correction",
                "correction",
                "correction-id",
                "⚠️",
                5_000L,
            ),
        ))
        store.ingest(correction)
        assertEquals(
            setOf(chip("original", "⚠️"), chip("original", "❤️", SELF)),
            chips("original").toSet(),
        )
        assertTrue(chips("correction").isEmpty())
    }

    @Test
    fun deferredCorrectionReactionSettlesOnOriginal() = runBlocking {
        val correction = incoming("late-correction", message("late-correction-id"))
            .copy(body = "edited", replaceId = "late-original-id")
        store.ingest(correction)
        assertEquals(
            ReactionApplyOutcome.PENDING,
            store.applyIncomingReaction(react("late-correction-id", listOf("👍"))),
        )
        store.ingest(incoming("late-original", message("late-original-id")).copy(body = "original"))

        assertEquals(listOf(chip("late-original", "👍")), chips("late-original"))
        assertTrue(chips("late-correction").isEmpty())
        assertTrue(database.messageDao().messageReactions(ACCOUNT, PEER).none { it.localMessageId == null })
    }

    @Test
    fun multiHopCycleAndMissingCorrectionTargetsRemainInert() = runBlocking {
        store.ingest(incoming("guard-original", message("guard-original-id")).copy(body = "original"))
        store.ingest(
            incoming("guard-middle", message("guard-middle-id"))
                .copy(body = "middle", replaceId = "guard-original-id"),
        )
        val topIncoming = incoming("guard-top", message("guard-top-id"))
            .copy(body = "top", replaceId = "guard-middle-id")
        store.ingest(topIncoming)
        val dao = database.messageDao()
        val top = requireNotNull(dao.message(ACCOUNT, "guard-top"))
        val middle = requireNotNull(dao.message(ACCOUNT, "guard-middle"))
        dao.updateMessage(top.copy(correctionTargetMessageId = middle.localMessageId))
        val attached = MessageReactionEntity(
            ACCOUNT, PEER, PEER, "guard-top", "guard-top", "guard-top-id", "⚠️", 5_000L,
        )
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.writeReactionFullSet(attached))

        assertNull(store.reactionWireTarget(ACCOUNT, PEER, "guard-top"))
        assertTrue(store.ownReactionEmojis(ACCOUNT, PEER, "guard-top", PEER).isEmpty())
        store.ingest(topIncoming)
        assertEquals(listOf(attached), dao.messageReactionsForMessage(ACCOUNT, PEER, "guard-top"))
        assertEquals(
            ReactionApplyOutcome.PENDING,
            store.applyIncomingReaction(react("guard-top-id", listOf("👍"))),
        )
        assertEquals(listOf(attached), dao.messageReactionsForMessage(ACCOUNT, PEER, "guard-top"))

        dao.updateMessage(middle.copy(correctionTargetMessageId = top.localMessageId))
        assertNull(store.reactionWireTarget(ACCOUNT, PEER, "guard-top"))
        dao.updateMessage(top.copy(correctionTargetMessageId = "missing"))
        assertNull(store.reactionWireTarget(ACCOUNT, PEER, "guard-top"))
        assertEquals(listOf(attached), dao.messageReactionsForMessage(ACCOUNT, PEER, "guard-top"))
    }

    @Test
    fun corruptOriginalTargetNeverAuthorizesCorrectionReactionState() = runBlocking {
        val originalIncoming = incoming("corrupt-original", message("corrupt-original-id")).copy(body = "original")
        val correctionIncoming = incoming("corrupt-correction", message("corrupt-correction-id"))
            .copy(body = "edited", replaceId = "corrupt-original-id")
        store.ingest(originalIncoming)
        store.ingest(correctionIncoming)
        val dao = database.messageDao()
        val original = requireNotNull(dao.message(ACCOUNT, "corrupt-original"))
        val correction = requireNotNull(dao.message(ACCOUNT, "corrupt-correction"))
        dao.updateMessage(original.copy(correctionTargetMessageId = "corrupt-link"))
        dao.updateMessage(correction.copy(correctionTargetMessageId = null))
        val attached = MessageReactionEntity(
            ACCOUNT, PEER, PEER, "corrupt-correction", "corrupt-correction",
            "corrupt-correction-id", "⚠️", 5_000L,
        )
        val pending = MessageReactionEntity(
            ACCOUNT, PEER, PEER, "pending:corrupt-correction-id", null,
            "corrupt-correction-id", "👍", 1_000L,
        )
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.writeReactionFullSet(attached))
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.writeReactionFullSet(pending))
        val before = dao.messageReactions(ACCOUNT, PEER).toSet()

        assertNull(store.reactionWireTarget(ACCOUNT, PEER, "corrupt-correction"))
        assertTrue(store.ownReactionEmojis(ACCOUNT, PEER, "corrupt-correction", PEER).isEmpty())
        assertEquals(
            ReactionApplyOutcome.PENDING,
            store.applyIncomingReaction(react("corrupt-correction-id", listOf("👍"))),
        )
        store.ingest(correctionIncoming)
        store.ingest(originalIncoming)

        assertNull(dao.message(ACCOUNT, "corrupt-correction")?.correctionTargetMessageId)
        assertEquals(before, dao.messageReactions(ACCOUNT, PEER).toSet())
    }

    @Test
    fun applyPendingAttachAndMergeKeepNewestTrustedChatSet() = runBlocking {
        store.ingest(incoming("local-1", message("message-1"), origin("origin-1"), stanza("stanza-1")))
        assertEquals(ReactionApplyOutcome.IGNORED, store.applyIncomingReaction(react("origin-1", listOf("⚠️"))))
        assertEquals(ReactionApplyOutcome.IGNORED, store.applyIncomingReaction(react("stanza-1", listOf("⚠️"))))
        assertEquals(ReactionApplyOutcome.APPLIED, store.applyIncomingReaction(react("message-1", listOf("👍"))))
        assertEquals(listOf(chip("local-1", "👍")), chips("local-1"))
        assertEquals(ReactionApplyOutcome.IGNORED, store.applyIncomingReaction(react("message-1", listOf("👍"), sender = "other@example.org")))
        store.applyIncomingReaction(react("message-1", listOf("❤️"), receivedAtMs = 2_000L))
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("clear", emptyList(), receivedAtMs = 3_000L)))
        store.ingest(incoming("local-1", message("message-1"), message("clear")))
        assertEquals(listOf(chip("local-1", "❤️")), chips("local-1"))
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("p-old", listOf("👍"), receivedAtMs = 1_000L)))
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("p-new", emptyList(), receivedAtMs = 2_000L)))
        store.ingest(incoming("local-3", message("p-old"), message("p-new")))
        assertTrue(chips("local-3").isEmpty())
        store.ingest(incoming("keep", message("keep-a")))
        store.ingest(incoming("drop", message("drop-b")))
        store.applyIncomingReaction(react("drop-b", listOf("🙏"), receivedAtMs = 5_000L))
        store.applyIncomingReaction(react("keep-a", listOf("😂"), receivedAtMs = 1_000L))
        store.ingest(incoming("keep", message("keep-a"), message("drop-b")))
        assertEquals(listOf(chip("keep", "🙏")), chips("keep"))
        assertTrue(chips("drop").isEmpty())
    }

    @Test
    fun pendingRowsSurviveMultipleLocalMessageIdAliasRowsIncludingDuplicateValues() = runBlocking {
        val dao = database.messageDao()
        listOf(pending("first", "👍", 3), pending("second", "❤️", 4), pending("cross-kind", "⚠️", 5)).forEach(::insertReactionUnchecked)
        store.ingest(incoming("multiple", message("first"), message("second"), origin("cross-kind")))
        assertEquals(
            setOf("pending:first" to 3L, "pending:second" to 4L, "pending:cross-kind" to 5L),
            dao.messageReactions(ACCOUNT, PEER).map { it.targetKey to it.revision }.toSet(),
        )
        listOf(pending("duplicate", "🙏", 6), pending("duplicate-cross-kind", "😂", 7)).forEach(::insertReactionUnchecked)
        store.ingest(incoming("duplicate", message("duplicate"),
            TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, SELF, "duplicate"), origin("duplicate-cross-kind")))
        assertEquals(setOf("pending:first" to 3L, "pending:second" to 4L, "pending:cross-kind" to 5L,
            "pending:duplicate" to 6L, "pending:duplicate-cross-kind" to 7L), dao.messageReactions(ACCOUNT, PEER).map { it.targetKey to it.revision }.toSet())
    }

    @Test
    fun semanticFullSetValidatesRowsFreshnessAndRevision() = runBlocking {
        store.ingest(incoming("owner", message("wire")))
        val dao = database.messageDao()
        val row = reaction("owner", "wire", "👍", 10)
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.writeReactionFullSet(row))
        assertEquals(1L, dao.messageReaction(ACCOUNT, PEER, PEER, "owner")?.revision)
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.writeReactionFullSet(row))
        assertEquals(2L, dao.messageReaction(ACCOUNT, PEER, PEER, "owner")?.revision)
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.writeReactionFullSet(row.copy(emojis = "")))
        assertEquals(3L, dao.messageReaction(ACCOUNT, PEER, PEER, "owner")?.revision)
        assertEquals(ReactionMutationOutcome.SUPERSEDED, dao.writeReactionFullSet(row.copy(updatedAtMs = 9), true))
        insertReactionUnchecked(row.copy(revision = Long.MAX_VALUE - 1))
        assertThrows(IllegalStateException::class.java) { runBlocking { dao.writeReactionFullSet(row) } }
        assertEquals(Long.MAX_VALUE - 1, dao.messageReaction(ACCOUNT, PEER, PEER, "owner")?.revision)
        listOf(
            row.copy(accountId = ""), row.copy(peerJid = ""), row.copy(senderBareJid = ""), row.copy(wireTargetId = ""),
            row.copy(targetKey = "wrong"), row.copy(localMessageId = "missing"), row.copy(emojis = "👍\u001F"),
            row.copy(revision = -1), row.copy(revision = 0), row.copy(revision = Long.MAX_VALUE),
        ).forEach { bad ->
            assertThrows(IllegalStateException::class.java) { runBlocking { dao.writeReactionFullSet(bad) } }
        }
    }

    @Test
    fun exactMoveHasFiniteOutcomesAndRollsBackFailures() = runBlocking {
        store.ingest(incoming("from", message("from-wire")))
        store.ingest(incoming("to", message("to-wire")))
        val dao = database.messageDao()
        val source = reaction("from", "from-wire", "👍", 20)
        val destination = reaction("to", "to-wire", "👍", 20)
        insertReactionUnchecked(source)
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.moveMessageReaction(source, destination))
        assertNull(dao.messageReaction(ACCOUNT, PEER, PEER, "from"))
        assertEquals(1L, dao.messageReaction(ACCOUNT, PEER, PEER, "to")?.revision)
        insertReactionUnchecked(source)
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.moveMessageReaction(source, destination))
        assertEquals(2L, dao.messageReaction(ACCOUNT, PEER, PEER, "to")?.revision)
        insertReactionUnchecked(source)
        insertReactionUnchecked(destination.copy(updatedAtMs = 30, revision = 4))
        assertEquals(ReactionMutationOutcome.SUPERSEDED, dao.moveMessageReaction(source, destination))
        assertNull(dao.messageReaction(ACCOUNT, PEER, PEER, "from"))
        assertEquals(4L, dao.messageReaction(ACCOUNT, PEER, PEER, "to")?.revision)
        insertReactionUnchecked(source)
        insertReactionUnchecked(destination.copy(revision = Long.MAX_VALUE - 1))
        assertThrows(IllegalStateException::class.java) { runBlocking { dao.moveMessageReaction(source, destination) } }
        assertEquals(source, dao.messageReaction(ACCOUNT, PEER, PEER, "from"))
        assertThrows(IllegalStateException::class.java) { runBlocking { dao.moveMessageReaction(source, destination.copy(targetKey = "bad")) } }
        insertReactionUnchecked(destination.copy(updatedAtMs = 30, revision = 4))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_reaction_retire BEFORE DELETE ON message_reactions BEGIN SELECT RAISE(ABORT, 'fault'); END",
        )
        assertThrows(Exception::class.java) { runBlocking { dao.moveMessageReaction(source, destination.copy(updatedAtMs = 40)) } }
        assertEquals(source, dao.messageReaction(ACCOUNT, PEER, PEER, "from"))
        assertEquals(4L, dao.messageReaction(ACCOUNT, PEER, PEER, "to")?.revision)
    }

    @Test
    fun pendingClassificationHonorsMessageIdAuthorityAndRetirement() = runBlocking {
        store.ingest(incoming("owner", message("accepted"), origin("unsupported"), stanza("accepted")))
        store.ingest(incoming("other", message("owned-elsewhere")))
        val dao = database.messageDao()
        val accepted = pending("accepted", "👍")
        val unsupported = pending("unsupported", "⚠️")
        val owned = pending("owned-elsewhere", "❤️")
        val unrelated = pending("unrelated", "🙏")
        listOf(accepted, unsupported, owned, unrelated).forEach(::insertReactionUnchecked)
        val selected = requireNotNull(dao.classifyPendingReactions(ACCOUNT, PEER, "owner"))
        assertEquals(listOf(accepted), selected.accepted)
        assertEquals(listOf(unsupported), selected.unsupported)
        assertEquals(1, dao.retireUnsupportedPendingReaction(unsupported))
        assertEquals(setOf(accepted, owned, unrelated), dao.messageReactions(ACCOUNT, PEER).toSet())
        store.ingest(incoming("global-collision", TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, SELF, "accepted")))
        assertNull(dao.classifyPendingReactions(ACCOUNT, PEER, "owner"))
        store.ingest(incoming("ambiguous", message("one"), message("two")))
        assertNull(dao.classifyPendingReactions(ACCOUNT, PEER, "ambiguous"))
        insertReactionUnchecked(pending("accepted", "👍", revision = 0))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { dao.classifyPendingReactions(ACCOUNT, PEER, "owner") }
        }
        Unit
    }

}
