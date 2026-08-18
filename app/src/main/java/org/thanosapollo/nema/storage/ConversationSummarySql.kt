package org.thanosapollo.nema.storage

internal const val CHEAP_CONVERSATION_SUMMARIES = """
        WITH latest_archive_ordinals AS (
          SELECT peerJid,
            messageKind,
            MAX(archiveOrdinal) AS conversationArchiveOrdinal
          FROM messages
          WHERE accountId = :accountId
            AND messageKind IN ('CHAT', 'GROUPCHAT')
            AND replaceId IS NULL
            AND archiveOrdinal IS NOT NULL
          GROUP BY peerJid, messageKind
        ),
        latest_archived AS (
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
            messages.archiveOrdinal AS conversationArchiveOrdinal
          FROM messages
          JOIN latest_archive_ordinals AS latest
            ON latest.peerJid = messages.peerJid
           AND latest.messageKind = messages.messageKind
           AND latest.conversationArchiveOrdinal = messages.archiveOrdinal
          WHERE messages.accountId = :accountId
            AND messages.messageKind IN ('CHAT', 'GROUPCHAT')
            AND messages.replaceId IS NULL
        ),
        latest_loose_times AS (
          SELECT peerJid,
            MAX(sentAtEpochMs IS NOT NULL) AS hasSentTime,
            MAX(sentAtEpochMs) AS sentAtEpochMs
          FROM messages
          WHERE accountId = :accountId
            AND messageKind IN ('CHAT', 'GROUPCHAT')
            AND replaceId IS NULL
            AND archiveOrdinal IS NULL
          GROUP BY peerJid
        ),
        latest_loose_sequences AS (
          SELECT messages.peerJid AS peerJid,
            MAX(messages.localSequence) AS localSequence
          FROM messages
          JOIN latest_loose_times AS latest
            ON latest.peerJid = messages.peerJid
           AND (
             (latest.hasSentTime = 0 AND messages.sentAtEpochMs IS NULL)
             OR (latest.hasSentTime = 1 AND messages.sentAtEpochMs = latest.sentAtEpochMs)
           )
          WHERE messages.accountId = :accountId
            AND messages.messageKind IN ('CHAT', 'GROUPCHAT')
            AND messages.replaceId IS NULL
            AND messages.archiveOrdinal IS NULL
          GROUP BY messages.peerJid
        ),
        latest_loose AS (
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
            messages.archiveOrdinal AS conversationArchiveOrdinal
          FROM messages
          JOIN latest_loose_sequences AS latest
            ON latest.peerJid = messages.peerJid
           AND latest.localSequence = messages.localSequence
          WHERE messages.accountId = :accountId
            AND messages.replaceId IS NULL
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
