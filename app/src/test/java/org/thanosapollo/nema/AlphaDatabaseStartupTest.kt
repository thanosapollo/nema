package org.thanosapollo.nema

import org.thanosapollo.nema.storage.ordinaryHistoricalColumns

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
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
import org.thanosapollo.nema.service.XmppConnectionService
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.DatabaseCompatibility
import org.thanosapollo.nema.storage.inspectAlphaDatabase

@RunWith(RobolectricTestRunner::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [34], application = AlphaFixtureApplication::class)
class AlphaDatabaseStartupTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AlphaFixtureApplication>().also { application ->
        runBlocking { kotlinx.coroutines.withTimeout(10_000) { application.databaseStartup.first { it != DatabaseStartup.OPENING } } }
    }

    companion object {
        @JvmStatic @org.junit.BeforeClass fun prepareKeystore() {
            java.security.Security.addProvider(object : java.security.Provider("NemaAlphaTest", 1.0, "test only") {
                init { put("KeyStore.AndroidKeyStore", "com.sun.crypto.provider.JceKeyStore") }
            })
        }
        @JvmStatic @org.junit.AfterClass fun removeKeystore() { java.security.Security.removeProvider("NemaAlphaTest") }
    }

    private fun launch() = Robolectric.buildActivity(MainActivity::class.java).create(Bundle()).start().resume().visible()
    private fun await(state: DatabaseStartup) {
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); app.databaseStartup.value == state }
        compose.waitForIdle()
    }
    private fun confirm() = compose.onNodeWithText("Reset and continue").performScrollTo().performClick()
    private fun assertUntouched() {
        assertEquals(app.beforeHash, hash(app.getDatabasePath("nema.db")))
        assertEquals(app.beforeRows, snapshot(app))
        assertTrue(app.deletedNames.isEmpty())
        assertTrue(runCatching { app.sessionRuntime }.exceptionOrNull() is UninitializedPropertyAccessException)
    }

    @Test @Config(application = Released30ProtectedFixtureApplication::class)
    fun released30UpgradesToProtectedSchemaWithoutReset() {
        assertEquals(1, app.beforeRows.getValue("messages").size)
        assertEquals(DatabaseStartup.READY, app.databaseStartup.value)
        assertEquals(31, app.database.openHelper.writableDatabase.version)
        assertTrue(app.deletedNames.isEmpty())
        app.database.openHelper.writableDatabase.query("SELECT protectedState, protectedEvidence, body FROM messages WHERE localMessageId = 'm'").use {
            assertTrue(it.moveToFirst())
            assertEquals("NONE", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals("unsent body", it.getString(2))
        }
    }

    @Test @Config(application = Current31ProtectedFixtureApplication::class)
    fun genuineCurrentProtectedSchemaOpensWithoutReset() {
        assertEquals(1, app.beforeRows.getValue("messages").size)
        assertEquals(DatabaseStartup.READY, app.databaseStartup.value)
        assertEquals(31, app.database.openHelper.writableDatabase.version)
        assertTrue(app.deletedNames.isEmpty())
    }

    @Test fun shippedIdentitiesAndHistoricalExportsStayPinned() {
        val avatar = requireNotNull(javaClass.getResourceAsStream("/schema-history/avatar-29.json")).use { it.readBytes() }
        assertEquals("43024f3cf2fa58f2dd56162cab8d27786c84825a20b5989f1bb5da5ddf92a9b9", digest(avatar))
        val canonical = app.assets.open("org.thanosapollo.nema.storage.NemaDatabase/29.json").use { it.readBytes() }
        assertEquals("d788f583b04b20cc3ed0c1b19e09041a8f5c9421f7d5ae0d795d961858b4b828", digest(canonical))
        assertEquals("d842cda2ce9467413942ebca6bfc3576", JSONObject(avatar.decodeToString()).getJSONObject("database").getString("identityHash"))
        assertEquals("5e8e901ae2a87582117fb5276a3c5e9b", JSONObject(canonical.decodeToString()).getJSONObject("database").getString("identityHash"))
    }

    @Test fun populatedShippedAvatarWarningPrecedesConsentThenResetCreatesUsableDatabaseAndReopens() {
        assertEquals(DatabaseStartup.RESET_REQUIRED, app.databaseStartup.value)
        val activity = launch()
        try {
            compose.onNodeWithText("Local database reset required").assertExists()
            compose.onNodeWithText(app.getString(R.string.database_reset_warning)).assertExists()
            assertUntouched()
            confirm()
            await(DatabaseStartup.READY)
            assertEquals(listOf("nema.db"), app.deletedNames)
            assertTrue(runBlocking { app.database.accountDao().allAccounts() }.isEmpty())
            assertEquals("keep", app.getSharedPreferences("alpha-fixture", 0).getString("setting", null))
            assertEquals("opaque credential fixture", File(app.noBackupFilesDir, "alpha-secret-sentinel").readText())
            assertEquals("unrelated", File(app.filesDir, "unrelated").readText())
        } finally { activity.pause().stop().destroy() }
        compose.waitForIdle()
        proveUsableAndReopen()
    }

    @Test fun cancelAndBackAndRecreationNeverDelete() {
        val first = launch()
        assertUntouched()
        first.recreate()
        compose.onNodeWithText("Local database reset required").assertExists()
        assertUntouched()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertTrue(first.get().isFinishing)
        first.pause().stop().destroy()
        val second = launch()
        second.get().onBackPressedDispatcher.onBackPressed()
        assertTrue(second.get().isFinishing)
        assertUntouched()
        second.pause().stop().destroy()
    }

    @Test @Config(application = OlderAlphaFixtureApplication::class)
    fun olderExport28RequiresConsentWithoutRunningMigrations() {
        assertEquals(DatabaseStartup.RESET_REQUIRED, app.databaseStartup.value)
        assertUntouched()
        val activity = launch()
        try {
            confirm()
            await(DatabaseStartup.READY)
            assertTrue(runBlocking { app.database.accountDao().allAccounts() }.isEmpty())
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(application = WalAlphaFixtureApplication::class)
    fun committedWalIsReadableBeforeConsentAndNativeResetRemovesOldSidecars() {
        assertEquals(DatabaseStartup.RESET_REQUIRED, app.databaseStartup.value)
        assertEquals("committed WAL draft", snapshot(app).getValue("message_drafts").single()[4])
        assertEquals(app.beforeRows, snapshot(app))
        assertTrue(app.deletedNames.isEmpty())
        // Native preserving preflight may checkpoint the original committed WAL. Restore
        // another genuine fixture after classification to exercise deletion of sidecars too.
        createCommittedWalFixture(app, "committed WAL reset fixture")
        assertTrue(File(app.getDatabasePath("nema.db").path + "-wal").length() > 0)
        val activity = launch()
        try {
            confirm()
            await(DatabaseStartup.READY)
            assertTrue(runBlocking { app.database.accountDao().allAccounts() }.isEmpty())
            assertEquals(listOf("nema.db"), app.deletedNames)
            assertTrue(app.nativeDeletionRemovedAllSidecars)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun nativeDeleteFailureReportsAndExplicitRetrySucceeds() {
        app.failDelete = true
        val activity = launch()
        try {
            confirm()
            await(DatabaseStartup.RESET_FAILED)
            assertEquals(app.beforeHash, hash(app.getDatabasePath("nema.db")))
            compose.onNodeWithText(app.getString(R.string.database_reset_failed)).assertExists()
            activity.recreate()
            compose.onNodeWithText(app.getString(R.string.database_reset_failed)).assertExists()
            app.failDelete = false
            confirm()
            await(DatabaseStartup.READY)
            assertEquals(listOf("nema.db", "nema.db"), app.deletedNames)
        } finally { activity.pause().stop().destroy() }
        compose.waitForIdle()
        proveUsableAndReopen()
    }

    @Test fun thrownDeleteFailureAlsoStaysAtConsentGate() {
        app.throwDelete = true
        val activity = launch()
        try {
            confirm()
            await(DatabaseStartup.RESET_FAILED)
            assertEquals(app.beforeHash, hash(app.getDatabasePath("nema.db")))
            assertTrue(runCatching { app.sessionRuntime }.isFailure)
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(application = CanonicalAlphaFixtureApplication::class)
    fun canonicalSharedThread29RetainsEveryPopulatedRowAndOpens31() {
        assertEquals(DatabaseStartup.READY, app.databaseStartup.value)
        assertEquals(app.beforeRows, snapshot(app))
        assertTrue(app.deletedNames.isEmpty())
        assertEquals(31, app.database.openHelper.writableDatabase.version)
        app.database.close()
        val reopened = NemaDatabase.create(app)
        try {
            assertEquals(31, reopened.openHelper.writableDatabase.version)
            assertEquals(app.beforeRows, snapshot(app))
        } finally { reopened.close() }
    }

    @Test @Config(application = FreshAlphaFixtureApplication::class)
    fun freshCurrentDatabaseAndStaleResetCallbackRetainData() {
        assertEquals(DatabaseStartup.READY, app.databaseStartup.value)
        assertTrue(app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode > 3)
        val sql = app.database.openHelper.writableDatabase
        assertEquals(31, sql.version)
        sql.query("SELECT name FROM sqlite_master WHERE name = 'muc_avatars'").use { assertFalse(it.moveToFirst()) }
        runBlocking { app.database.accountDao().upsert(AccountEntity("retain", "retain@example.org", "retain", null, "example.org", null, null)) }
        runBlocking {
            app.resetDatabaseAndContinue().join()
            app.retryDatabaseStartup().join()
        }
        assertEquals(1, runBlocking { app.database.accountDao().allAccounts().size })
        assertTrue(app.deletedNames.isEmpty())
        app.database.close()
        val reopened = NemaDatabase.create(app)
        try { assertEquals(1, runBlocking { reopened.accountDao().allAccounts().size }) } finally { reopened.close() }
    }

    @Test @Config(application = CorruptAlphaFixtureApplication::class)
    fun corruptionNeverOffersOrPerformsResetAndRetryKeepsBytes() {
        assertEquals(DatabaseStartup.FAILED, app.databaseStartup.value)
        val activity = launch()
        try {
            compose.onNodeWithText("Reset and continue").assertDoesNotExist()
            compose.onNodeWithText("Retry").performClick()
            await(DatabaseStartup.FAILED)
            runBlocking { app.resetDatabaseAndContinue().join() }
            assertEquals(app.beforeHash, hash(app.getDatabasePath("nema.db")))
            assertTrue(app.deletedNames.isEmpty())
            val db = NemaDatabase.create(app)
            try { assertTrue(runCatching { db.openHelper.writableDatabase }.isFailure) } finally { db.close() }
            assertEquals(app.beforeHash, hash(app.getDatabasePath("nema.db")))
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(application = UnknownIdentityAlphaFixtureApplication::class)
    fun damagedIdentityIsNotProofOfAnIncompatibleShippedSchema() {
        assertEquals(DatabaseStartup.FAILED, app.databaseStartup.value)
        val activity = launch()
        try {
            compose.onNodeWithText("Reset and continue").assertDoesNotExist()
            runBlocking {
                app.resetDatabaseAndContinue().join()
                app.retryDatabaseStartup().join()
            }
            assertEquals(DatabaseStartup.FAILED, app.databaseStartup.value)
            assertUntouched()
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(application = UnknownOlderAlphaFixtureApplication::class)
    fun unknownNonblankIdentityAt28PreservesDataThroughEveryFailureAction() = verifyUnknownOlderFailureActions()

    @Test @Config(application = MismatchedOlderAlphaFixtureApplication::class)
    fun known27IdentityClaimingVersion28PreservesDataThroughEveryFailureAction() = verifyUnknownOlderFailureActions()

    private fun verifyUnknownOlderFailureActions() {
        assertEquals(DatabaseStartup.FAILED, app.databaseStartup.value)
        assertTrue(app.beforeRows.getValue("messages").isNotEmpty())
        assertUntouched()
        val activity = launch()
        try {
            compose.onNodeWithText("Reset and continue").assertDoesNotExist()
            val staleReset = app::resetDatabaseAndContinue
            runBlocking { staleReset().join() }
            assertUntouched()
            compose.onNodeWithText("Retry").performClick()
            runBlocking { app.retryDatabaseStartup().join() }
            await(DatabaseStartup.FAILED)
            assertUntouched()
            activity.recreate()
            compose.onNodeWithText("Reset and continue").assertDoesNotExist()
            runBlocking { staleReset().join() }
            assertEquals(DatabaseStartup.FAILED, app.databaseStartup.value)
            assertUntouched()
            compose.onNodeWithText("Cancel").performScrollTo().performClick()
            assertTrue(activity.get().isFinishing)
            assertUntouched()
        } finally { activity.pause().stop().destroy() }
        val second = launch()
        try {
            compose.onNodeWithText("Reset and continue").assertDoesNotExist()
            second.get().onBackPressedDispatcher.onBackPressed()
            assertTrue(second.get().isFinishing)
            assertUntouched()
        } finally { second.pause().stop().destroy() }
    }

    @Test @Config(application = OlderAlphaFixtureApplication::class)
    fun renderedResetCallbackCannotDeleteAfterRetryRejectsChangedOlderIdentity() {
        assertEquals(DatabaseStartup.RESET_REQUIRED, app.databaseStartup.value)
        val activity = launch()
        try {
            val staleClick = compose.onNodeWithText("Reset and continue").fetchSemanticsNode()
                .config[SemanticsActions.OnClick].action!!
            SQLiteDatabase.openDatabase(app.getDatabasePath("nema.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("UPDATE room_master_table SET identity_hash = 'unrecognized-damaged-identity' WHERE id = 42")
            }
            app.beforeHash = hash(app.getDatabasePath("nema.db"))
            runBlocking { app.retryDatabaseStartup().join() }
            await(DatabaseStartup.FAILED)
            compose.onNodeWithText("Reset and continue").assertDoesNotExist()
            compose.runOnIdle { staleClick() }
            runBlocking { app.resetDatabaseAndContinue().join(); app.retryDatabaseStartup().join() }
            await(DatabaseStartup.FAILED)
            assertUntouched()
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(application = OlderAlphaFixtureApplication::class)
    fun retainedOlderMetadataMustMatchItsExactExportedVersion() {
        val identities = (1..28).associateWith { version ->
            app.assets.open("org.thanosapollo.nema.storage.NemaDatabase/$version.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONObject("database").getString("identityHash")
            }
        }
        // Exercise the metadata contract, not reconstructed historical table layouts.
        for ((version, identity) in identities) {
            for (candidate in identities.values.toSet() + "unknown-nonblank-identity") {
                SQLiteDatabase.openDatabase(app.getDatabasePath("nema.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.version = version
                    it.execSQL("UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf(candidate))
                }
                val result = runCatching { inspectAlphaDatabase(app) }
                if (candidate == identity) assertEquals("version $version", DatabaseCompatibility.INCOMPATIBLE, result.getOrThrow())
                else assertTrue("version $version must reject identity $candidate", result.isFailure)
            }
        }
        assertEquals(app.beforeRows, snapshot(app))
        assertTrue(app.deletedNames.isEmpty())
    }

    @Test @Config(application = IoAlphaFixtureApplication::class)
    fun ioFailureHasNonDestructiveRetryThroughRealActivity() {
        assertEquals(DatabaseStartup.FAILED, app.databaseStartup.value)
        val activity = launch()
        try {
            compose.onNodeWithText("Reset and continue").assertDoesNotExist()
            assertTrue(app.getDatabasePath("nema.db").isDirectory)
            assertTrue(app.getDatabasePath("nema.db").delete()) // Remove test-owned injected IO obstacle, not user data.
            compose.onNodeWithText("Retry").performClick()
            await(DatabaseStartup.READY)
            assertTrue(app.deletedNames.isEmpty())
        } finally { activity.pause().stop().destroy() }
        compose.waitForIdle()
        proveUsableAndReopen()
    }

    @Test @Config(application = NewerAlphaFixtureApplication::class)
    fun newerDatabaseIsBlockedWithoutReset() {
        assertEquals(DatabaseStartup.NEWER, app.databaseStartup.value)
        val activity = launch()
        try {
            compose.onNodeWithText(app.getString(R.string.database_newer)).assertExists()
            compose.onNodeWithText("Reset and continue").assertDoesNotExist()
            assertUntouched()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun stickyServiceRestartCannotConstructRuntimeBehindGate() {
        assertEquals(DatabaseStartup.RESET_REQUIRED, app.databaseStartup.value)
        val service = Robolectric.buildService(XmppConnectionService::class.java).create()
        assertEquals(android.app.Service.START_NOT_STICKY, service.get().onStartCommand(null, 0, 1))
        service.destroy()
        assertUntouched()
    }

    private fun proveUsableAndReopen() {
        val sql = app.database.openHelper.writableDatabase
        assertEquals(31, sql.version)
        runBlocking { app.database.accountDao().upsert(AccountEntity("new", "new@example.org", "new", null, "example.org", null, null)) }
        app.database.close()
        val reopened = NemaDatabase.create(app)
        try {
            assertEquals("new", runBlocking { reopened.accountDao().allAccounts().single().id })
            reopened.openHelper.writableDatabase.query("PRAGMA quick_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            reopened.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { reopened.close() }
    }
}

class OlderAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "older" }
class WalAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "avatar-wal" }
class CanonicalAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "canonical" }
class FreshAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "fresh" }
class CorruptAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "corrupt" }
class IoAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "io" }
class NewerAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "newer" }
class UnknownIdentityAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "unknown-identity" }
class UnknownOlderAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "older-unknown-identity" }
class MismatchedOlderAlphaFixtureApplication : AlphaFixtureApplication() { override val mode = "older-mismatched-identity" }

class Released30ProtectedFixtureApplication : AlphaFixtureApplication() { override val mode = "released30" }
class Current31ProtectedFixtureApplication : AlphaFixtureApplication() { override val mode = "current31" }

open class AlphaFixtureApplication : NemaApplication() {
    protected open val mode = "avatar"
    var failDelete = false
    var throwDelete = false
    val deletedNames = mutableListOf<String>()
    var nativeDeletionRemovedAllSidecars = false
    var beforeHash = ""
    var beforeRows: Map<String, List<List<String?>>> = emptyMap()
    override fun onCreate() {
        when (mode) {
            "avatar", "avatar-wal", "canonical", "released30", "current31", "newer", "older", "unknown-identity", "older-unknown-identity", "older-mismatched-identity" -> {
                createHistoricalDatabase(this, avatar = mode.startsWith("avatar"), version = when {
                    mode.startsWith("older") -> 28
                    mode == "released30" -> 30
                    mode == "current31" -> 31
                    else -> 29
                })
                if (mode == "avatar-wal") createCommittedWalFixture(this)
                if (mode == "newer") SQLiteDatabase.openDatabase(getDatabasePath("nema.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 32 }
                if (mode.endsWith("unknown-identity")) SQLiteDatabase.openDatabase(getDatabasePath("nema.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("UPDATE room_master_table SET identity_hash = 'unrecognized-damaged-identity' WHERE id = 42")
                }
                if (mode == "older-mismatched-identity") SQLiteDatabase.openDatabase(getDatabasePath("nema.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("UPDATE room_master_table SET identity_hash = '5dc09db235dbbe5451b3d7b07b67ebb3' WHERE id = 42")
                }
                beforeRows = snapshot(this)
            }
            "corrupt" -> { getDatabasePath("nema.db").parentFile!!.mkdirs(); getDatabasePath("nema.db").writeText("not a sqlite database") }
            "io" -> getDatabasePath("nema.db").mkdirs()
        }
        if (getDatabasePath("nema.db").isFile) beforeHash = hash(getDatabasePath("nema.db"))
        getSharedPreferences("alpha-fixture", 0).edit().putString("setting", "keep").commit()
        File(noBackupFilesDir, "alpha-secret-sentinel").writeText("opaque credential fixture")
        File(filesDir, "unrelated").writeText("unrelated")
        super.onCreate()
    }
    override fun deleteDatabase(name: String): Boolean {
        deletedNames += name
        if (throwDelete) throw java.io.IOException("injected deletion failure")
        if (failDelete) return false
        return super.deleteDatabase(name).also {
            nativeDeletionRemovedAllSidecars = listOf("", "-wal", "-shm", "-journal").none { suffix -> File(getDatabasePath(name).path + suffix).exists() }
        }
    }
}

private fun hash(file: File): String = digest(file.readBytes())
private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun snapshot(context: android.content.Context): Map<String, List<List<String?>>> =
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

/** Restore a committed native SQLite file set as if its writer exited before checkpointing. */
private fun createCommittedWalFixture(context: android.content.Context, body: String = "committed WAL draft") {
    val file = context.getDatabasePath("nema.db")
    val captured = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        check(db.enableWriteAheadLogging())
        db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
        db.execSQL("UPDATE message_drafts SET body = ?", arrayOf(body))
        listOf("", "-wal", "-shm").associateWith { suffix -> File(file.path + suffix).readBytes() }
    }
    check(captured.getValue("-wal").isNotEmpty())
    captured.forEach { (suffix, bytes) -> File(file.path + suffix).writeBytes(bytes) }
}

internal fun createHistoricalDatabase(context: android.content.Context, avatar: Boolean, version: Int = 29) {
    val text = if (avatar) requireNotNull(AlphaDatabaseStartupTest::class.java.getResourceAsStream("/schema-history/avatar-29.json"))
        .bufferedReader().use { it.readText() }
    else context.assets.open("org.thanosapollo.nema.storage.NemaDatabase/$version.json").bufferedReader().use { it.readText() }
    val schema = JSONObject(text).getJSONObject("database")
    context.getDatabasePath("nema.db").parentFile!!.mkdirs()
    SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("nema.db"), null).use { db ->
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val views = schema.optJSONArray("views")
        if (views != null) for (i in 0 until views.length()) {
            val view = views.getJSONObject(i)
            db.execSQL(view.getString("createSql").replace("\${VIEW_NAME}", view.getString("viewName")))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        db.execSQL("INSERT INTO accounts (id,bareJid,authenticationId,serviceDomain) VALUES ('a','a@example.org','a','example.org')")
        db.execSQL("INSERT INTO peers (accountId,jid,room,lastReadLocalSequence,inRoster) VALUES ('a','peer@example.org',0,5,1)")
        db.execSQL("INSERT INTO message_threads VALUES ('a','peer@example.org','CHAT','local-only',NULL)")
        db.execSQL("""INSERT INTO messages (accountId,localMessageId,peerJid,senderJid,direction,messageKind,body,
            localSequence,markable,directSessionTransitionApplied,liveDeliveryObserved,unreadEligible,locallyRead)
            VALUES ('a','m','peer@example.org','a@example.org','OUTBOUND','CHAT','unsent body',6,1,1,1,1,0)""")
        db.execSQL("INSERT INTO message_drafts (accountId,peerJid,messageKind,threadKey,body) VALUES ('a','peer@example.org','CHAT','','draft body')")
        db.execSQL("INSERT INTO message_outbox VALUES ('a','op','m','origin','PENDING',NULL,2,'offline',NULL)")
        if (avatar) db.execSQL("INSERT INTO muc_avatars VALUES ('a','room@example.org','actor','photo',X'1234',NULL)")
        else if (version >= 29) {
            db.execSQL("INSERT INTO shared_threads VALUES ('a','peer@example.org','CHAT','local-only','example.org','incarnation','Shared title',17,1,0,0)")
            db.execSQL("INSERT INTO thread_directory_intents VALUES ('a','peer@example.org','CHAT','example.org','incarnation','local-only','pending-operation',17,'Pending title',NULL)")
        }
    }
}
