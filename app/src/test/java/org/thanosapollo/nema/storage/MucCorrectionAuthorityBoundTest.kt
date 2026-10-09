package org.thanosapollo.nema.storage

import android.app.Application
import androidx.room.withTransaction
import androidx.room.Room
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.MessageKind

/** Real Room SQL: unrelated owner/kind history must not become a residual prefix scan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
internal class MucCorrectionAuthorityBoundTest : ReactionStoreTestFixture() {
    @Test fun mixedHistoryAuthoritySearchMustConstrainOwnerAndKindTogether() = runBlocking {
        val failures = mutableListOf<String>()
        for (distribution in listOf("mixed", "owner", "event")) for (size in listOf(100, 10000)) {
            database.close()
            context.deleteDatabase(databaseName)
            val queries = mutableListOf<Pair<String, List<Any?>>>()
            database = Room.databaseBuilder(context, NemaDatabase::class.java, databaseName)
                .setQueryCallback({ query, args -> synchronized(queries) { queries.add(query to args.toList()) } }, Executor { it.run() })
                .build()
            database.accountDao().upsert(AccountEntity(ACCOUNT, SELF, ACCOUNT, null, "example.org", null, null))
            store = MessageStore(database)
            for (id in listOf("root", "a-other", "z-other")) {
                store.ingest(IncomingMessage(ACCOUNT, id, ROOM, "$ROOM/nick", MessageDirection.INBOUND,
                    MessageKind.GROUPCHAT, null, null, id, null, emptyList()))
            }
            val sql = database.openHelper.writableDatabase
            database.withTransaction {
                val insert = sql.compileStatement("INSERT INTO identity_conflicts VALUES (?, ?, ?, ?, ?, ?, ?)")
                try {
                    repeat(size) { i ->
                        listOf(Triple("MESSAGE_ID", "root", "z-other"), Triple("MESSAGE_ID", "a-other", "root"),
                            Triple("STANZA_ID", "a-other", "z-other"), Triple("MAM_RESULT", "a-other", "z-other"))
                            .filter { distribution == "mixed" || (it.first == "MESSAGE_ID") == (distribution == "owner") }
                            .forEach { (kind, first, second) ->
                                insert.bindString(1, ACCOUNT)
                                insert.bindString(2, kind)
                                insert.bindString(3, ROOM)
                                insert.bindString(4, "value-$i")
                                insert.bindString(5, first)
                                insert.bindString(6, second)
                                insert.bindLong(7, i.toLong())
                                insert.executeInsert()
                            }
                    }
                } finally { insert.close() }
            }
            for (analyzed in listOf(false, true)) {
                if (analyzed) sql.execSQL("ANALYZE")
                assertTrue(database.messageDao().mucConflictedEvents(ACCOUNT, listOf("root")).isEmpty())
                val (query, bindings) = synchronized(queries) {
                    queries.last { it.first.contains("EXISTS(SELECT 1 FROM identity_conflicts") }
                }
                val args = bindings.toTypedArray()
                val plans = sql.query("EXPLAIN QUERY PLAN $query", args).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(3)) }
                }
                println("HISTORY distribution=$distribution size=$size analyzed=$analyzed plans=$plans")
                // Both event-kind owner prefixes are empty, regardless of irrelevant history size.
                // With owner+kind SeekGE/IdxGT constraints there are zero matching index entries;
                // this is plan-derived work evidence, not a wall-clock or stmt_status measurement.
                for (owner in listOf("firstMessageId", "secondMessageId")) {
                    sql.query("SELECT count(*) FROM identity_conflicts WHERE accountId=? AND $owner=? AND kind IN ('STANZA_ID','MAM_RESULT')",
                        arrayOf<Any>(ACCOUNT, "root")).use { c ->
                        assertTrue(c.moveToFirst())
                        assertEquals(0, c.getInt(0))
                    }
                }
                sql.query("EXPLAIN $query", args).use { c ->
                    while (c.moveToNext()) println("VM " + (0 until c.columnCount).joinToString("|") { c.getString(it) ?: "NULL" })
                }
                // SQLite before 3.36 names the alias as "TABLE identity_conflicts AS c".
                val searches = plans.filter { CONFLICT_ALIAS_PLAN.containsMatchIn(it) }
                if (searches.size != 2 || searches.any { detail ->
                        !detail.contains("kind=?") ||
                            !(detail.contains("firstMessageId=?") || detail.contains("secondMessageId=?"))
                    }) failures += "distribution=$distribution size=$size analyzed=$analyzed: $searches"
            }
            // Positive controls exercise each owner and each event kind through the real DAO.
            for (kind in listOf("STANZA_ID", "MAM_RESULT")) for ((first, second) in
                listOf("root" to "z-other", "a-other" to "root")) {
                sql.execSQL("INSERT INTO identity_conflicts VALUES (?, ?, ?, 'positive', ?, ?, 1)",
                    arrayOf<Any>(ACCOUNT, kind, ROOM, first, second))
                assertEquals(listOf("root"), database.messageDao().mucConflictedEvents(ACCOUNT, listOf("root")))
                assertTrue(database.messageDao().mucConflictedEvents(OTHER_ACCOUNT, listOf("root")).isEmpty())
                assertTrue(database.messageDao().mucConflictedEvents(ACCOUNT, emptyList()).isEmpty())
                sql.execSQL("DELETE FROM identity_conflicts WHERE value='positive'")
            }
        }
        assertTrue("Unbounded irrelevant-history prefixes: $failures", failures.isEmpty())
    }

    private companion object {
        val CONFLICT_ALIAS_PLAN = Regex("""^(SEARCH|SCAN) (TABLE identity_conflicts AS )?c( |$)""")
    }
}
