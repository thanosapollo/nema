package org.thanosapollo.nema.storage

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProtectedMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NemaDatabase::class.java)
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun literalReleased30KeepsOldColumnsAndPlaceholderThroughFactoryReopen() {
        val name = "protected-migration-${UUID.randomUUID()}.db"
        try {
            val old = migrations.createDatabase(name, 30)
            seed(old)
            val before = snapshot(old).toMutableMap()
            before["message_outbox"] = before.getValue("message_outbox").map { row ->
                row.mapIndexed { index, value -> if (index == 4 && value == "IN_FLIGHT") "UNCERTAIN" else value }
            }
            old.close()
            repeat(2) {
                val db = NemaDatabase.create(context, name)
                try {
                    val sql = db.openHelper.writableDatabase
                    assertEquals(31, sql.version)
                    assertEquals(before, snapshot(sql))
                    sql.query("SELECT protectedState, protectedEvidence, body FROM messages").use { c ->
                        assertTrue(c.moveToFirst()); assertEquals("NONE", c.getString(0)); assertTrue(c.isNull(1))
                        assertEquals("Encrypted message", c.getString(2))
                    }
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    sql.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
                } finally { db.close() }
            }
            migrations.runMigrationsAndValidate(name, 31, true, MessageSchema.MIGRATION_30_31).close()
        } finally { context.deleteDatabase(name) }
    }

    private fun seed(db: SupportSQLiteDatabase) {
        for (account in listOf("a", "b")) {
            db.execSQL("INSERT INTO accounts(id,bareJid,authenticationId,serviceDomain) VALUES(?,? ,?,'example.org')",
                arrayOf(account, "$account@example.org", account))
            db.execSQL("INSERT INTO peers(accountId,jid,lastReadLocalSequence) VALUES(?,'peer@example.org',0)", arrayOf(account))
            db.execSQL("INSERT INTO account_reconciliation_state VALUES(?,'identityless-live-mam-v1','PENDING',11,1,2,3,4,5)", arrayOf(account))
            db.execSQL("INSERT INTO message_threads VALUES(?,'peer@example.org','CHAT','root',NULL)", arrayOf(account))
            db.execSQL("INSERT INTO message_threads VALUES(?,'peer@example.org','CHAT','child','root')", arrayOf(account))
            db.execSQL("INSERT INTO direct_thread_sessions VALUES(?,'peer@example.org','CHAT','root')", arrayOf(account))
            db.execSQL("INSERT INTO message_thread_titles VALUES(?,'peer@example.org','CHAT','child','Child topic')", arrayOf(account))
            for ((index, status) in listOf("PENDING", "IN_FLIGHT", "ACKNOWLEDGED", "CONFIRMED", "UNCERTAIN", "FAILED").withIndex()) {
                db.execSQL("""INSERT INTO messages(accountId,localMessageId,peerJid,senderJid,direction,messageKind,body,
                    threadId,parentThreadId,localSequence,markable,directSessionTransitionApplied,liveDeliveryObserved)
                    VALUES(?,?,'peer@example.org','peer@example.org','INBOUND','CHAT','Encrypted message','child','root',?,0,0,1)""",
                    arrayOf<Any>(account, "m$index", index + 1))
                db.execSQL("INSERT INTO message_outbox VALUES(?,?,?,?,?,7,2,'retained failure',NULL)",
                    arrayOf(account, "op$index", "m$index", "origin$index", status))
            }
            db.execSQL("INSERT INTO account_message_sequences VALUES(?,10)", arrayOf(account))
            db.execSQL("INSERT INTO archive_message_positions VALUES(?,'archive.example.org','ACCOUNT',0,'m0')", arrayOf(account))
            db.execSQL("INSERT INTO archive_record_positions VALUES(?,'archive.example.org','ACCOUNT','uid',0)", arrayOf(account))
            db.execSQL("INSERT INTO trusted_identity_aliases VALUES(?,'MESSAGE_ID','peer@example.org','wire','m0','TRUSTED')", arrayOf(account))
            db.execSQL("INSERT INTO identity_conflicts VALUES(?,'MESSAGE_ID','peer@example.org','collision','m0','m1',3)", arrayOf(account))
            db.execSQL("INSERT INTO archive_cursors VALUES(?,'archive.example.org','ACCOUNT','uid','uid',1,'retained',0,0)", arrayOf(account))
            db.execSQL("""INSERT INTO message_drafts VALUES(?,'peer@example.org','CHAT','child','draft',
                'wire','peer@example.org','quoted','Peer','https://example.org/file','file','application/octet-stream',8)""", arrayOf(account))
            db.execSQL("INSERT INTO chat_navigation VALUES(?,'peer@example.org','child','root')", arrayOf(account))
            db.execSQL("INSERT INTO message_reactions VALUES(?,'peer@example.org','peer@example.org','m0','m0','wire','👍',100,2)", arrayOf(account))
            db.execSQL("INSERT INTO shared_threads VALUES(?,'peer@example.org','CHAT','child','example.org','incarnation','Shared',17,0,1,0)", arrayOf(account))
            db.execSQL("INSERT INTO thread_directory_intents VALUES(?,'peer@example.org','CHAT','example.org','incarnation','child','intent',17,'Pending',NULL)", arrayOf(account))
        }
        db.execSQL("INSERT INTO active_account VALUES(1,'a')")
    }

    private fun snapshot(sql: SupportSQLiteDatabase): Map<String, List<List<String?>>> {
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata') ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table ->
            val columns = sql.query("PRAGMA table_info(`$table`)").use { c ->
                buildList { while (c.moveToNext()) if (c.getString(1) !in setOf("protectedState", "protectedEvidence")) add(c.getString(1)) }
            }
            sql.query("SELECT ${columns.joinToString { "`$it`" }} FROM `$table` ORDER BY rowid").use { c ->
                buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
            }
        }
    }
}
