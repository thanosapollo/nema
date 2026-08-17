package org.thanosapollo.nema.storage

internal const val CHEAP_CONVERSATION_SUMMARIES = """
        WITH positioned AS (
          SELECT messages.accountId AS accountId,
            messages.localMessageId AS localMessageId,
            messages.peerJid AS peerJid,
            messages.senderJid AS senderJid,
            messages.body AS body,
            messages.localSequence AS localSequence,
            messages.messageKind AS messageKind,
            messages.direction AS direction,
            messages.sentAtEpochMs AS sentAtEpochMs,
            messages.sentTimeSource AS sentTimeSource,
            CASE
              WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
              ELSE accounts.bareJid
            END AS archiveAuthority,
            CASE
              WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
              ELSE 'ACCOUNT'
            END AS archiveScope,
            position.archiveOrdinal AS conversationArchiveOrdinal
          FROM messages
          JOIN accounts ON accounts.id = messages.accountId
          LEFT JOIN archive_message_positions AS position
            ON position.accountId = messages.accountId
           AND position.messageId = messages.localMessageId
           AND position.archiveAuthority = CASE
             WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
             ELSE accounts.bareJid
           END
           AND position.archiveScope = CASE
             WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
             ELSE 'ACCOUNT'
           END
          WHERE messages.accountId = :accountId
            AND messages.messageKind IN ('CHAT', 'GROUPCHAT')
            AND messages.replaceId IS NULL
        ),
        latest_archive_ordinals AS (
          SELECT peerJid, archiveAuthority, archiveScope,
            MAX(conversationArchiveOrdinal) AS conversationArchiveOrdinal
          FROM positioned
          WHERE conversationArchiveOrdinal IS NOT NULL
          GROUP BY peerJid, archiveAuthority, archiveScope
        ),
        latest_archived AS (
          SELECT positioned.*
          FROM positioned
          JOIN latest_archive_ordinals AS latest
            ON latest.peerJid = positioned.peerJid
           AND latest.archiveAuthority = positioned.archiveAuthority
           AND latest.archiveScope = positioned.archiveScope
           AND latest.conversationArchiveOrdinal = positioned.conversationArchiveOrdinal
        ),
        latest_loose_times AS (
          SELECT peerJid,
            MAX(sentAtEpochMs IS NOT NULL) AS hasSentTime,
            MAX(sentAtEpochMs) AS sentAtEpochMs
          FROM positioned
          WHERE conversationArchiveOrdinal IS NULL
          GROUP BY peerJid
        ),
        latest_loose_sequences AS (
          SELECT positioned.peerJid AS peerJid,
            MAX(positioned.localSequence) AS localSequence
          FROM positioned AS positioned
          JOIN latest_loose_times AS latest
            ON latest.peerJid = positioned.peerJid
           AND (
             (latest.hasSentTime = 0 AND positioned.sentAtEpochMs IS NULL)
             OR (latest.hasSentTime = 1 AND positioned.sentAtEpochMs = latest.sentAtEpochMs)
           )
          WHERE positioned.conversationArchiveOrdinal IS NULL
          GROUP BY positioned.peerJid
        ),
        latest_loose AS (
          SELECT positioned.*
          FROM positioned
          JOIN latest_loose_sequences AS latest
            ON latest.peerJid = positioned.peerJid
           AND latest.localSequence = positioned.localSequence
        ),
        candidates AS (
          SELECT * FROM latest_archived
          UNION ALL
          SELECT * FROM latest_loose
        ),
        latest_messages AS (
          SELECT candidate.*
          FROM candidates AS candidate
          WHERE NOT EXISTS (
            SELECT 1 FROM candidates AS newer
            WHERE newer.peerJid = candidate.peerJid
              AND (
                (newer.sentAtEpochMs IS NOT NULL AND candidate.sentAtEpochMs IS NULL)
                OR (newer.sentAtEpochMs > candidate.sentAtEpochMs)
                OR (
                  (newer.sentAtEpochMs = candidate.sentAtEpochMs
                    OR (newer.sentAtEpochMs IS NULL AND candidate.sentAtEpochMs IS NULL))
                  AND newer.conversationArchiveOrdinal IS NOT NULL
                  AND candidate.conversationArchiveOrdinal IS NULL
                )
                OR (
                  (newer.sentAtEpochMs = candidate.sentAtEpochMs
                    OR (newer.sentAtEpochMs IS NULL AND candidate.sentAtEpochMs IS NULL))
                  AND (
                    (newer.conversationArchiveOrdinal IS NULL) =
                      (candidate.conversationArchiveOrdinal IS NULL)
                  )
                  AND (
                    newer.localSequence > candidate.localSequence
                    OR (newer.localSequence = candidate.localSequence
                      AND newer.localMessageId > candidate.localMessageId)
                  )
                )
              )
          )
        )
        SELECT messages.localMessageId AS localMessageId,
          messages.peerJid AS peerJid,
          messages.senderJid AS senderJid,
          COALESCE(
            (
              SELECT correction.body
              FROM messages AS correction
              WHERE correction.accountId = messages.accountId
                AND correction.correctionTargetMessageId = messages.localMessageId
              ORDER BY correction.sentAtEpochMs IS NULL,
                correction.sentAtEpochMs DESC,
                correction.localSequence DESC,
                correction.localMessageId DESC
              LIMIT 1
            ),
            messages.body
          ) AS preview,
          messages.localSequence AS localSequence,
          messages.messageKind AS messageKind,
          messages.direction AS direction,
          messages.sentAtEpochMs AS sentAtEpochMs,
          messages.sentTimeSource AS sentTimeSource,
          messages.conversationArchiveOrdinal AS conversationArchiveOrdinal,
          peers.displayName AS displayName,
          peers.localNickname AS localNickname,
          peers.photoBytes AS photoBytes,
          peers.photoMime AS photoMime,
          COALESCE(peers.room, 0) AS room,
          (
            SELECT COUNT(*)
            FROM messages AS unread
            WHERE unread.accountId = :accountId
              AND unread.peerJid = messages.peerJid
              AND unread.direction = 'INBOUND'
              AND unread.replaceId IS NULL
              AND unread.localSequence > COALESCE(peers.lastReadLocalSequence, 0)
          ) AS unreadCount
        FROM latest_messages AS messages
        LEFT JOIN peers
          ON peers.accountId = messages.accountId
         AND peers.jid = messages.peerJid
        """
