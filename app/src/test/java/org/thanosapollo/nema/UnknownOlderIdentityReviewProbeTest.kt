package org.thanosapollo.nema

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.experimental.LazyApplication

@RunWith(RobolectricTestRunner::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [34], application = UnknownOlderIdentityReviewApplication::class)
class UnknownOlderIdentityReviewProbeTest {
    @Test fun unknownOlderSchemaMustNeverGrantResetPermission() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<UnknownOlderIdentityReviewApplication>()
        val state = withTimeout(10_000) { app.databaseStartup.first { it != DatabaseStartup.OPENING } }
        assertEquals("Unknown identity at version 28 must fail closed instead of granting destructive reset", DatabaseStartup.FAILED, state)
        app.resetDatabaseAndContinue().join()
        assertTrue(app.deletedNames.isEmpty())
    }
}
class UnknownOlderIdentityReviewApplication : NemaApplication() {
    val deletedNames = mutableListOf<String>()
    override fun onCreate() {
        createHistoricalDatabase(this, avatar = false, version = 28)
        SQLiteDatabase.openDatabase(getDatabasePath("nema.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE room_master_table SET identity_hash = 'unrecognized-damaged-identity' WHERE id = 42")
        }
        super.onCreate()
    }
    override fun deleteDatabase(name: String): Boolean { deletedNames += name; return super.deleteDatabase(name) }
}
