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
