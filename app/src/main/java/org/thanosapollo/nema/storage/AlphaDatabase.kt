package org.thanosapollo.nema.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

internal const val NEMA_DATABASE_NAME = "nema.db"
internal enum class DatabaseCompatibility { COMPATIBLE, INCOMPATIBLE, NEWER }

// Exact identities from the retained Room exports in app/schemas (not migration support).
private val olderAlphaIdentities = mapOf(
    1 to "4955e45e81def49d3cc7680d5e9c6d99",
    2 to "9e0c2cd236e5097e72e3a9aa5a667ad0",
    3 to "af75e34659785394776eee9d2517e266",
    4 to "6cd6cc6a301e1a37a82f92a1fafc4c5b",
    5 to "1b7116e2ae0c157c7937fd069149e27e",
    6 to "bcccc2c8eab829b52d4290836f9d29c4",
    7 to "a2b2c4e3b46e7a349e7a818315a80a2d",
    8 to "3864ef93217bec9a40d899f460ec994d",
    9 to "fce5d0ff02ee8a60d3f294d714e169ad",
    10 to "d23fc53bdd80448bd05cf8366546e193",
    11 to "5750c58710fc1f8afc1d70852d76449f",
    12 to "6cc4a164738864946dee06396e285f44",
    13 to "0ae8091d612049ebf0dbf761f3cb6615",
    14 to "bf070c9e2597c819d8a72299da6c2f9f",
    15 to "2fe21876197a3b5eea2cac69609aae15",
    16 to "2fe21876197a3b5eea2cac69609aae15",
    17 to "ae8c292b3274bd374ed344650d2436aa",
    18 to "ae8c292b3274bd374ed344650d2436aa",
    19 to "e7c5edb2a16cc68ac665cfaca8b2260e",
    20 to "87cbc327d72cb1d5e74569d53795eae1",
    21 to "946212e47fdd5bae340dc03230ec9c91",
    22 to "ec1443e8ce2cfd085b0a2612bec17864",
    23 to "0bcd708bc6c5afaab2c39aee2b838b1e",
    24 to "85219e6fac97b8c48930e113334dda2a",
    25 to "546747c35576da69dd4e6ea6dc4fe0a0",
    26 to "e35febfb8493a7248ffd425f941b0f5f",
    27 to "5dc09db235dbbe5451b3d7b07b67ebb3",
    28 to "0812e81d9039042d4eef3249597ea62d",
)

/** Inspect without Room callbacks, migrations, or SQLite's default corruption deletion. */
internal fun inspectAlphaDatabase(context: Context): DatabaseCompatibility {
    val file = context.getDatabasePath(NEMA_DATABASE_NAME)
    if (!file.exists()) return DatabaseCompatibility.COMPATIBLE
    // Native rollback recovery needs writes even to read metadata. Do not create a missing
    // file, rewrite locale metadata, or invoke the default corruption-deletion handler.
    return SQLiteDatabase.openDatabase(file.path, null,
        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS, { }).use { db ->
        db.rawQuery("PRAGMA quick_check", null).use {
            check(it.moveToFirst() && it.getString(0) == "ok") { "Database integrity check failed" }
        }
        val version = db.version
        if (version > 31) return@use DatabaseCompatibility.NEWER
        val identity = db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null).use {
            check(it.moveToFirst()) { "Missing database identity" }
            it.getString(0).also { hash -> check(!hash.isNullOrBlank()) { "Missing database identity" } }
        }
        when {
            // A version alone is not proof of a known schema. Only exact retained
            // version/identity pairs may authorize the explicit alpha reset.
            olderAlphaIdentities[version] == identity -> DatabaseCompatibility.INCOMPATIBLE
            version in 29..30 && identity == "5e8e901ae2a87582117fb5276a3c5e9b" -> DatabaseCompatibility.COMPATIBLE
            version == 31 && identity == "09191c6875860ed97bff22694b097a4d" -> DatabaseCompatibility.COMPATIBLE
            // Only the known shipped collision proves incompatibility; an arbitrary changed
            // identity could be damaged metadata and must not authorize destructive recovery.
            version == 29 && identity == "d842cda2ce9467413942ebca6bfc3576" -> DatabaseCompatibility.INCOMPATIBLE
            else -> error("Unrecognized database metadata")
        }
    }
}

/** Only called before database consumers exist, after explicit startup confirmation. */
internal fun resetAlphaDatabase(context: Context) {
    val file = context.getDatabasePath(NEMA_DATABASE_NAME)
    val files = listOf(file, java.io.File("${file.path}-wal"), java.io.File("${file.path}-shm"), java.io.File("${file.path}-journal"))
    if (files.any { it.exists() }) {
        check(context.deleteDatabase(NEMA_DATABASE_NAME)) { "Database reset failed" }
        check(files.none { it.exists() }) { "Database reset incomplete" }
    }
}

/** Room must report corruption, never invoke the framework's delete-and-recreate handler. */
internal val preservingOpenHelperFactory = SupportSQLiteOpenHelper.Factory { configuration ->
    val delegate = configuration.callback
    FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
        .name(configuration.name)
        .callback(object : SupportSQLiteOpenHelper.Callback(delegate.version) {
            override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = delegate.onDowngrade(db, oldVersion, newVersion)
            override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)
            override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) { throw SQLiteDatabaseCorruptException("Nema database is corrupt; retained for recovery") }
        })
        .noBackupDirectory(configuration.useNoBackupDirectory)
        .allowDataLossOnRecovery(false)
        .build())
}
