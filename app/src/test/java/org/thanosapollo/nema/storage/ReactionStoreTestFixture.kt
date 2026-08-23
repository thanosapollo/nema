package org.thanosapollo.nema.storage

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.reactions.ReactionDisplay

internal data class ReactionStoreState(
    val accounts: List<AccountEntity>,
    val messages: List<MessageEntity>,
    val aliases: List<TrustedIdentityAliasEntity>,
    val reactions: List<MessageReactionEntity>,
    val totalChanges: Long,
)

internal class CountingFixedClock(private val value: Long) : () -> Long {
    var calls = 0
        private set
    override fun invoke(): Long = value.also { calls++ }
    fun resetCount() { calls = 0 }
}

internal open class ReactionStoreTestFixture {
    protected lateinit var context: Context
    protected lateinit var databaseName: String
    protected lateinit var database: NemaDatabase
    protected lateinit var store: MessageStore

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

    protected suspend fun resetStore() {
        database.close()
        context.deleteDatabase(databaseName)
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().upsert(
            AccountEntity(ACCOUNT, SELF, ACCOUNT, null, "example.org", null, null),
        )
        store = MessageStore(database)
    }

    protected suspend fun installFacts(vararg facts: IncomingMessage) = facts.forEach { store.ingest(it) }

    protected suspend fun insertAliasFact(alias: TrustedIdentityAliasEntity) =
        database.messageDao().insertTrustedAlias(alias)

    protected suspend fun state(): ReactionStoreState = database.withTransaction {
        val accounts = database.accountDao().allAccounts()
        val dao = database.messageDao()
        val messages = accounts.flatMap { dao.messages(it.id) }
        val aliases = accounts.flatMap { dao.trustedAliases(it.id) }
        val db = database.openHelper.writableDatabase
        val scopes = db.query(
            "SELECT DISTINCT accountId, peerJid FROM message_reactions ORDER BY accountId, peerJid",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
            }
        }
        val reactions = scopes.flatMap { (account, peer) -> dao.messageReactions(account, peer) }
            .sortedWith(compareBy<MessageReactionEntity>(
                { it.accountId },
                { it.peerJid },
                { it.senderBareJid },
                { it.targetKey },
                { it.localMessageId ?: "" },
                { it.wireTargetId },
                { it.emojis },
                { it.updatedAtMs },
                { it.revision },
            ))
        val totalChanges = db.query("SELECT total_changes()").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        ReactionStoreState(accounts, messages, aliases, reactions, totalChanges)
    }

    protected suspend fun <T> assertReadOnlyState(block: suspend () -> T): T {
        val before = state()
        val result = block()
        assertEquals(before, state())
        return result
    }

    protected suspend fun assertRejectedWithoutMutation(
        clock: CountingFixedClock,
        block: suspend () -> Boolean,
    ): Boolean {
        clock.resetCount()
        return assertReadOnlyState {
            val result = block()
            assertFalse(result)
            assertEquals(0, clock.calls)
            result
        }
    }

    protected fun reaction(localId: String, wire: String, emojis: String, time: Long, revision: Long = 1) =
        MessageReactionEntity(ACCOUNT, PEER, PEER, localId, localId, wire, emojis, time, revision)

    protected fun pending(wire: String, emojis: String, revision: Long = 1) =
        MessageReactionEntity(ACCOUNT, PEER, PEER, "pending:$wire", null, wire, emojis, 10, revision)

    protected fun insertReactionUnchecked(row: MessageReactionEntity) {
        database.openHelper.writableDatabase.execSQL(
            """INSERT OR REPLACE INTO message_reactions
                (accountId, peerJid, senderBareJid, targetKey, localMessageId,
                 wireTargetId, emojis, updatedAtMs, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            arrayOf<Any?>(row.accountId, row.peerJid, row.senderBareJid, row.targetKey, row.localMessageId,
                row.wireTargetId, row.emojis, row.updatedAtMs, row.revision),
        )
    }

    protected fun incoming(localId: String, vararg aliases: TrustedIdentityAlias) = IncomingMessage(
        ACCOUNT, localId, PEER, PEER, MessageDirection.INBOUND, MessageKind.CHAT,
        null, null, "body", null, aliases.toList(),
    )

    protected fun message(value: String) = TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, value)
    protected fun origin(value: String) = TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, value)
    protected fun stanza(value: String) = TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, PEER, value)
    protected fun react(
        targetId: String,
        emojis: List<String>,
        sender: String = PEER,
        receivedAtMs: Long = 1_000L,
        delayedAtMs: Long? = null,
    ) = IncomingReactionApply(ACCOUNT, SELF, PEER, sender, targetId, emojis, receivedAtMs, delayedAtMs)

    protected suspend fun chips(localMessageId: String) =
        store.reactionDisplays(ACCOUNT, PEER, SELF).filter { it.localMessageId == localMessageId }

    protected fun chip(localMessageId: String, emoji: String, sender: String = PEER) =
        ReactionDisplay(localMessageId, emoji, 1, sender == SELF, listOf(sender))

    protected companion object {
        const val ACCOUNT = "account"
        const val OTHER_ACCOUNT = "other-account"
        const val PEER = "peer@example.org"
        const val OTHER_PEER = "other-peer@example.org"
        const val ROOM = "room@conference.example.org"
        const val SELF = "account@example.org"
    }
}
