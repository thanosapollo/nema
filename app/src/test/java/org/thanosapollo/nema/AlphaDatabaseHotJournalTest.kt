package org.thanosapollo.nema

import org.thanosapollo.nema.storage.ordinaryHistoricalColumns

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.experimental.LazyApplication

@RunWith(RobolectricTestRunner::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [34], application = HotJournalApplication::class)
class AlphaDatabaseHotJournalTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<HotJournalApplication>().also { application ->
        runBlocking { withTimeout(10_000) { application.databaseStartup.first { it != DatabaseStartup.OPENING } } }
    }

    companion object {
        @JvmStatic @org.junit.BeforeClass fun prepareKeystore() {
            java.security.Security.addProvider(object : java.security.Provider("NemaHotJournalTest", 1.0, "test only") {
                init { put("KeyStore.AndroidKeyStore", "com.sun.crypto.provider.JceKeyStore") }
            })
        }
        @JvmStatic @org.junit.AfterClass fun removeKeystore() { java.security.Security.removeProvider("NemaHotJournalTest") }
    }

    @After fun close() {
        if (app.databaseStartup.value == DatabaseStartup.READY) app.database.close()
    }

    @Test fun canonical29HotJournalRecoversBeforeBridgeAndPreservesCommittedRows() = compatible()

    @Test @Config(application = CurrentHotJournalApplication::class)
    fun current31HotJournalRecoversBeforeClassificationAndPreservesCommittedRows() = compatible()

    private fun compatible() {
        assertTrue("fixture required native rollback before startup", app.readOnlyRecoveryFailed)
        assertEquals(DatabaseStartup.READY, app.databaseStartup.value)
        assertEquals(31, app.database.openHelper.writableDatabase.version)
        assertEquals(app.committedRows, journalRows(app))
        assertTrue(app.deletedNames.isEmpty())
        runBlocking { app.retryDatabaseStartup().join() }
        assertEquals(DatabaseStartup.READY, app.databaseStartup.value)
        assertEquals(app.committedRows, journalRows(app))
        assertTrue(app.deletedNames.isEmpty())
    }

    @Test @Config(application = HistoricalHotJournalApplication::class)
    fun historicalCollisionRecoversButCancelNeverMigratesOrDeletesCommittedData() {
        assertTrue(app.readOnlyRecoveryFailed)
        assertEquals(DatabaseStartup.RESET_REQUIRED, app.databaseStartup.value)
        assertEquals(app.committedRows, journalRows(app))
        val recoveredBytes = app.getDatabasePath("nema.db").readBytes()
        val activity = Robolectric.buildActivity(MainActivity::class.java).create(Bundle()).start().resume().visible()
        try {
            compose.onNodeWithText("Local database reset required").assertExists()
            compose.onNodeWithText("Cancel").performScrollTo().performClick()
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { app.retryDatabaseStartup().join() }
        assertEquals(DatabaseStartup.RESET_REQUIRED, app.databaseStartup.value)
        assertArrayEquals(recoveredBytes, app.getDatabasePath("nema.db").readBytes())
        assertEquals(app.committedRows, journalRows(app))
        assertTrue(app.deletedNames.isEmpty())
        assertTrue(runCatching { app.sessionRuntime }.isFailure)
        SQLiteDatabase.openDatabase(app.getDatabasePath("nema.db").path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(29, it.version)
            it.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("d842cda2ce9467413942ebca6bfc3576", cursor.getString(0))
            }
        }
        assertEquals("keep", app.getSharedPreferences("journal-fixture", 0).getString("setting", null))
        assertEquals("opaque fixture", File(app.noBackupFilesDir, "journal-secret-sentinel").readText())
        assertEquals("unrelated", File(app.filesDir, "unrelated").readText())
    }
}

class CurrentHotJournalApplication : HotJournalApplication() { override val version = 31 }
class HistoricalHotJournalApplication : HotJournalApplication() { override val avatar = true }

open class HotJournalApplication : NemaApplication() {
    protected open val version = 29
    protected open val avatar = false
    val deletedNames = mutableListOf<String>()
    var committedRows: Map<String, List<List<String?>>> = emptyMap()
    var readOnlyRecoveryFailed = false

    override fun onCreate() {
        createHistoricalDatabase(this, avatar, version)
        val file = getDatabasePath("nema.db")
        val captured = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { check(it.moveToFirst()); check(it.getString(0) == "delete") }
            db.execSQL("PRAGMA cache_size=1")
            for (i in 0 until 100) db.execSQL(
                "INSERT INTO accounts (id,bareJid,authenticationId,serviceDomain) VALUES (?,?,?,?)",
                arrayOf("fixture-$i", "a$i@example.org", "before-crash", "example.org"),
            )
            committedRows = journalRows(this)
            db.beginTransaction()
            try {
                // Force native dirty-page spill, not an empty/fabricated sidecar. Capture the
                // crash-time file set before close rolls back, then restore it with no handles.
                db.execSQL("UPDATE accounts SET authenticationId=?", arrayOf("uncommitted".repeat(4000)))
                db.execSQL("UPDATE message_drafts SET body='uncommitted draft'")
                val journal = File(file.path + "-journal").readBytes()
                check(journal.size > 512)
                check(journal.take(8).toByteArray().contentEquals(byteArrayOf(0xd9.toByte(), 0xd5.toByte(), 0x05, 0xf9.toByte(), 0x20, 0xa1.toByte(), 0x63, 0xd7.toByte())))
                mapOf("" to file.readBytes(), "-journal" to journal)
            } finally { db.endTransaction() }
        }
        captured.forEach { (suffix, bytes) -> File(file.path + suffix).writeBytes(bytes) }
        // Prove this native file set is genuinely hot; don't accidentally recover it in
        // fixture setup. A read-only handle must fail on its first page read and retain it.
        readOnlyRecoveryFailed = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, { }).use { db ->
                db.rawQuery("PRAGMA quick_check", null).use { check(it.moveToFirst()) }
            }
        }.isFailure
        check(readOnlyRecoveryFailed)
        captured.forEach { (suffix, bytes) -> check(File(file.path + suffix).readBytes().contentEquals(bytes)) }
        getSharedPreferences("journal-fixture", 0).edit().putString("setting", "keep").commit()
        File(noBackupFilesDir, "journal-secret-sentinel").writeText("opaque fixture")
        File(filesDir, "unrelated").writeText("unrelated")
        super.onCreate()
    }

    override fun deleteDatabase(name: String): Boolean {
        deletedNames += name
        return super.deleteDatabase(name)
    }
}

private fun journalRows(context: android.content.Context): Map<String, List<List<String?>>> =
    SQLiteDatabase.openDatabase(context.getDatabasePath("nema.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('room_master_table','android_metadata','sqlite_sequence') ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        tables.associateWith { table -> db.rawQuery("SELECT * FROM `$table` ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(c.ordinaryHistoricalColumns().map { column ->
                if (c.getType(column) == android.database.Cursor.FIELD_TYPE_BLOB) c.getBlob(column).joinToString("") { "%02x".format(it) } else c.getString(column)
            }) }
        } }
    }
