package org.thanosapollo.nema.storage

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object MessageSchema {
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
