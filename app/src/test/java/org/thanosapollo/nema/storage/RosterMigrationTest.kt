package org.thanosapollo.nema.storage

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RosterMigrationTest {
    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NemaDatabase::class.java,
    )

    @Test
    fun migration22To23PreservesPeerAndDefaultsToNonRoster() {
        migrations.createDatabase(MIGRATION_DB, 22).apply {
            execSQL("INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) VALUES ('a', 'self@example.org', 'self', 'example.org')")
            execSQL(
                """INSERT INTO peers (accountId, jid, displayName, localNickname, photoMime,
                    photoBytes, photoSha1, vcardFetchedAtMs, vcardFailureAtMs, room,
                    lastReadLocalSequence) VALUES ('a', 'peer@example.org', 'VCard', 'Local',
                    'image/png', X'0102', 'sha', 11, 12, 1, 13)""",
            )
            close()
        }

        migrations.runMigrationsAndValidate(
            MIGRATION_DB, 23, true, MessageSchema.MIGRATION_22_23,
        ).use { db ->
            db.query("SELECT displayName, localNickname, photoBytes, photoSha1, vcardFetchedAtMs, vcardFailureAtMs, room, lastReadLocalSequence, rosterName, inRoster FROM peers").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("VCard", row.getString(0))
                assertEquals("Local", row.getString(1))
                assertArrayEquals(byteArrayOf(1, 2), row.getBlob(2))
                assertEquals("sha", row.getString(3))
                assertEquals(11L, row.getLong(4))
                assertEquals(12L, row.getLong(5))
                assertEquals(1, row.getInt(6))
                assertEquals(13L, row.getLong(7))
                assertNull(row.getString(8))
                assertEquals(0, row.getInt(9))
            }
        }
    }

    @Test
    fun completeSnapshotsAreAccountIsolatedAndPreservePeerAuthority() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "roster-${UUID.randomUUID()}.db"
        val db = NemaDatabase.create(context, name)
        try {
            db.accountDao().upsert(account("a"))
            db.accountDao().upsert(account("b"))
            db.openHelper.writableDatabase.execSQL(
                """INSERT INTO peers (accountId, jid, displayName, localNickname, photoMime,
                    photoBytes, photoSha1, vcardFetchedAtMs, vcardFailureAtMs, room,
                    lastReadLocalSequence) VALUES ('a', 'peer@example.org', 'VCard', 'Local',
                    'image/png', X'0102', 'sha', 11, 12, 1, 13)""",
            )
            db.openHelper.writableDatabase.execSQL(
                """INSERT INTO messages (accountId, localMessageId, peerJid, senderJid,
                    direction, messageKind, body, localSequence, markable,
                    directSessionTransitionApplied, liveDeliveryObserved, unreadEligible)
                    VALUES ('a', 'history', 'peer@example.org', 'peer@example.org',
                    'INBOUND', 'CHAT', 'kept', 1, 0, 0, 0, 1)""",
            )
            val store = RosterStore(db)
            store.reconcile(CompleteRosterSnapshot("b", listOf(RosterMember("peer@example.org", "Other"))))
            store.reconcile(
                CompleteRosterSnapshot(
                    "a",
                    listOf(RosterMember("z@example.org", "Z"), RosterMember("peer@example.org", "Server"), RosterMember("a@example.org", null)),
                ),
            )
            assertEquals(listOf("a@example.org", "peer@example.org", "z@example.org"), store.observe("a").first().map { it.jid })

            store.reconcile(CompleteRosterSnapshot("a", listOf(RosterMember("peer@example.org", "Renamed"))))
            store.reconcile(CompleteRosterSnapshot("a", listOf(RosterMember("peer@example.org", "Renamed"))))
            val peer = requireNotNull(db.messageDao().peer("a", "peer@example.org"))
            assertEquals("Renamed", peer.rosterName)
            assertEquals("VCard", peer.displayName)
            assertEquals("Local", peer.localNickname)
            assertEquals("image/png", peer.photoMime)
            assertArrayEquals(byteArrayOf(1, 2), peer.photoBytes)
            assertEquals(listOf("sha", 11L, 12L, true, 13L), listOf(peer.photoSha1, peer.vcardFetchedAtMs, peer.vcardFailureAtMs, peer.room, peer.lastReadLocalSequence))
            assertEquals(1, db.messageDao().messages("a").size)
            assertEquals(false, db.messageDao().peer("a", "z@example.org")?.inRoster)

            store.reconcile(CompleteRosterSnapshot("a", emptyList()))
            store.reconcile(CompleteRosterSnapshot("a", emptyList()))
            assertTrue(store.observe("a").first().isEmpty())
            assertEquals(listOf("peer@example.org"), store.observe("b").first().map { it.jid })
            assertEquals(1, db.messageDao().messages("a").size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun account(id: String) = AccountEntity(id, "$id@example.org", id, null, "example.org", null, null)

    private companion object {
        const val MIGRATION_DB = "roster-migration"
    }
}