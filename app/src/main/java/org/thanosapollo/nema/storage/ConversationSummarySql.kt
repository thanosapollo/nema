package org.thanosapollo.nema.storage

internal const val CHEAP_CONVERSATION_SUMMARIES = """
        SELECT messages.localMessageId AS localMessageId,
          messages.peerJid AS peerJid,
          messages.senderJid AS senderJid,
          CASE WHEN messages.protectedState = 'NONE' AND messages.protectedEvidence IS NOT NULL THEN 'REJECTED' ELSE messages.protectedState END AS protectedState,
          COALESCE(
            (
              SELECT correction.body
              FROM messages AS correction
              WHERE correction.accountId = messages.accountId
                AND correction.correctionTargetMessageId = messages.localMessageId
              AND messages.protectedState = 'NONE' AND messages.protectedEvidence IS NULL
              AND correction.protectedState = 'NONE' AND correction.protectedEvidence IS NULL
              AND (correction.messageKind != 'GROUPCHAT' OR correction.mucCorrectionSelected = 1)
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
          NULL AS conversationArchiveOrdinal,
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
              AND unread.unreadEligible = 1
              AND unread.locallyRead = 0
          ) AS unreadCount
        FROM messages
        INNER JOIN (
          SELECT messages.peerJid AS peerJid, MAX(messages.localSequence) AS localSequence
          FROM messages
          INNER JOIN (
            SELECT peerJid, MAX(sentAtEpochMs) AS sentAtEpochMs
            FROM messages
            WHERE accountId = :accountId
              AND messageKind IN ('CHAT', 'GROUPCHAT')
              AND replaceId IS NULL
            GROUP BY peerJid
          ) AS latest_time
            ON latest_time.peerJid = messages.peerJid
           AND (
             (latest_time.sentAtEpochMs IS NOT NULL AND messages.sentAtEpochMs = latest_time.sentAtEpochMs)
             OR (latest_time.sentAtEpochMs IS NULL AND messages.sentAtEpochMs IS NULL)
           )
          WHERE messages.accountId = :accountId
            AND messages.messageKind IN ('CHAT', 'GROUPCHAT')
            AND messages.replaceId IS NULL
          GROUP BY messages.peerJid
        ) AS latest
          ON latest.peerJid = messages.peerJid
         AND latest.localSequence = messages.localSequence
        LEFT JOIN peers
          ON peers.accountId = messages.accountId
         AND peers.jid = messages.peerJid
        WHERE messages.accountId = :accountId
          AND messages.replaceId IS NULL
        """
