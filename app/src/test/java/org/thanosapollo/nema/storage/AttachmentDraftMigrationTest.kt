package org.thanosapollo.nema.storage

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
class AttachmentDraftMigrationTest {
    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NemaDatabase::class.java)

    @Test
    fun exported23UpgradesThroughRegisteredMigrationAndKeepsReply() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "attachment-migration-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 23).use { db ->
                db.execSQL("INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) VALUES ('a', 'self@example.org', 'self', 'example.org')")
                db.execSQL("INSERT INTO peers (accountId, jid, room, lastReadLocalSequence, inRoster) VALUES ('a', 'peer@example.org', 0, 0, 0)")
                db.execSQL("""INSERT INTO message_drafts (accountId, peerJid, messageKind, threadKey, body,
                    replyToId, replyToJid, replyFallbackBody, replyFallbackSender)
                    VALUES ('a', 'peer@example.org', 'CHAT', '6:parentchild', 'caption', 'id', 'peer@example.org', 'quote', 'Peer')""")
                db.query("PRAGMA table_info(message_drafts)").use { columns ->
                    while (columns.moveToNext()) assertTrue(!columns.getString(1).startsWith("attachment"))
                }
            }
            // Opening normally proves the production builder registered the upgrade.
            var db = NemaDatabase.create(context, name)
            try {
                assertEquals(27, db.openHelper.writableDatabase.version)
                val draft = requireNotNull(db.messageDao().draft("a", "peer@example.org", "6:parentchild"))
                assertEquals("caption", draft.body)
                assertEquals(listOf("id", "peer@example.org", "quote", "Peer"),
                    listOf(draft.replyToId, draft.replyToJid, draft.replyFallbackBody, draft.replyFallbackSender))
                assertEquals(listOf(null, null, null, null),
                    listOf(draft.attachmentUrl, draft.attachmentName, draft.attachmentMime, draft.attachmentSize))
            } finally {
                db.close()
            }
            migrations.runMigrationsAndValidate(name, 27, true, MessageSchema.MIGRATION_23_24, MessageSchema.MIGRATION_24_25, MessageSchema.MIGRATION_25_26, MessageSchema.MIGRATION_26_27).close()
            db = NemaDatabase.create(context, name)
            try {
                assertNull(db.messageDao().draft("a", "peer@example.org", ""))
                assertEquals("caption", db.messageDao().draft("a", "peer@example.org", "6:parentchild")?.body)
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
