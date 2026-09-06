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
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ArchiveRecordMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NemaDatabase::class.java)
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val key = ArchiveCursorKey("a", "archive.example.org", "ACCOUNT")

    private inline fun <T> NemaDatabase.use(block: (NemaDatabase) -> T): T =
        try { block(this) } finally { close() }

    private fun seed(sql: SupportSQLiteDatabase) {
        for (account in listOf("a", "b")) {
            sql.execSQL("INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) VALUES (?, ?, ?, 'example.org')", arrayOf(account, "$account@example.org", account))
            sql.execSQL("INSERT INTO peers (accountId, jid, lastReadLocalSequence) VALUES (?, 'peer@example.org', 7)", arrayOf(account))
            sql.execSQL("INSERT INTO account_message_sequences VALUES (?, 10)", arrayOf(account))
        }
        fun owner(account: String, id: String, ordinal: Long, vararg uids: String) {
            sql.execSQL("""INSERT INTO messages (accountId, localMessageId, peerJid, senderJid, direction,
                messageKind, body, localSequence, sentAtEpochMs, sentTimeSource, unreadEligible, locallyRead,
                markable, directSessionTransitionApplied, liveDeliveryObserved)
                VALUES (?, ?, 'peer@example.org', 'peer@example.org', 'INBOUND', 'CHAT', 'same', ?, 1000, 'MAM', 0, 1, 0, 0, 0)""",
                arrayOf<Any>(account, id, ordinal + 3))
            sql.execSQL("INSERT INTO archive_message_positions VALUES (?, ?, 'ACCOUNT', ?, ?)", arrayOf<Any>(account, key.archiveAuthority, ordinal, id))
            sql.execSQL("INSERT INTO trusted_identity_aliases VALUES (?, 'MESSAGE_ID', 'peer@example.org', ?, ?, 'TRUSTED')", arrayOf(account, id, id))
            for (uid in uids) sql.execSQL("INSERT INTO trusted_identity_aliases VALUES (?, 'MAM_RESULT', ?, ?, ?, 'TRUSTED')", arrayOf(account, key.aliasAuthority(), uid, id))
        }
        owner("a", "prefix", -1, "prefix-uid")
        owner("a", "unique", 1, "unique-uid")
        owner("a", "multi", 2, "multi-one", "multi-two")
        owner("b", "reset", 0, "reset-uid")
        sql.execSQL("INSERT INTO trusted_identity_aliases VALUES ('a', 'MAM_RESULT', ?, 'quarantined', 'multi', 'QUARANTINED')", arrayOf(key.aliasAuthority()))
        sql.execSQL("INSERT INTO trusted_identity_aliases VALUES ('a', 'MAM_RESULT', ?, 'quarantined-unique', 'unique', 'QUARANTINED')", arrayOf(key.aliasAuthority()))
        sql.execSQL("INSERT INTO trusted_identity_aliases VALUES ('a', 'MAM_RESULT', 'other-scope', 'other-alias', 'unique', 'TRUSTED')")
        sql.execSQL("INSERT INTO archive_cursors VALUES ('a', ?, 'ACCOUNT', 'control-first', 'control-last', 1, 'retained error', 0, 3)", arrayOf(key.archiveAuthority))
        sql.execSQL("INSERT INTO archive_cursors VALUES ('a', 'room@example.org', 'room@example.org', 'room-first', 'room-last', 1, NULL, 0, 8)")
        sql.execSQL("INSERT INTO archive_cursors VALUES ('b', ?, 'ACCOUNT', NULL, NULL, 1, 'Archive cursor reset during migration', NULL, NULL)", arrayOf(key.archiveAuthority))
    }

    private fun record(uid: String, owner: String, account: String = "a") = ArchivedIncomingMessage(uid, IncomingMessage(
        accountId = account, localMessageId = "incoming-$uid", peerJid = "peer@example.org", senderJid = "peer@example.org",
        direction = MessageDirection.INBOUND, messageKind = MessageKind.CHAT, threadId = null, parentThreadId = null,
        body = "same", archiveOrdinal = null, aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, "peer@example.org", owner)),
        sentAtEpochMs = 1000, sentTimeSource = MessageTimeSource.MAM,
    ))
    private fun page(direction: ArchiveDirection, boundary: String?, vararg records: ArchivedIncomingMessage,
        cursorKey: ArchiveCursorKey = key) = ArchivePage(cursorKey, direction, boundary, true, true, true,
        records.firstOrNull()?.resultId, records.lastOrNull()?.resultId, records.toList())
    private suspend fun raw(db: NemaDatabase, cursorKey: ArchiveCursorKey = key) = db.messageDao().archiveRecords(
        cursorKey.accountId, cursorKey.archiveAuthority, cursorKey.scope, Long.MIN_VALUE, Long.MAX_VALUE,
    ).map { it.resultId to it.archiveOrdinal }

    @Test fun exact27PreservesAllExistingTablesSeedsOnlyBoundariesAndSupportsCoveredLegacyReplay() = runBlocking {
        val name = "record-migration-${UUID.randomUUID()}.db"
        try {
            val before = migrations.createDatabase(name, 27).use { seed(it); snapshot(it) }
            repeat(2) {
                NemaDatabase.create(context, name).use { db ->
                    val sql = db.openHelper.writableDatabase
                    assertEquals(28, sql.version)
                    assertEquals(before, snapshot(sql))
                    assertEquals(listOf("control-first" to 0L, "control-last" to 3L), raw(db))
                    assertTrue(raw(db, key.copy(accountId = "b")).isEmpty())
                    assertTrue(raw(db, key.copy(archiveAuthority = "room@example.org", scope = "room@example.org")).isEmpty())
                    sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                    sql.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
                }
            }
            migrations.runMigrationsAndValidate(name, 28, true, MessageSchema.MIGRATION_27_28).close()
            NemaDatabase.create(context, name).use { db ->
                val store = MessageStore(db)
                val after = store.applyArchivePage(page(ArchiveDirection.AFTER, "control-last",
                    record("unique-uid", "unique"), record("multi-one", "multi"),
                    ArchivedIncomingMessage("control-last", null), record("fresh-repeat", "unique")))
                assertEquals(ArchivePageStatus.APPLIED, after.status)
                assertEquals(0, after.inserted)
                assertEquals(4L, after.cursor.newestOrdinal)
                assertEquals(1L, store.archivePositions("a", "unique").single().archiveOrdinal)
                assertTrue(store.messages("a").all { it.locallyRead && !it.unreadEligible })
                val earlier = store.applyArchivePage(page(ArchiveDirection.BEFORE, "control-first",
                    record("prefix-uid", "prefix"), ArchivedIncomingMessage("control-first", null)))
                assertEquals(ArchivePageStatus.APPLIED, earlier.status)
                assertEquals(-1L, earlier.cursor.oldestOrdinal)
                assertEquals(listOf("prefix-uid" to -1L, "control-first" to 0L, "unique-uid" to 1L,
                    "multi-one" to 2L, "control-last" to 3L, "fresh-repeat" to 4L), raw(db))
            }
            NemaDatabase.create(context, name).use { db ->
                val store = MessageStore(db)
                assertEquals(ArchivePageStatus.APPLIED, store.applyArchivePage(page(ArchiveDirection.AFTER, "fresh-repeat",
                    record("next-repeat", "unique"))).status)
                assertEquals(5L, store.archiveCursor(key)?.newestOrdinal)
                assertEquals(3, store.messages("a").size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun oldMultiAliasCannotInventExtensionButFreshUidCan() = runBlocking {
        val name = "record-ambiguous-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 27).use { seed(it) }
            NemaDatabase.create(context, name).use { db ->
                val store = MessageStore(db)
                val before = snapshot(db.openHelper.writableDatabase)
                val refused = store.applyArchivePage(page(ArchiveDirection.AFTER, "control-last", record("multi-two", "multi")))
                assertEquals(ArchivePageStatus.RETRYABLE_ERROR, refused.status)
                assertEquals(before - "archive_cursors", snapshot(db.openHelper.writableDatabase) - "archive_cursors")
                assertEquals(listOf("control-first" to 0L, "control-last" to 3L), raw(db))
                val fresh = store.applyArchivePage(page(ArchiveDirection.AFTER, "control-last", record("fresh", "multi")))
                assertEquals(ArchivePageStatus.APPLIED, fresh.status)
                assertEquals(2L, store.archivePositions("a", "multi").single().archiveOrdinal)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun exactResetPrefixRepeatedOwnerRebasesOnceAndKeepsNewLedgerFixed() = runBlocking {
        val name = "record-reset-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 27).use { seed(it) }
            val resetKey = key.copy(accountId = "b")
            NemaDatabase.create(context, name).use { db ->
                val store = MessageStore(db)
                val bootstrap = store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP, null,
                    record("tail", "tail", "b"), cursorKey = resetKey))
                assertEquals(ArchivePageStatus.APPLIED, bootstrap.status)
                assertEquals(1L, bootstrap.cursor.oldestOrdinal)
            }
            NemaDatabase.create(context, name).use { db ->
                val store = MessageStore(db)
                val before = store.applyArchivePage(page(ArchiveDirection.BEFORE, "tail",
                    record("reset-uid", "reset", "b"), record("repeat", "reset", "b"),
                    record("tail", "tail", "b"), cursorKey = resetKey))
                assertEquals(ArchivePageStatus.APPLIED, before.status)
                assertEquals(listOf("reset-uid" to -1L, "repeat" to 0L, "tail" to 1L), raw(db, resetKey))
                assertEquals(-1L, store.archivePositions("b", "reset").single().archiveOrdinal)
                assertEquals(1L, store.archivePositions("b", "incoming-tail").single().archiveOrdinal)
                assertEquals(2, store.messages("b").size)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun lazyUniqueLegacyAnchorCannotOverrideContradictoryRawBoundary() = runBlocking {
        val name = "record-anchor-conflict-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 27).use { sql ->
                seed(sql)
                sql.execSQL("UPDATE archive_cursors SET oldestOrdinal = 1 WHERE accountId = 'a' AND scope = 'ACCOUNT'")
            }
            NemaDatabase.create(context, name).use { db ->
                val before = snapshot(db.openHelper.writableDatabase)
                val result = MessageStore(db).applyArchivePage(page(ArchiveDirection.AFTER, "control-last",
                    record("unique-uid", "unique"), record("multi-one", "multi"),
                    ArchivedIncomingMessage("control-last", null), record("fresh", "unique")))
                assertEquals(ArchivePageStatus.RETRYABLE_ERROR, result.status)
                assertEquals(before - "archive_cursors", snapshot(db.openHelper.writableDatabase) - "archive_cursors")
                assertEquals(listOf("control-first" to 1L, "control-last" to 3L), raw(db))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun exported8MigratesThroughResetTo28ThenRepeatedBootstrap() = runBlocking {
        val name = "record-chain-${UUID.randomUUID()}.db"
        val oldKey = key.copy(archiveAuthority = "a@example.org")
        try {
            migrations.createDatabase(name, 8).use { sql ->
                sql.execSQL("INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) VALUES ('a', 'a@example.org', 'a', 'example.org')")
                sql.execSQL("INSERT INTO peers (accountId, jid, room) VALUES ('a', 'peer@example.org', 0)")
                sql.execSQL("""INSERT INTO messages (accountId, localMessageId, peerJid, senderJid, direction, messageKind, body, localSequence, archiveOrdinal)
                    VALUES ('a', 'old', 'peer@example.org', 'peer@example.org', 'INBOUND', 'CHAT', 'same', 1, 0)""")
                sql.execSQL("INSERT INTO trusted_identity_aliases VALUES ('a', 'MAM_RESULT', ?, 'old-uid', 'old', 'TRUSTED')", arrayOf(oldKey.aliasAuthority()))
                sql.execSQL("INSERT INTO trusted_identity_aliases VALUES ('a', 'MESSAGE_ID', 'peer@example.org', 'old', 'old', 'TRUSTED')")
                sql.execSQL("INSERT INTO archive_cursors VALUES ('a', 'a@example.org', 'ACCOUNT', 'old-uid', 'old-uid', 0, NULL)")
                sql.execSQL("INSERT INTO account_message_sequences VALUES ('a', 2)")
            }
            NemaDatabase.create(context, name).use { db ->
                val store = MessageStore(db)
                assertEquals(28, db.openHelper.writableDatabase.version)
                assertTrue(raw(db, oldKey).isEmpty())
                assertNull(store.archiveCursor(oldKey)?.oldestId)
                val result = store.applyArchivePage(page(ArchiveDirection.BOOTSTRAP, null,
                    record("old-uid", "old"), record("new-repeat", "old"), cursorKey = oldKey))
                assertEquals(ArchivePageStatus.APPLIED, result.status)
                assertEquals(listOf("old-uid" to 0L, "new-repeat" to 1L), raw(db, oldKey))
                assertEquals(1, store.messages("a").size)
                assertEquals(0L, store.archivePositions("a", "old").single().archiveOrdinal)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun contradictoryBoundaryEvidenceFailsMigrationInsteadOfDroppingOneEndpoint() {
        val name = "record-invalid-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 27).use { sql ->
                seed(sql)
                sql.execSQL("UPDATE archive_cursors SET newestId = oldestId WHERE accountId = 'a' AND scope = 'ACCOUNT'")
            }
            val db = NemaDatabase.create(context, name)
            try { db.openHelper.writableDatabase; fail("Contradictory UID positions must fail") }
            catch (_: android.database.sqlite.SQLiteConstraintException) { /* transaction rolled back */ }
            finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    private fun snapshot(sql: SupportSQLiteDatabase): Map<String, List<List<String?>>> {
        val tables = sql.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata', 'archive_record_positions') ORDER BY name").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return tables.associateWith { table -> sql.query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
            buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) }
        } }
    }
}
