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

    @Test
    fun migration11To12PreservesMessagesAndAddsNeutralCorrectionState() {
        helper.createDatabase(DATABASE_NAME, 11).apply {
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
                    messageKind, body, localSequence, markable
                ) VALUES ('account-a', 'legacy', 'peer@example.org',
                    'peer@example.org', 'INBOUND', 'CHAT', 'legacy', 1, 0)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            12,
            true,
            MessageSchema.MIGRATION_11_12,
        ).use { database ->
            database.query(
                "SELECT body, replaceId, correctionTargetMessageId FROM messages WHERE localMessageId = 'legacy'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    @Test
    fun migration12To13AddsScopedSessionAndTitleTablesWithWorkingCascades() {
        helper.createDatabase(DATABASE_NAME, 12).apply {
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
                INSERT INTO message_threads (
                    accountId, peerJid, messageKind, threadId, parentThreadId
                ) VALUES ('account-a', 'peer@example.org', 'CHAT', 'session-a', NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages (
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, threadId, parentThreadId, body, localSequence, markable
                ) VALUES ('account-a', 'legacy', 'peer@example.org', 'peer@example.org',
                    'INBOUND', 'CHAT', 'session-a', NULL, 'legacy', 1, 0)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            13,
            true,
            MessageSchema.MIGRATION_12_13,
        ).use { database ->
            enableAndAssertForeignKeys(database)
            database.query("SELECT body FROM messages WHERE localMessageId = 'legacy'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy", cursor.getString(0))
            }
            database.execSQL(
                """
                INSERT INTO direct_thread_sessions(accountId, peerJid, messageKind, threadId)
                VALUES ('account-a', 'peer@example.org', 'CHAT', 'session-a')
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO message_thread_titles(accountId, peerJid, messageKind, threadId, title)
                VALUES ('account-a', 'peer@example.org', 'CHAT', 'session-a', 'Custom title')
                """.trimIndent(),
            )
            assertForeignKeysClean(database)
            database.execSQL("DELETE FROM peers WHERE accountId = 'account-a' AND jid = 'peer@example.org'")
            assertTableEmpty(database, "direct_thread_sessions")
            assertTableEmpty(database, "message_thread_titles")
            assertTableEmpty(database, "message_threads")
            assertTableEmpty(database, "messages")
            assertForeignKeysClean(database)
        }
    }

    @Test
    fun migration13To14PreservesThreadStateAndAddsNeutralTransitionMarker() {
        helper.createDatabase(DATABASE_NAME, 13).apply {
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
                INSERT INTO message_threads (
                    accountId, peerJid, messageKind, threadId, parentThreadId
                ) VALUES ('account-a', 'peer@example.org', 'CHAT', 'session-a', NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages (
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, threadId, parentThreadId, body, localSequence, markable
                ) VALUES ('account-a', 'legacy', 'peer@example.org', 'peer@example.org',
                    'INBOUND', 'CHAT', 'session-a', NULL, 'legacy', 1, 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO direct_thread_sessions(accountId, peerJid, messageKind, threadId)
                VALUES ('account-a', 'peer@example.org', 'CHAT', 'session-a')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO message_thread_titles(accountId, peerJid, messageKind, threadId, title)
                VALUES ('account-a', 'peer@example.org', 'CHAT', 'session-a', 'Custom title')
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            14,
            true,
            MessageSchema.MIGRATION_13_14,
        ).use { database ->
            database.query(
                "SELECT body, directSessionTransitionApplied FROM messages WHERE localMessageId = 'legacy'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            database.query("SELECT threadId FROM direct_thread_sessions").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("session-a", cursor.getString(0))
            }
            database.query("SELECT title FROM message_thread_titles").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Custom title", cursor.getString(0))
            }
            assertForeignKeysClean(database)
        }
    }

    @Test
    fun migration14To15BackfillsLastReadToCurrentMaxSequence() {
        helper.createDatabase(DATABASE_NAME, 14).apply {
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
                INSERT INTO message_threads (
                    accountId, peerJid, messageKind, threadId, parentThreadId
                ) VALUES ('account-a', 'peer@example.org', 'CHAT', 'session-a', NULL)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages (
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, threadId, parentThreadId, body, localSequence, markable,
                    directSessionTransitionApplied
                ) VALUES ('account-a', 'legacy', 'peer@example.org', 'peer@example.org',
                    'INBOUND', 'CHAT', 'session-a', NULL, 'legacy', 7, 0, 0)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            15,
            true,
            MessageSchema.MIGRATION_14_15,
        ).use { database ->
            database.query(
                "SELECT lastReadLocalSequence FROM peers WHERE jid = 'peer@example.org'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
            }
            assertForeignKeysClean(database)
        }
    }

    @Test
    fun migration18To19StartsExistingMessagesWithoutLiveReceiptDelivery() {
        helper.createDatabase(DATABASE_NAME, 18).apply {
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
                    messageKind, body, localSequence, markable, directSessionTransitionApplied
                ) VALUES (
                    'account-a', 'old-message', 'peer@example.org', 'peer@example.org',
                    'INBOUND', 'CHAT', 'old', 1, 0, 0
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            19,
            true,
            MessageSchema.MIGRATION_18_19,
        ).use { database ->
            database.query(
                "SELECT liveDeliveryObserved FROM messages WHERE localMessageId = 'old-message'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            assertForeignKeysClean(database)
        }
    }

    @Test
    fun migration19To20MarksOnlyMamOnlyInboundHistoryRead() {
        helper.createDatabase(DATABASE_NAME, 19).apply {
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
                    messageKind, body, localSequence, sentAtEpochMs, sentTimeSource,
                    markable, directSessionTransitionApplied, liveDeliveryObserved
                ) VALUES
                    ('account-a', 'history', 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', 'history', 1, 1000, 'MAM', 0, 0, 0),
                    ('account-a', 'live-before-mam', 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', 'live', 2, 2000, 'MAM', 0, 0, 1),
                    ('account-a', 'local', 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', 'local', 3, 3000, 'LOCAL', 0, 0, 0),
                    ('account-a', 'outbound-history', 'peer@example.org', 'self@example.org',
                        'OUTBOUND', 'CHAT', 'outbound', 4, 4000, 'MAM', 0, 0, 0),
                    ('account-a', 'mam-without-delay', 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', 'delayless', 5, 5000, 'LOCAL', 0, 0, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO trusted_identity_aliases (
                    accountId, kind, authority, value, messageId, status
                ) VALUES ('account-a', 'MAM_RESULT', 'archive', 'delayless-result',
                    'mam-without-delay', 'TRUSTED')
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            20,
            true,
            MessageSchema.MIGRATION_19_20,
        ).use { database ->
            database.query(
                "SELECT localMessageId, unreadEligible FROM messages ORDER BY localSequence",
            ).use { cursor ->
                val eligibility = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1))
                }
                assertEquals(
                    listOf(
                        "history" to 0,
                        "live-before-mam" to 1,
                        "local" to 1,
                        "outbound-history" to 1,
                        "mam-without-delay" to 0,
                    ),
                    eligibility,
                )
            }
            assertForeignKeysClean(database)
        }
    }

    @Test
    fun migration20To21BackfillsLiveObservationAndCreatesPendingAccountState() {
        helper.createDatabase(DATABASE_NAME, 20).apply {
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
                    messageKind, body, localSequence, sentAtEpochMs, sentTimeSource,
                    liveDeliveryObserved, markable, directSessionTransitionApplied, unreadEligible
                ) VALUES
                    ('account-a', 'live-local', 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', 'live', 1, 1000, 'LOCAL', 1, 0, 0, 1),
                    ('account-a', 'local-not-live', 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', 'not-live', 2, 2000, 'LOCAL', 0, 0, 0, 1),
                    ('account-a', 'mam-live', 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', 'mam', 3, 3000, 'MAM', 1, 0, 0, 1),
                    ('account-a', 'live-no-time', 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', 'no-time', 4, NULL, 'LOCAL', 1, 0, 0, 1)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            21,
            true,
            MessageSchema.MIGRATION_20_21,
        ).use { database ->
            database.query(
                """
                SELECT COUNT(*) FROM messages
                WHERE (localMessageId = 'live-local' AND reconciliationObservedAtMs = 1000)
                   OR (localMessageId IN ('local-not-live', 'mam-live', 'live-no-time')
                       AND reconciliationObservedAtMs IS NULL)
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }
            database.query(
                """
                SELECT COUNT(*) FROM account_reconciliation_state
                WHERE accountId = 'account-a'
                  AND repairKey = 'identityless-live-mam-v1' AND status = 'PENDING'
                  AND wallFloorMs = 1000 AND beforeCount IS NULL AND afterCount IS NULL
                  AND matchedCount = 0 AND skippedCount = 0 AND caughtErrorCount = 0
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            assertForeignKeysClean(database)
        }
    }

    @Test
    fun migration21To22PreservesReactionRowsAndDefaultsRevision() {
        helper.createDatabase(DATABASE_NAME, 21).apply {
            execSQL("INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) " +
                "VALUES ('account-a', 'self@example.org', 'self', 'example.org')")
            execSQL(
                """
                INSERT INTO message_reactions (
                    accountId, peerJid, senderBareJid, targetKey, localMessageId,
                    wireTargetId, emojis, updatedAtMs
                ) VALUES
                    ('account-a', 'peer@example.org', 'one@example.org', 'visible',
                        'message-visible', 'wire-visible', '👍', 101),
                    ('account-a', 'peer@example.org', 'two@example.org', 'pending-nonempty',
                        NULL, 'wire-pending', '🔥', 102),
                    ('account-a', 'peer@example.org', 'three@example.org', 'pending-empty',
                        NULL, 'wire-empty', '', 103)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(DATABASE_NAME, 22, true, MessageSchema.MIGRATION_21_22).use { database ->
            database.query(
                "SELECT targetKey, localMessageId, wireTargetId, emojis, updatedAtMs, revision " +
                    "FROM message_reactions ORDER BY updatedAtMs",
            ).use { cursor ->
                val rows = buildList {
                    while (cursor.moveToNext()) add((0..5).map { cursor.getString(it) })
                }
                assertEquals(
                    listOf(
                        listOf("visible", "message-visible", "wire-visible", "👍", "101", "1"),
                        listOf("pending-nonempty", null, "wire-pending", "🔥", "102", "1"),
                        listOf("pending-empty", null, "wire-empty", "", "103", "1"),
                    ),
                    rows,
                )
            }
            database.execSQL(
                """
                INSERT INTO message_reactions (
                    accountId, peerJid, senderBareJid, targetKey, localMessageId,
                    wireTargetId, emojis, updatedAtMs
                ) VALUES ('account-a', 'peer@example.org', 'fresh@example.org', 'fresh',
                    NULL, 'wire-fresh', '', 104)
                """.trimIndent(),
            )
            database.query("SELECT revision FROM message_reactions WHERE targetKey = 'fresh'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migration17To18QuarantinesOnlyPersistedRoomStanzaIds() {
        helper.createDatabase(DATABASE_NAME, 17).apply {
            execSQL(
                "INSERT INTO accounts (id, bareJid, authenticationId, serviceDomain) " +
                    "VALUES ('account-a', 'self@example.org', 'self', 'example.org')",
            )
            execSQL(
                """
                INSERT INTO peers (accountId, jid, room)
                VALUES
                    ('account-a', 'peer@example.org', 0),
                    ('account-a', 'room@conference.example.org', 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages (
                    accountId, localMessageId, peerJid, senderJid, direction,
                    messageKind, body, localSequence, markable, directSessionTransitionApplied
                ) VALUES
                    ('account-a', 'direct-message', 'peer@example.org', 'peer@example.org',
                        'INBOUND', 'CHAT', 'direct', 1, 0, 0),
                    ('account-a', 'room-message', 'room@conference.example.org',
                        'room@conference.example.org/alice', 'INBOUND', 'GROUPCHAT', 'room', 2, 0, 0)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO trusted_identity_aliases (
                    accountId, kind, authority, value, messageId, status
                ) VALUES
                    ('account-a', 'STANZA_ID', 'self@example.org', 'direct-stanza',
                        'direct-message', 'TRUSTED'),
                    ('account-a', 'ORIGIN_ID', 'room@conference.example.org', 'room-origin',
                        'room-message', 'TRUSTED'),
                    ('account-a', 'STANZA_ID', 'room@conference.example.org', 'room-stanza',
                        'room-message', 'TRUSTED')
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            18,
            true,
            MessageSchema.MIGRATION_17_18,
        ).use { database ->
            database.query(
                """
                SELECT value, status, messageId
                FROM trusted_identity_aliases
                ORDER BY value
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToNext())
                assertEquals("direct-stanza", cursor.getString(0))
                assertEquals("TRUSTED", cursor.getString(1))
                assertEquals("direct-message", cursor.getString(2))

                assertTrue(cursor.moveToNext())
                assertEquals("room-origin", cursor.getString(0))
                assertEquals("TRUSTED", cursor.getString(1))
                assertEquals("room-message", cursor.getString(2))

                assertTrue(cursor.moveToNext())
                assertEquals("room-stanza", cursor.getString(0))
                assertEquals("QUARANTINED", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertTrue(!cursor.moveToNext())
            }
            assertForeignKeysClean(database)
        }
    }

    private fun assertTableEmpty(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String) {
        database.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    private fun assertForeignKeysClean(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.query("PRAGMA foreign_key_check").use { cursor ->
            assertTrue(!cursor.moveToFirst())
        }
    }

    private fun enableAndAssertForeignKeys(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("PRAGMA foreign_keys = ON")
        database.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    private companion object {
        const val DATABASE_NAME = "nema-migration-test"
    }
}
