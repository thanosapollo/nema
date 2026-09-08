package org.thanosapollo.nema.storage

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ConflictIndexMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NemaDatabase::class.java)

    @Test fun exact26StateAndConflictAuthoritySurviveRegisteredMigrationAndReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "conflict-index-${UUID.randomUUID()}.db"
        try {
            // Genuine exported schema 26, not a new database with a rewound version marker.
            val before = migrations.createDatabase(name, 26).use { sql ->
                sql.execSQL("INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) VALUES ('a', 'self@example.org', 'self', 'example.org')")
                sql.execSQL("INSERT INTO peers (accountId, jid, room, lastReadLocalSequence) VALUES ('a', 'room@example.org', 1, 1)")
                for ((i, id) in listOf("a", "root", "z").withIndex()) {
                    sql.execSQL("""INSERT INTO messages (accountId, localMessageId, peerJid, senderJid,
                        direction, messageKind, body, localSequence, markable, directSessionTransitionApplied,
                        liveDeliveryObserved, locallyRead, mucMessageId, mucReplaceId, mucClaimState,
                        mucOccupantId, mucOccupantEvidence, mucPayloadState, mucLiveOrderEpoch, mucCorrectionSelected)
                        VALUES ('a', '$id', 'room@example.org', 'room@example.org/nick', 'INBOUND', 'GROUPCHAT',
                        '$id body', ${i + 1}, 0, 0, 1, 1, 'wire-$id', 'retained', 'VALID', 'opaque', 'BOTH', 'PLAIN', 'epoch', 1)""")
                }
                for (kind in listOf("MESSAGE_ID", "STANZA_ID", "MAM_RESULT")) {
                    for ((first, second) in listOf("a" to "root", "root" to "z")) {
                        sql.execSQL("INSERT INTO identity_conflicts VALUES ('a', '$kind', 'room@example.org', 'value', '$first', '$second', 42)")
                    }
                    sql.execSQL("INSERT INTO trusted_identity_aliases VALUES ('a', '$kind', 'room@example.org', 'value', 'root', 'QUARANTINED')")
                }
                sql.execSQL("INSERT INTO account_message_sequences VALUES ('a', 4)")
                sql.execSQL("INSERT INTO archive_message_positions VALUES ('a', 'room@example.org', 'room@example.org', 0, 'root')")
                sql.execSQL("INSERT INTO archive_cursors VALUES ('a', 'room@example.org', 'room@example.org', 'old', 'new', 0, NULL, 0, 1)")
                snapshot(sql)
            }
            // Production factory must register the migration; helper-only validation cannot prove it.
            repeat(2) {
                val db = NemaDatabase.create(context, name)
                try {
                    val sql = db.openHelper.writableDatabase
                    assertEquals(30, sql.version)
                    assertEquals(before, snapshot(sql))
                    assertEquals(listOf("root"), db.messageDao().mucConflictedEvents("a", listOf("root")))
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    sql.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
                } finally { db.close() }
            }
            migrations.runMigrationsAndValidate(name, 30, true, MessageSchema.MIGRATION_26_27, MessageSchema.MIGRATION_27_28).close()
        } finally { context.deleteDatabase(name) }
    }

    private fun snapshot(sql: SupportSQLiteDatabase): Map<String, List<List<String?>>> {
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata', 'archive_record_positions', 'shared_threads', 'thread_directory_intents') ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table ->
            sql.query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
                buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
            }
        }
    }
}
