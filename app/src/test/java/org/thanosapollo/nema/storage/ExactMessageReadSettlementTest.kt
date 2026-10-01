package org.thanosapollo.nema.storage

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.thanosapollo.nema.thread.MessageKind

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExactMessageReadSettlementTest {
    private lateinit var database: NemaDatabase
    private val readBindings = mutableListOf<List<Any?>>()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), NemaDatabase::class.java,
        ).setQueryCallback({ query, args ->
            if (query.contains("UPDATE messages SET locallyRead = 1") &&
                query.contains("localMessageId IN")) {
                synchronized(readBindings) { readBindings.add(args.toList()) }
            }
        }, Executor { it.run() }).build()
        for (account in listOf(ACCOUNT, OTHER_ACCOUNT)) {
            database.accountDao().upsert(AccountEntity(
                account, "$account@example.org", account, null, "example.org", null, null,
            ))
            for (peer in listOf(PEER, OTHER_PEER)) {
                database.messageDao().insertPeer(PeerEntity(accountId = account, jid = peer))
            }
        }
        // Counts every matched UPDATE, including an assignment of 1 to an existing 1.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TABLE read_updates (accountId TEXT, localMessageId TEXT)",
        )
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER witness_read_update AFTER UPDATE OF locallyRead ON messages
            BEGIN INSERT INTO read_updates VALUES (NEW.accountId, NEW.localMessageId); END
        """)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun daoCountsOnlyNewExactInboundOriginalRows() = runBlocking {
        val dao = database.messageDao()
        val first = row("first", 1)
        val historical = row("historical", 3).copy(unreadEligible = false)
        val new = row("new", 5)
        val excluded = listOf(
            row("hole", 2),
            row("other-peer", 4).copy(peerJid = OTHER_PEER),
            row("outbound", 6).copy(direction = MessageDirection.OUTBOUND),
            row("correction", 7).copy(replaceId = "wire-original"),
            row("first", 1).copy(accountId = OTHER_ACCOUNT),
        )
        database.withTransaction {
            (listOf(first, historical, new) + excluded).forEach { dao.insertMessage(it) }
        }
        val requested = listOf("first", "historical", "other-peer", "outbound", "correction", "missing")
        assertEquals(2, dao.markMessageIdsRead(ACCOUNT, PEER, requested))
        assertEquals(0, dao.markMessageIdsRead(ACCOUNT, PEER, requested))
        assertEquals(1, dao.markMessageIdsRead(ACCOUNT, PEER, requested + "new"))
        assertEquals(0, dao.markMessageIdsRead(ACCOUNT, PEER, emptyList()))
        assertEquals(0, dao.markMessageIdsRead("missing-account", PEER, requested))
        assertEquals(0, dao.markMessageIdsRead(ACCOUNT, "missing-peer", requested))
        assertEquals(0, dao.markMessageIdsRead(ACCOUNT, PEER, listOf("missing")))
        for (message in listOf(first, historical, new)) {
            assertEquals(message.copy(locallyRead = true), dao.message(message.accountId, message.localMessageId))
        }
        for (message in excluded) {
            assertEquals(message, dao.message(message.accountId, message.localMessageId))
        }
        assertEquals(listOf("first", "historical", "new"), updatedIds())
    }

    @Test
    fun storeRepeatedAndMixedSettlementDoesNotUpdateSettledRows() = runBlocking {
        val store = MessageStore(database)
        for (id in listOf("first", "hole", "new")) {
            store.ingest(IncomingMessage(
                accountId = ACCOUNT, localMessageId = id, peerJid = PEER, senderJid = PEER,
                direction = MessageDirection.INBOUND, messageKind = MessageKind.CHAT,
                threadId = null, parentThreadId = null, body = "body", archiveOrdinal = null,
                aliases = emptyList(),
            ))
        }
        store.markMessagesRead(ACCOUNT, PEER, listOf("first", "first", "missing"))
        assertEquals(listOf("first"), updatedIds())
        store.markMessagesRead(ACCOUNT, PEER, listOf("first", "first", "missing"))
        assertEquals(listOf("first"), updatedIds())
        store.markMessagesRead(ACCOUNT, PEER, listOf("first", "new", "new"))
        assertEquals(listOf("first", "new"), updatedIds())
        store.markMessagesRead(ACCOUNT, PEER, emptyList())
        assertEquals(listOf("first", "new"), updatedIds())
        assertFalse(requireNotNull(database.messageDao().message(ACCOUNT, "hole")).locallyRead)
        assertEquals(0L, database.messageDao().peer(ACCOUNT, PEER)?.lastReadLocalSequence)
    }

    @Test
    fun storeDeduplicatesBeforeFiveHundredIdChunksAndRepeatsAreNoOps() = runBlocking {
        val dao = database.messageDao()
        val ids = (0..1000).map { "message-$it" }
        database.withTransaction {
            ids.forEachIndexed { index, id -> dao.insertMessage(row(id, index.toLong() + 1)) }
            dao.insertMessage(row("unobserved", 1002))
        }
        val request = ids.take(500) + ids + ids.takeLast(1)
        val store = MessageStore(database)
        synchronized(readBindings) { readBindings.clear() }
        store.markMessagesRead(ACCOUNT, PEER, request)
        assertEquals(ids.sorted(), updatedIds().sorted())
        assertReadChunks(ids)
        synchronized(readBindings) { readBindings.clear() }
        store.markMessagesRead(ACCOUNT, PEER, request)
        assertEquals(ids.sorted(), updatedIds().sorted())
        assertReadChunks(ids)
        synchronized(readBindings) { readBindings.clear() }
        store.markMessagesRead(ACCOUNT, PEER, emptyList())
        assertEquals(emptyList<List<Any?>>(), synchronized(readBindings) { readBindings.toList() })
        assertFalse(requireNotNull(dao.message(ACCOUNT, "unobserved")).locallyRead)
    }

    private fun assertReadChunks(ids: List<String>) {
        val bindings = synchronized(readBindings) { readBindings.toList() }
        assertEquals(listOf(500, 500, 1), bindings.map { it.size - 2 })
        assertEquals(ids, bindings.flatMap { it.drop(2) })
        bindings.forEach { assertEquals(listOf(ACCOUNT, PEER), it.take(2)) }
    }

    private fun updatedIds(): List<String> = database.openHelper.writableDatabase.query(
        "SELECT localMessageId FROM read_updates ORDER BY rowid",
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun row(id: String, sequence: Long) = MessageEntity(
        accountId = ACCOUNT, localMessageId = id, peerJid = PEER, senderJid = PEER,
        direction = MessageDirection.INBOUND, messageKind = MessageKind.CHAT,
        threadId = null, parentThreadId = null, body = "body", localSequence = sequence,
        archiveOrdinal = null,
    )

    companion object {
        private const val ACCOUNT = "account"
        private const val OTHER_ACCOUNT = "other"
        private const val PEER = "peer@example.org"
        private const val OTHER_PEER = "other@example.org"
    }
}
