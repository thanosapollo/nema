package org.thanosapollo.nema.storage

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object MessageSchema {
    val MIGRATION_22_23: Migration = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE peers ADD COLUMN rosterName TEXT")
            db.execSQL("ALTER TABLE peers ADD COLUMN inRoster INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_21_22: Migration = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE message_reactions ADD COLUMN revision INTEGER NOT NULL DEFAULT 1",
            )
        }
    }

    val MIGRATION_20_21: Migration = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reconciliationObservedAtMs INTEGER")
            db.execSQL(
                """
                UPDATE messages
                SET reconciliationObservedAtMs = sentAtEpochMs
                WHERE sentTimeSource = 'LOCAL'
                  AND liveDeliveryObserved = 1
                  AND sentAtEpochMs IS NOT NULL
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS account_reconciliation_state (
                    accountId TEXT NOT NULL,
                    repairKey TEXT NOT NULL,
                    status TEXT NOT NULL,
                    wallFloorMs INTEGER NOT NULL,
                    beforeCount INTEGER,
                    afterCount INTEGER,
                    matchedCount INTEGER NOT NULL,
                    skippedCount INTEGER NOT NULL,
                    caughtErrorCount INTEGER NOT NULL,
                    PRIMARY KEY(accountId, repairKey),
                    FOREIGN KEY(accountId)
                        REFERENCES accounts(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO account_reconciliation_state (
                    accountId, repairKey, status, wallFloorMs, beforeCount, afterCount,
                    matchedCount, skippedCount, caughtErrorCount
                )
                SELECT accounts.id, ?, 'PENDING',
                       COALESCE((
                           SELECT MAX(messages.reconciliationObservedAtMs)
                           FROM messages WHERE messages.accountId = accounts.id
                       ), 0),
                       NULL, NULL, 0, 0, 0
                FROM accounts
                """.trimIndent(),
                arrayOf<Any>(IDENTITYLESS_LIVE_MAM_REPAIR),
            )
        }
    }

    val MIGRATION_19_20: Migration = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE messages ADD COLUMN unreadEligible INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                """
                UPDATE messages SET unreadEligible = 0
                WHERE direction = 'INBOUND'
                  AND (
                    (sentTimeSource = 'MAM' AND liveDeliveryObserved = 0)
                    OR (
                      (sentTimeSource IS NULL OR sentTimeSource != 'MAM')
                      AND EXISTS (
                        SELECT 1 FROM trusted_identity_aliases AS alias
                        WHERE alias.accountId = messages.accountId
                          AND alias.messageId = messages.localMessageId
                          AND alias.kind = 'MAM_RESULT'
                          AND alias.status = 'TRUSTED'
                      )
                    )
                  )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_18_19: Migration = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE messages ADD COLUMN liveDeliveryObserved INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    val MIGRATION_17_18: Migration = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE trusted_identity_aliases
                SET status = 'QUARANTINED', messageId = NULL
                WHERE kind = 'STANZA_ID'
                  AND status = 'TRUSTED'
                  AND EXISTS (
                    SELECT 1 FROM messages
                    WHERE messages.accountId = trusted_identity_aliases.accountId
                      AND messages.localMessageId = trusted_identity_aliases.messageId
                      AND messages.messageKind = 'GROUPCHAT'
                  )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_16_17: Migration = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_reactions (
                    accountId TEXT NOT NULL,
                    peerJid TEXT NOT NULL,
                    senderBareJid TEXT NOT NULL,
                    targetKey TEXT NOT NULL,
                    localMessageId TEXT,
                    wireTargetId TEXT NOT NULL,
                    emojis TEXT NOT NULL,
                    updatedAtMs INTEGER NOT NULL,
                    PRIMARY KEY(accountId, peerJid, senderBareJid, targetKey),
                    FOREIGN KEY(accountId)
                        REFERENCES accounts(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_message_reactions_accountId_peerJid
                ON message_reactions(accountId, peerJid)
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_15_16: Migration = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE peers SET lastReadLocalSequence = MAX(
                  lastReadLocalSequence,
                  COALESCE((
                    SELECT MAX(localSequence) FROM messages
                    WHERE messages.accountId = peers.accountId
                      AND messages.peerJid = peers.jid
                      AND messages.direction = 'OUTBOUND'
                      AND messages.messageKind = 'CHAT'
                      AND (messages.sentTimeSource IS NULL OR messages.sentTimeSource != 'MAM')
                  ), 0)
                )
                WHERE room = 0
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_14_15: Migration = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE peers ADD COLUMN lastReadLocalSequence INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                """
                UPDATE peers SET lastReadLocalSequence = (
                  SELECT COALESCE(MAX(localSequence), 0) FROM messages
                  WHERE messages.accountId = peers.accountId
                    AND messages.peerJid = peers.jid
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_13_14: Migration = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE messages ADD COLUMN directSessionTransitionApplied INTEGER NOT NULL DEFAULT 0",
            )
        }
    }

    val MIGRATION_12_13: Migration = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS direct_thread_sessions (
                    accountId TEXT NOT NULL,
                    peerJid TEXT NOT NULL,
                    messageKind TEXT NOT NULL,
                    threadId TEXT NOT NULL,
                    PRIMARY KEY(accountId, peerJid),
                    FOREIGN KEY(accountId, peerJid)
                        REFERENCES peers(accountId, jid)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(accountId, peerJid, messageKind, threadId)
                        REFERENCES message_threads(accountId, peerJid, messageKind, threadId)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    index_direct_thread_sessions_accountId_peerJid_messageKind_threadId
                ON direct_thread_sessions(accountId, peerJid, messageKind, threadId)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_thread_titles (
                    accountId TEXT NOT NULL,
                    peerJid TEXT NOT NULL,
                    messageKind TEXT NOT NULL,
                    threadId TEXT NOT NULL,
                    title TEXT NOT NULL,
                    PRIMARY KEY(accountId, peerJid, messageKind, threadId),
                    FOREIGN KEY(accountId, peerJid, messageKind, threadId)
                        REFERENCES message_threads(accountId, peerJid, messageKind, threadId)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN replaceId TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN correctionTargetMessageId TEXT")
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_messages_accountId_correctionTargetMessageId
                ON messages(accountId, correctionTargetMessageId)
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE message_outbox ADD COLUMN receiptStage TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN markable INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN markerTargetId TEXT")
        }
    }

    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sentAtEpochMs INTEGER")
            db.execSQL("ALTER TABLE messages ADD COLUMN sentTimeSource TEXT")
        }
    }

    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS archive_message_positions (
                    accountId TEXT NOT NULL,
                    archiveAuthority TEXT NOT NULL,
                    archiveScope TEXT NOT NULL,
                    archiveOrdinal INTEGER NOT NULL,
                    messageId TEXT NOT NULL,
                    PRIMARY KEY(accountId, archiveAuthority, archiveScope, archiveOrdinal),
                    FOREIGN KEY(accountId, messageId)
                        REFERENCES messages(accountId, localMessageId)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    index_archive_message_positions_accountId_archiveAuthority_archiveScope_messageId
                ON archive_message_positions(accountId, archiveAuthority, archiveScope, messageId)
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS index_archive_message_positions_accountId_messageId
                ON archive_message_positions(accountId, messageId)
                """.trimIndent(),
            )
            backfillArchivePositions(db)
            db.execSQL("ALTER TABLE archive_cursors ADD COLUMN oldestOrdinal INTEGER")
            db.execSQL("ALTER TABLE archive_cursors ADD COLUMN newestOrdinal INTEGER")
            db.execSQL(
                """
                UPDATE archive_cursors
                SET oldestId = NULL,
                    newestId = NULL,
                    hasEarlier = 1,
                    retryableError = 'Archive cursor reset during migration',
                    oldestOrdinal = NULL,
                    newestOrdinal = NULL
                """.trimIndent(),
            )
            db.execSQL("DROP INDEX index_messages_accountId_archiveOrdinal")
        }
    }

    val REOPEN_CALLBACK: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE message_outbox SET status = 'UNCERTAIN' WHERE status = 'IN_FLIGHT'",
            )
        }
    }

    private data class ArchivedMessage(
        val accountId: String,
        val messageId: String,
        val ordinal: Long,
        val accountBareJid: String,
    )

    private fun backfillArchivePositions(db: SupportSQLiteDatabase) {
        val messages = db.query(
            """
            SELECT messages.accountId, messages.localMessageId,
                   messages.archiveOrdinal, accounts.bareJid
            FROM messages
            INNER JOIN accounts ON accounts.id = messages.accountId
            WHERE messages.archiveOrdinal IS NOT NULL
            """.trimIndent(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ArchivedMessage(
                            accountId = cursor.getString(0),
                            messageId = cursor.getString(1),
                            ordinal = cursor.getLong(2),
                            accountBareJid = cursor.getString(3),
                        ),
                    )
                }
            }
        }
        messages.forEach { message ->
            val decoded = validArchiveAliases(db, message).ifEmpty {
                setOf(message.accountBareJid to "ACCOUNT")
            }
            decoded.forEach { (authority, scope) ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO archive_message_positions(
                        accountId, archiveAuthority, archiveScope, archiveOrdinal, messageId
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any>(
                        message.accountId,
                        authority,
                        scope,
                        message.ordinal,
                        message.messageId,
                    ),
                )
            }
        }
    }

    private fun validArchiveAliases(
        db: SupportSQLiteDatabase,
        message: ArchivedMessage,
    ): Set<Pair<String, String>> = db.query(
        """
        SELECT authority FROM trusted_identity_aliases
        WHERE accountId = ? AND messageId = ?
          AND kind = 'MAM_RESULT' AND status = 'TRUSTED'
        ORDER BY authority
        """.trimIndent(),
        arrayOf(message.accountId, message.messageId),
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) decodeArchiveAlias(cursor.getString(0))?.let(::add)
        }
    }

    private fun decodeArchiveAlias(encoded: String): Pair<String, String>? {
        val separator = encoded.indexOf(':')
        if (separator <= 0) return null
        val prefix = encoded.substring(0, separator)
        if (prefix.any { it !in '0'..'9' }) return null
        val authorityLength = prefix.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val payload = encoded.substring(separator + 1)
        if (payload.length <= authorityLength) return null
        val authority = payload.substring(0, authorityLength)
        val scope = payload.substring(authorityLength)
        if (!authority.hasWellFormedUtf16() || !scope.hasWellFormedUtf16()) return null
        return (authority to scope).takeIf {
            encoded == "${authority.length}:$authority$scope"
        }
    }

    private fun String.hasWellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val character = this[index]
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                character.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }
}
