package org.thanosapollo.nema.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.reactions.ReactionDisplay

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessageReactionStoreTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private lateinit var store: MessageStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "message-reactions-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().upsert(
            AccountEntity(ACCOUNT, SELF, ACCOUNT, null, "example.org", null, null),
        )
        store = MessageStore(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
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
    fun pendingForbiddenAliasesAreDiscardedBeforeTheirValueCanAttachElsewhere() = runBlocking {
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("late-origin", listOf("⚠️"))))
        store.ingest(incoming("origin-owner", origin("late-origin")))
        assertTrue(database.messageDao().messageReactions(ACCOUNT, PEER).isEmpty())
        store.ingest(incoming("later-message", message("late-origin")))
        assertTrue(chips("later-message").isEmpty())

        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("late-stanza", listOf("⚠️"))))
        store.ingest(incoming("stanza-owner", stanza("late-stanza")))
        assertTrue(database.messageDao().messageReactions(ACCOUNT, PEER).isEmpty())
        store.ingest(incoming("later-stanza-message", message("late-stanza")))
        assertTrue(chips("later-stanza-message").isEmpty())
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
        database.messageDao().upsertMessageReaction(
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
        )
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
        dao.upsertMessageReaction(attached)

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
        dao.upsertMessageReaction(attached)
        dao.upsertMessageReaction(pending)
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
        assertTrue(chips("local-1").isEmpty())
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
        raw(row.copy(revision = Long.MAX_VALUE - 1))
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
        raw(source)
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.moveMessageReaction(source, destination))
        assertNull(dao.messageReaction(ACCOUNT, PEER, PEER, "from"))
        assertEquals(1L, dao.messageReaction(ACCOUNT, PEER, PEER, "to")?.revision)
        raw(source)
        assertEquals(ReactionMutationOutcome.WRITTEN, dao.moveMessageReaction(source, destination))
        assertEquals(2L, dao.messageReaction(ACCOUNT, PEER, PEER, "to")?.revision)
        raw(source)
        raw(destination.copy(updatedAtMs = 30, revision = 4))
        assertEquals(ReactionMutationOutcome.SUPERSEDED, dao.moveMessageReaction(source, destination))
        assertNull(dao.messageReaction(ACCOUNT, PEER, PEER, "from"))
        assertEquals(4L, dao.messageReaction(ACCOUNT, PEER, PEER, "to")?.revision)
        raw(source)
        raw(destination.copy(revision = Long.MAX_VALUE - 1))
        assertThrows(IllegalStateException::class.java) { runBlocking { dao.moveMessageReaction(source, destination) } }
        assertEquals(source, dao.messageReaction(ACCOUNT, PEER, PEER, "from"))
        assertThrows(IllegalStateException::class.java) { runBlocking { dao.moveMessageReaction(source, destination.copy(targetKey = "bad")) } }
        raw(destination.copy(updatedAtMs = 30, revision = 4))
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
        listOf(accepted, unsupported, owned, unrelated).forEach(::raw)
        val selected = requireNotNull(dao.classifyPendingReactions(ACCOUNT, PEER, "owner"))
        assertEquals(listOf(accepted), selected.accepted)
        assertEquals(listOf(unsupported), selected.unsupported)
        assertEquals(1, dao.retireUnsupportedPendingReaction(unsupported))
        assertEquals(setOf(accepted, owned, unrelated), dao.messageReactions(ACCOUNT, PEER).toSet())
        store.ingest(incoming("global-collision", TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, SELF, "accepted")))
        assertNull(dao.classifyPendingReactions(ACCOUNT, PEER, "owner"))
        store.ingest(incoming("ambiguous", message("one"), message("two")))
        assertNull(dao.classifyPendingReactions(ACCOUNT, PEER, "ambiguous"))
        raw(pending("accepted", "👍", revision = 0))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { dao.classifyPendingReactions(ACCOUNT, PEER, "owner") }
        }
        Unit
    }

    private fun reaction(localId: String, wire: String, emojis: String, time: Long, revision: Long = 1) =
        MessageReactionEntity(ACCOUNT, PEER, PEER, localId, localId, wire, emojis, time, revision)
    private fun pending(wire: String, emojis: String, revision: Long = 1) =
        MessageReactionEntity(ACCOUNT, PEER, PEER, "pending:$wire", null, wire, emojis, 10, revision)
    private fun raw(row: MessageReactionEntity) = runBlocking { database.messageDao().upsertMessageReaction(row) }

    private fun incoming(localId: String, vararg aliases: TrustedIdentityAlias) = IncomingMessage(
        ACCOUNT, localId, PEER, PEER, MessageDirection.INBOUND, MessageKind.CHAT,
        null, null, "body", null, aliases.toList(),
    )

    private fun message(value: String) = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, value)
    private fun origin(value: String) = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, value)
    private fun stanza(value: String) = TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, PEER, value)
    private fun react(
        targetId: String,
        emojis: List<String>,
        sender: String = PEER,
        receivedAtMs: Long = 1_000L,
        delayedAtMs: Long? = null,
    ) = IncomingReactionApply(ACCOUNT, SELF, PEER, sender, targetId, emojis, receivedAtMs, delayedAtMs)
    private suspend fun chips(localMessageId: String) =
        store.reactionDisplays(ACCOUNT, PEER, SELF).filter { it.localMessageId == localMessageId }
    private fun chip(localMessageId: String, emoji: String, sender: String = PEER) =
        ReactionDisplay(localMessageId, emoji, 1, sender == SELF, listOf(sender))

    companion object {
        private const val ACCOUNT = "account"
        private const val OTHER_ACCOUNT = "other-account"
        private const val PEER = "peer@example.org"
        private const val OTHER_PEER = "other-peer@example.org"
        private const val ROOM = "room@conference.example.org"
        private const val SELF = "account@example.org"
    }
}
