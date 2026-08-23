package org.thanosapollo.nema.storage

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun fixtureOraclesPreserveCompleteStateAndDetectMutants() = runBlocking {
        val bootstrap = state()
        assertEquals(listOf(AccountEntity(ACCOUNT, SELF, ACCOUNT, null, "example.org", null, null)), bootstrap.accounts)
        assertTrue(bootstrap.messages.isEmpty())
        assertTrue(bootstrap.aliases.isEmpty())
        assertTrue(bootstrap.reactions.isEmpty())
        assertEquals(bootstrap, state())

        val original = incoming("original", message("original-id")).copy(body = "original")
        val correction = incoming("correction", message("correction-id"))
            .copy(body = "edited", replaceId = "original-id")
        installFacts(original, correction)
        val quarantined = TrustedIdentityAliasEntity(
            ACCOUNT, IdentityAliasKind.STANZA_ID, PEER, "bad", null, IdentityAliasStatus.QUARANTINED,
        )
        insertAliasFact(quarantined)
        val malformed = pending("pending", "", revision = 0)
        insertReactionUnchecked(malformed)
        val captured = state()
        val dao = database.messageDao()
        assertEquals(listOf(dao.message(ACCOUNT, "original"), dao.message(ACCOUNT, "correction")), captured.messages)
        assertEquals(listOf("original", "edited"), captured.messages.map { it.body })
        assertEquals("original", captured.messages.single { it.localMessageId == "correction" }.correctionTargetMessageId)
        assertEquals(listOf(
            TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.MESSAGE_ID, PEER, "correction-id", "correction", IdentityAliasStatus.TRUSTED),
            TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.MESSAGE_ID, PEER, "original-id", "original", IdentityAliasStatus.TRUSTED),
            quarantined,
        ), captured.aliases)
        assertEquals(listOf(malformed), captured.reactions)
        assertEquals(captured, state())
        assertEquals("read-result", assertReadOnlyState { "read-result" })

        insertReactionUnchecked(pending("changed", "👍"))
        assertTrue(state().totalChanges > captured.totalChanges)
        resetStore()

        val clock = CountingFixedClock(42L)
        assertEquals(42L, clock())
        assertEquals(1, clock.calls)
        clock.resetCount()
        assertEquals(0, clock.calls)
        assertFalse(assertRejectedWithoutMutation(clock) { false })

        assertThrows(AssertionError::class.java) {
            runBlocking { assertRejectedWithoutMutation(clock) { true } }
        }
        resetStore()
        assertThrows(AssertionError::class.java) {
            runBlocking { assertRejectedWithoutMutation(clock) { insertReactionUnchecked(pending("mutant", "👍")); false } }
        }
        resetStore()
        assertThrows(AssertionError::class.java) {
            runBlocking { assertRejectedWithoutMutation(clock) { clock(); false } }
        }
        resetStore()
        assertThrows(AssertionError::class.java) {
            runBlocking { assertReadOnlyState { insertReactionUnchecked(pending("hidden", "👍")) } }
        }
        resetStore()
        assertEquals(bootstrap, state())
    }

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
    fun directReactionResolutionRequiresCanonicalCorrectionAncestry() = runBlocking {
        suspend fun resolve(id: String = "selected") = assertReadOnlyState {
            store.resolveDirectReactionTarget(ACCOUNT, PEER, id)
        }
        suspend fun installPair() {
            resetStore()
            installFacts(
                incoming("original", message("original-wire")).copy(body = "original"),
                incoming("selected", message("selected-wire")).copy(body = "edited", replaceId = "original-wire"),
            )
        }

        installPair()
        assertEquals(DirectReactionTarget(ACCOUNT, PEER, "original", "original-wire"), resolve("original"))
        assertEquals(DirectReactionTarget(ACCOUNT, PEER, "original", "original-wire"), resolve())
        val dao = database.messageDao()
        val selected = requireNotNull(dao.message(ACCOUNT, "selected"))
        val original = requireNotNull(dao.message(ACCOUNT, "original"))
        dao.updateMessage(selected.copy(correctionTargetMessageId = null))
        assertNull("replaceId without correction target", resolve())
        dao.updateMessage(selected.copy(replaceId = null, correctionTargetMessageId = original.localMessageId))
        assertNull("correction target without replaceId", resolve())

        resetStore()
        installFacts(
            incoming("original", message("original-wire")),
            incoming("middle", message("middle-wire")).copy(replaceId = "original-wire"),
            incoming("selected", message("selected-wire")).copy(replaceId = "middle-wire"),
        )
        assertNull("multi-hop correction", resolve())
        installPair()
        val canonical = requireNotNull(database.messageDao().message(ACCOUNT, "original"))
        database.messageDao().updateMessage(canonical.copy(replaceId = "selected-wire", correctionTargetMessageId = "selected"))
        assertNull("correction-marked canonical", resolve())
    }

    @Test
    fun directReactionResolutionDelegatesEveryCorrectionAuthorityDimension() = runBlocking {
        data class AuthorityCase(
            val name: String,
            val mutate: (MessageEntity, MessageEntity) -> Pair<MessageEntity, MessageEntity>,
        )
        val cases = listOf(
            AuthorityCase("sender") { selected, target -> selected.copy(senderJid = SELF) to target },
            AuthorityCase("direction") { selected, target -> selected.copy(direction = MessageDirection.OUTBOUND) to target },
            AuthorityCase("thread") { selected, target -> selected.copy(threadId = null) to target },
            AuthorityCase("parent thread") { selected, target -> selected.copy(parentThreadId = null) to target },
            AuthorityCase("selected blank body") { selected, target -> selected.copy(body = " ") to target },
            AuthorityCase("canonical blank body") { selected, target -> selected to target.copy(body = " ") },
            AuthorityCase("attachment URL") { selected, target -> selected.copy(attachmentUrl = "https://example.org/a") to target },
            AuthorityCase("attachment name") { selected, target -> selected.copy(attachmentName = "a") to target },
            AuthorityCase("attachment size") { selected, target -> selected.copy(attachmentSize = 1) to target },
            AuthorityCase("attachment MIME type") { selected, target -> selected.copy(attachmentMime = "text/plain") to target },
            AuthorityCase("reply ID") { selected, target -> selected.copy(replyToId = "reply") to target },
            AuthorityCase("reply JID") { selected, target -> selected.copy(replyToId = "reply", replyToJid = PEER) to target },
            AuthorityCase("reply fallback body") { selected, target -> selected.copy(replyToId = "reply", replyFallbackBody = "quoted") to target },
            AuthorityCase("same local ID") { selected, _ -> selected.copy(
                replaceId = "selected-wire", correctionTargetMessageId = selected.localMessageId,
            ) to selected },
            AuthorityCase("canonical wrong peer") { selected, target -> selected to target.copy(peerJid = OTHER_PEER) },
            AuthorityCase("canonical non-CHAT") { selected, target -> selected to target.copy(messageKind = MessageKind.GROUPCHAT) },
            AuthorityCase("canonical target attachment metadata") { selected, target -> selected to target.copy(attachmentUrl = "https://example.org/a") },
            AuthorityCase("canonical target reply metadata") { selected, target -> selected to target.copy(replyToId = "reply") },
        )

        for (case in cases) {
            resetStore()
            installFacts(
                incoming("target", message("target-wire")).copy(body = "original", threadId = "thread", parentThreadId = "parent"),
                incoming("selected", message("selected-wire")).copy(
                    body = "edited", threadId = "thread", parentThreadId = "parent", replaceId = "target-wire",
                ),
            )
            val dao = database.messageDao()
            dao.insertPeer(PeerEntity(ACCOUNT, OTHER_PEER))
            dao.insertThread(MessageThreadEntity(ACCOUNT, OTHER_PEER, MessageKind.CHAT, "parent", null))
            dao.insertThread(MessageThreadEntity(ACCOUNT, OTHER_PEER, MessageKind.CHAT, "thread", "parent"))
            dao.insertThread(MessageThreadEntity(ACCOUNT, PEER, MessageKind.GROUPCHAT, "parent", null))
            dao.insertThread(MessageThreadEntity(ACCOUNT, PEER, MessageKind.GROUPCHAT, "thread", "parent"))
            val selected = requireNotNull(dao.message(ACCOUNT, "selected"))
            val target = requireNotNull(dao.message(ACCOUNT, "target"))
            val (changedSelected, changedTarget) = case.mutate(selected, target)
            if (changedSelected.localMessageId != changedTarget.localMessageId) dao.updateMessage(changedTarget)
            dao.updateMessage(changedSelected)
            assertNull(case.name, assertReadOnlyState { store.resolveDirectReactionTarget(ACCOUNT, PEER, "selected") })
        }
    }

    @Test
    fun directReactionResolutionRejectsNonCorrectionAuthorityFailures() = runBlocking {
        suspend fun rejected(label: String) = assertNull(label, assertReadOnlyState {
            store.resolveDirectReactionTarget(ACCOUNT, PEER, "selected")
        })
        suspend fun baseline(vararg aliases: TrustedIdentityAlias) {
            resetStore()
            installFacts(incoming("selected", *aliases))
        }

        baseline(message("wire"))
        assertNull("wrong account", assertReadOnlyState { store.resolveDirectReactionTarget("missing", PEER, "selected") })
        assertNull("wrong peer", assertReadOnlyState { store.resolveDirectReactionTarget(ACCOUNT, OTHER_PEER, "selected") })
        assertNull("missing selected message", assertReadOnlyState { store.resolveDirectReactionTarget(ACCOUNT, PEER, "missing") })
        database.messageDao().updateMessage(requireNotNull(database.messageDao().message(ACCOUNT, "selected"))
            .copy(messageKind = MessageKind.GROUPCHAT))
        rejected("selected non-CHAT")

        val aliasCases = listOf(
            "missing MESSAGE_ID" to emptyArray(), "origin-only" to arrayOf(origin("origin")),
            "stanza-only" to arrayOf(stanza("stanza")),
            "MAM-only" to arrayOf(TrustedIdentityAlias(IdentityAliasKind.MAM_RESULT, PEER, "mam")),
            "duplicate MESSAGE_ID" to arrayOf(message("one"), message("two")),
        )
        for ((label, aliases) in aliasCases) { baseline(*aliases); rejected(label) }
        baseline()
        insertAliasFact(TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.MESSAGE_ID, PEER, "wire", null, IdentityAliasStatus.QUARANTINED))
        rejected("quarantined MESSAGE_ID")
        baseline(message("wire"))
        insertAliasFact(TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.MESSAGE_ID, SELF, "wire", "selected", IdentityAliasStatus.TRUSTED))
        rejected("duplicate wrong-authority MESSAGE_ID")
        baseline()
        insertAliasFact(TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.MESSAGE_ID, "wrong", "wire", "selected", IdentityAliasStatus.TRUSTED))
        rejected("wrong-authority MESSAGE_ID")
        baseline()
        insertAliasFact(TrustedIdentityAliasEntity(ACCOUNT, IdentityAliasKind.MESSAGE_ID, PEER, "", "selected", IdentityAliasStatus.TRUSTED))
        rejected("blank MESSAGE_ID")

        suspend fun correctionLink(status: IdentityAliasStatus, messageId: String?) {
            resetStore()
            installFacts(
                incoming("canonical", message("target-wire"), message("canonical-wire")).copy(body = "original"),
                incoming("selected", message("selected-wire")).copy(body = "edited", replaceId = "target-wire"),
                incoming("other", message("other-wire")),
            )
            database.openHelper.writableDatabase.execSQL(
                "UPDATE trusted_identity_aliases SET messageId = ?, status = ? WHERE value = 'target-wire'",
                arrayOf(messageId, status.name),
            )
        }
        correctionLink(IdentityAliasStatus.QUARANTINED, null)
        rejected("selected correction trusted MESSAGE_ID link absent")
        correctionLink(IdentityAliasStatus.TRUSTED, "other")
        rejected("link resolves to different local message than correctionTargetMessageId")
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
