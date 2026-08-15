package org.thanosapollo.nema.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NemaDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NemaDatabase::class.java,
    )

    @Test
    fun migration4To5PreservesPeerAndStartsWithoutLocalNickname() {
        helper.createDatabase(DATABASE_NAME, 4).apply {
            execSQL(
                """
                INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain)
                VALUES ('account-a', 'self@example.org', 'self', 'example.org')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO peers (accountId, jid, displayName, vcardFetchedAtMs)
                VALUES ('account-a', 'peer@example.org', 'Remote name', 123)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 5, true).use { database ->
            database.query(
                """
                SELECT displayName, localNickname, vcardFetchedAtMs
                FROM peers
                WHERE accountId = 'account-a' AND jid = 'peer@example.org'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Remote name", cursor.getString(0))
                assertNull(cursor.getString(1))
                assertEquals(123L, cursor.getLong(2))
            }
        }
    }

    @Test
    fun migration8To9QualifiesExistingRoomArchivePosition() {
        val room = "room@conference.example.org"
        val account = "self@example.org"
        val roomArchive = "${room.length}:$room$room"
        val accountArchive = "${account.length}:${account}ACCOUNT"
        val unicodeAuthority = "𠀀room@example.org"
        val unicodeArchive = "${unicodeAuthority.length}:$unicodeAuthority$unicodeAuthority"
        helper.createDatabase(DATABASE_NAME, 8).apply {
            execSQL(
                """
                INSERT INTO accounts (
                    id, bareJid, authenticationId, serviceDomain
                ) VALUES ('account-a', 'self@example.org', 'self', 'example.org')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO peers (accountId, jid, room)
                VALUES ('account-a', ?, 1), ('account-a', 'peer@example.org', 0)
                """.trimIndent(),
                arrayOf(room),
            )
            execSQL(
                """
                INSERT INTO messages (
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, body, localSequence, archiveOrdinal
                ) VALUES ('account-a', 'room-message', ?, ?, 'INBOUND',
                    'GROUPCHAT', 'history', 0, 0)
                """.trimIndent(),
                arrayOf(room, "$room/Alice"),
            )
            execSQL(
                """
                INSERT INTO trusted_identity_aliases (
                    accountId, kind, authority, value, messageId, status
                ) VALUES
                    ('account-a', 'MAM_RESULT', ?, 'room-r1', 'room-message', 'TRUSTED'),
                    ('account-a', 'MAM_RESULT', ?, 'room-r2', 'room-message', 'TRUSTED'),
                    ('account-a', 'MAM_RESULT', ?, 'account-r1', 'room-message', 'TRUSTED')
                """.trimIndent(),
                arrayOf(roomArchive, roomArchive, accountArchive),
            )
            execSQL(
                """
                INSERT INTO archive_cursors (
                    accountId, archiveAuthority, scope, oldestId, newestId,
                    hasEarlier, retryableError
                ) VALUES
                    ('account-a', ?, ?, 'room-r1', 'room-r1', 0, NULL),
                    ('account-a', ?, 'ACCOUNT', 'account-r1', 'account-r1', 0, NULL)
                """.trimIndent(),
                arrayOf(room, room, account),
            )
            val malformedAliases = listOf(
                "1x:ab",
                "+1:ab",
                "01:ab",
                "5:ab",
                "1:a",
                "1:😀ACCOUNT",
            )
            malformedAliases.forEachIndexed { index, encoded ->
                val sequence = index + 1L
                val messageId = "malformed-$index"
                execSQL(
                    """
                    INSERT INTO messages (
                        accountId, localMessageId, peerJid, senderJid, direction,
                        messageKind, body, localSequence, archiveOrdinal
                    ) VALUES ('account-a', ?, 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any>(messageId, messageId, sequence, sequence),
                )
                execSQL(
                    """
                    INSERT INTO trusted_identity_aliases (
                        accountId, kind, authority, value, messageId, status
                    ) VALUES ('account-a', 'MAM_RESULT', ?, ?, ?, 'TRUSTED')
                    """.trimIndent(),
                    arrayOf(encoded, "bad-r$index", messageId),
                )
            }
            execSQL(
                """
                INSERT INTO messages (
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, body, localSequence, archiveOrdinal
                ) VALUES ('account-a', 'missing-alias', 'peer@example.org',
                    'peer@example.org', 'INBOUND', 'CHAT', 'missing', 7, 7)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages (
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, body, localSequence, archiveOrdinal
                ) VALUES ('account-a', 'unicode-alias', 'peer@example.org',
                    'peer@example.org', 'INBOUND', 'CHAT', 'unicode', 8, 8)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO trusted_identity_aliases (
                    accountId, kind, authority, value, messageId, status
                ) VALUES ('account-a', 'MAM_RESULT', ?, 'unicode-r1',
                    'unicode-alias', 'TRUSTED')
                """.trimIndent(),
                arrayOf(unicodeArchive),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            9,
            true,
            MessageSchema.MIGRATION_8_9,
        ).use { database ->
            database.query(
                """
                SELECT archiveAuthority, archiveScope, archiveOrdinal
                FROM archive_message_positions
                WHERE accountId = 'account-a' AND messageId = 'room-message'
                """.trimIndent(),
            ).use { cursor ->
                val positions = buildSet {
                    while (cursor.moveToNext()) {
                        add(Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                    }
                }
                assertEquals(
                    setOf(
                        Triple(room, room, 0L),
                        Triple(account, "ACCOUNT", 0L),
                    ),
                    positions,
                )
            }
            database.query(
                """
                SELECT messageId, archiveAuthority, archiveScope, archiveOrdinal
                FROM archive_message_positions
                WHERE accountId = 'account-a'
                  AND (messageId LIKE 'malformed-%' OR messageId = 'missing-alias')
                ORDER BY archiveOrdinal
                """.trimIndent(),
            ).use { cursor ->
                var expectedOrdinal = 1L
                while (cursor.moveToNext()) {
                    assertEquals(account, cursor.getString(1))
                    assertEquals("ACCOUNT", cursor.getString(2))
                    assertEquals(expectedOrdinal, cursor.getLong(3))
                    expectedOrdinal++
                }
                assertEquals(8L, expectedOrdinal)
            }
            database.query(
                """
                SELECT archiveAuthority, archiveScope, archiveOrdinal
                FROM archive_message_positions
                WHERE accountId = 'account-a' AND messageId = 'unicode-alias'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(unicodeAuthority, cursor.getString(0))
                assertEquals(unicodeAuthority, cursor.getString(1))
                assertEquals(8L, cursor.getLong(2))
            }
            database.query(
                """
                SELECT oldestId, newestId, hasEarlier, retryableError,
                       oldestOrdinal, newestOrdinal
                FROM archive_cursors
                WHERE accountId = 'account-a'
                ORDER BY archiveAuthority, scope
                """.trimIndent(),
            ).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    assertNull(cursor.getString(0))
                    assertNull(cursor.getString(1))
                    assertEquals(1, cursor.getInt(2))
                    assertEquals("Archive cursor reset during migration", cursor.getString(3))
                    assertTrue(cursor.isNull(4))
                    assertTrue(cursor.isNull(5))
                    count++
                }
                assertEquals(2, count)
            }
        }
    }

    @Test
    fun migration9To10PreservesMessagesWithoutInventingSentTime() {
        helper.createDatabase(DATABASE_NAME, 9).apply {
            execSQL(
                """
                INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain)
                VALUES ('account-a', 'self@example.org', 'self', 'example.org')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO peers (accountId, jid, room)
                VALUES ('account-a', 'peer@example.org', 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages (
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, body, localSequence, archiveOrdinal
                ) VALUES ('account-a', 'legacy', 'peer@example.org',
                    'peer@example.org', 'INBOUND', 'CHAT', 'legacy', 1, NULL)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            10,
            true,
            MessageSchema.MIGRATION_9_10,
        ).use { database ->
            database.query(
                "SELECT sentAtEpochMs, sentTimeSource FROM messages WHERE localMessageId = 'legacy'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun migration10To11PreservesRowsAndAddsNeutralReceiptMarkerState() {
        helper.createDatabase(DATABASE_NAME, 10).apply {
            execSQL(
                "INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) " +
                    "VALUES ('account-a', 'self@example.org', 'self', 'example.org')",
            )
            execSQL(
                "INSERT INTO peers (accountId, jid, room) " +
                    "VALUES ('account-a', 'peer@example.org', 0)",
            )
            execSQL(
                """
                INSERT INTO messages (
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, body, localSequence
                ) VALUES ('account-a', 'legacy', 'peer@example.org',
                    'self@example.org', 'OUTBOUND', 'CHAT', 'legacy', 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO message_outbox (
                    accountId, operationId, messageId, originId, status, attempt
                ) VALUES ('account-a', 'op-1', 'legacy', 'origin-1', 'SENT', 1)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            11,
            true,
            MessageSchema.MIGRATION_10_11,
        ).use { database ->
            database.query(
                "SELECT markable, markerTargetId FROM messages WHERE localMessageId = 'legacy'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
            }
            database.query(
                "SELECT receiptStage FROM message_outbox WHERE operationId = 'op-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "nema-migration-test"
    }
}
