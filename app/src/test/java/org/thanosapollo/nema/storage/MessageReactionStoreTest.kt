package org.thanosapollo.nema.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun applyPendingAttachAndMergeKeepNewestTrustedChatSet() = runBlocking {
        store.ingest(incoming("local-1", origin("origin-1")))
        assertEquals(ReactionApplyOutcome.APPLIED, store.applyIncomingReaction(react("origin-1", listOf("👍"))))
        assertEquals(listOf(chip("local-1", "👍")), chips("local-1"))
        assertEquals(ReactionApplyOutcome.IGNORED, store.applyIncomingReaction(react("origin-1", listOf("👍"), sender = "other@example.org")))
        store.applyIncomingReaction(react("origin-1", listOf("❤️"), receivedAtMs = 2_000L))
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("stanza-1", emptyList(), receivedAtMs = 3_000L)))
        store.ingest(incoming("local-1", origin("origin-1"), stanza("stanza-1")))
        assertTrue(chips("local-1").isEmpty())
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("p-old", listOf("👍"), receivedAtMs = 1_000L)))
        assertEquals(ReactionApplyOutcome.PENDING, store.applyIncomingReaction(react("p-new", emptyList(), receivedAtMs = 2_000L)))
        store.ingest(incoming("local-3", origin("p-old"), stanza("p-new")))
        assertTrue(chips("local-3").isEmpty())
        store.ingest(incoming("keep", origin("keep-a")))
        store.ingest(incoming("drop", origin("drop-b")))
        store.applyIncomingReaction(react("drop-b", listOf("🙏"), receivedAtMs = 5_000L))
        store.applyIncomingReaction(react("keep-a", listOf("😂"), receivedAtMs = 1_000L))
        store.ingest(incoming("keep", origin("keep-a"), origin("drop-b")))
        assertEquals(listOf(chip("keep", "🙏")), chips("keep"))
        assertTrue(chips("drop").isEmpty())
    }

    private fun incoming(localId: String, vararg aliases: TrustedIdentityAlias) = IncomingMessage(
        ACCOUNT, localId, PEER, PEER, MessageDirection.INBOUND, MessageKind.CHAT,
        null, null, "body", null, aliases.toList(),
    )

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
    private fun chip(localMessageId: String, emoji: String) =
        ReactionDisplay(localMessageId, emoji, 1, false, listOf(PEER))

    companion object {
        private const val ACCOUNT = "account"
        private const val PEER = "peer@example.org"
        private const val SELF = "account@example.org"
    }
}
