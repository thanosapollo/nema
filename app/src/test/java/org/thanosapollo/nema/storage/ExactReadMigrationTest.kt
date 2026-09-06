package org.thanosapollo.nema.storage

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExactReadMigrationTest {
    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NemaDatabase::class.java)

    @Test
    fun exported24SnapshotBecomesExactReadState() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "exact-read-${UUID.randomUUID()}.db"
        try {
            val beforeUnread = mutableListOf<String>()
            migrations.createDatabase(name, 24).use { db ->
                for (account in listOf("a", "b")) {
                    db.execSQL("INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) VALUES ('$account', 'self@example.org', 'self', 'example.org')")
                    for (peer in listOf("p", "q")) {
                        val watermark = if (account == "a" && peer == "p") 2 else 0
                        db.execSQL("INSERT INTO peers (accountId, jid, room, lastReadLocalSequence, inRoster) VALUES ('$account', '$peer', 0, $watermark, 0)")
                        for (n in 1..5) {
                            val sequence = n + if (peer == "q") 10 else 0
                            db.execSQL("""INSERT INTO messages (accountId, localMessageId, peerJid, senderJid,
                                direction, messageKind, body, localSequence, markable, directSessionTransitionApplied,
                                liveDeliveryObserved, unreadEligible, threadId, parentThreadId) VALUES ('$account', '$peer$n', '$peer', '$peer',
                                'INBOUND', 'CHAT', 'body', $sequence, 0, 0, 0, ${if (n == 4) 0 else 1},
                                '${if (n >= 3) "child" else "root"}', ${if (n >= 3) "'root'" else "NULL"})""")
                        }
                    }
                }
                // The schema-24 predicate from both production unread queries, before migration.
                db.query("""SELECT unread.accountId, unread.localMessageId FROM messages AS unread
                    LEFT JOIN peers ON peers.accountId = unread.accountId AND peers.jid = unread.peerJid
                    WHERE unread.direction = 'INBOUND' AND unread.replaceId IS NULL
                      AND unread.unreadEligible = 1
                      AND unread.localSequence > COALESCE(peers.lastReadLocalSequence, 0)
                    ORDER BY unread.accountId, unread.localMessageId""").use { rows ->
                    while (rows.moveToNext()) beforeUnread += rows.getString(0) + rows.getString(1)
                }
                assertEquals(listOf("ap3", "ap5", "aq1", "aq2", "aq3", "aq5", "bp1", "bp2", "bp3", "bp5", "bq1", "bq2", "bq3", "bq5"), beforeUnread)
            }
            repeat(2) {
                val db = NemaDatabase.create(context, name)
                try {
                    val sql = db.openHelper.writableDatabase
                    assertEquals(28, sql.version)
                    sql.query("SELECT localMessageId FROM messages WHERE accountId = 'a' AND peerJid = 'p' AND locallyRead = 1 ORDER BY localSequence").use { rows ->
                        val ids = mutableListOf<String>()
                        while (rows.moveToNext()) ids += rows.getString(0)
                        assertEquals(listOf("p1", "p2"), ids)
                    }
                    sql.query("SELECT accountId, localMessageId FROM messages WHERE direction = 'INBOUND' AND replaceId IS NULL AND unreadEligible AND NOT locallyRead ORDER BY accountId, localMessageId").use { rows ->
                        val ids = mutableListOf<String>()
                        while (rows.moveToNext()) ids += rows.getString(0) + rows.getString(1)
                        assertEquals(beforeUnread, ids)
                    }
                    sql.query("SELECT localMessageId, locallyRead, unreadEligible FROM messages WHERE accountId = 'a' AND peerJid = 'p' AND threadId = 'child' AND parentThreadId = 'root' ORDER BY localSequence").use { rows ->
                        val children = mutableListOf<Triple<String, Int, Int>>()
                        while (rows.moveToNext()) children += Triple(rows.getString(0), rows.getInt(1), rows.getInt(2))
                        assertEquals(listOf(Triple("p3", 0, 1), Triple("p4", 0, 0), Triple("p5", 0, 1)), children)
                    }
                } finally {
                    db.close()
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
