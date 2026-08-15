package org.thanosapollo.nema.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.draftKey
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@Dao
abstract class MessageDao {
    @Query("SELECT EXISTS(SELECT 1 FROM accounts WHERE id = :accountId)")
    abstract suspend fun accountExists(accountId: String): Boolean

    @Query("SELECT bareJid FROM accounts WHERE id = :accountId")
    abstract suspend fun accountBareJid(accountId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPeer(peer: PeerEntity): Long

    @Upsert
    abstract suspend fun upsertPeer(peer: PeerEntity)

    @Query(
        """
        UPDATE peers SET displayName = :displayName, photoMime = :photoMime,
          photoBytes = :photoBytes, photoSha1 = :photoSha1,
          vcardFetchedAtMs = :fetchedAtMs, vcardFailureAtMs = NULL
        WHERE accountId = :accountId AND jid = :peerJid
        """,
    )
    protected abstract suspend fun updatePeerRemoteIdentity(
        accountId: String,
        peerJid: String,
        displayName: String?,
        photoMime: String?,
        photoBytes: ByteArray?,
        photoSha1: String?,
        fetchedAtMs: Long?,
    ): Int

    @Query(
        """
        UPDATE peers SET vcardFailureAtMs = :failureAtMs
        WHERE accountId = :accountId AND jid = :peerJid
        """,
    )
    protected abstract suspend fun updatePeerVCardFailure(
        accountId: String,
        peerJid: String,
        failureAtMs: Long,
    ): Int

    @Query(
        """
        UPDATE peers SET localNickname = :localNickname
        WHERE accountId = :accountId AND jid = :peerJid
        """,
    )
    protected abstract suspend fun updatePeerNickname(
        accountId: String,
        peerJid: String,
        localNickname: String?,
    ): Int

    @Transaction
    open suspend fun savePeerRemoteIdentity(peer: PeerEntity) {
        require(accountExists(peer.accountId)) { "Unknown peer account" }
        insertPeer(peer)
        check(
            updatePeerRemoteIdentity(
                accountId = peer.accountId,
                peerJid = peer.jid,
                displayName = peer.displayName,
                photoMime = peer.photoMime,
                photoBytes = peer.photoBytes,
                photoSha1 = peer.photoSha1,
                fetchedAtMs = peer.vcardFetchedAtMs,
            ) == 1,
        ) { "Peer changed while saving remote identity" }
    }

    @Transaction
    open suspend fun savePeerVCardFailure(accountId: String, peerJid: String, failureAtMs: Long) {
        require(accountExists(accountId)) { "Unknown peer account" }
        insertPeer(PeerEntity(accountId, peerJid))
        check(updatePeerVCardFailure(accountId, peerJid, failureAtMs) == 1) {
            "Peer changed while saving vCard failure"
        }
    }

    @Transaction
    open suspend fun savePeerNickname(accountId: String, peerJid: String, localNickname: String?) {
        require(accountExists(accountId)) { "Unknown peer account" }
        insertPeer(PeerEntity(accountId, peerJid))
        check(updatePeerNickname(accountId, peerJid, localNickname) == 1) {
            "Peer changed while saving nickname"
        }
    }

    @Query(
        """
        UPDATE peers SET displayName = :displayName
        WHERE accountId = :accountId AND jid = :peerJid
        """,
    )
    protected abstract suspend fun updatePeerDisplayName(
        accountId: String,
        peerJid: String,
        displayName: String?,
    ): Int

    @Transaction
    open suspend fun savePeerDisplayName(accountId: String, peerJid: String, displayName: String?) {
        require(accountExists(accountId)) { "Unknown peer account" }
        insertPeer(PeerEntity(accountId, peerJid))
        check(updatePeerDisplayName(accountId, peerJid, displayName?.trim()?.ifEmpty { null }) == 1) {
            "Peer changed while saving display name"
        }
    }

    @Query(
        """
        UPDATE peers SET room = :room
        WHERE accountId = :accountId AND jid = :peerJid
        """,
    )
    protected abstract suspend fun updatePeerRoom(
        accountId: String,
        peerJid: String,
        room: Boolean,
    ): Int

    @Transaction
    open suspend fun savePeerRoom(accountId: String, peerJid: String, room: Boolean) {
        require(accountExists(accountId)) { "Unknown peer account" }
        insertPeer(PeerEntity(accountId, peerJid, room = room))
        check(updatePeerRoom(accountId, peerJid, room) == 1) {
            "Peer changed while saving room flag"
        }
    }

    @Query("SELECT * FROM peers WHERE accountId = :accountId AND jid = :peerJid")
    abstract suspend fun peer(accountId: String, peerJid: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE accountId = :accountId AND jid = :peerJid")
    abstract fun observePeer(accountId: String, peerJid: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE accountId = :accountId AND room = 1 ORDER BY jid")
    abstract fun observeRooms(accountId: String): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE accountId = :accountId ORDER BY jid")
    abstract fun observePeers(accountId: String): Flow<List<PeerEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertThread(thread: MessageThreadEntity): Long

    @Query(
        """
        SELECT * FROM message_threads
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND messageKind = :messageKind AND threadId = :threadId
        """,
    )
    abstract suspend fun thread(
        accountId: String,
        peerJid: String,
        messageKind: MessageKind,
        threadId: String,
    ): MessageThreadEntity?

    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM messages
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND messageKind = :messageKind AND threadId = :threadId)
        """,
    )
    abstract suspend fun threadHasMessages(
        accountId: String,
        peerJid: String,
        messageKind: MessageKind,
        threadId: String,
    ): Boolean

    @Query(
        """
        UPDATE message_threads SET parentThreadId = :parentThreadId
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND messageKind = :messageKind AND threadId = :threadId
          AND parentThreadId IS NULL
          AND NOT EXISTS (
            SELECT 1 FROM messages
            WHERE accountId = :accountId AND peerJid = :peerJid
              AND messageKind = :messageKind AND threadId = :threadId
          )
        """,
    )
    abstract suspend fun resolveThreadParent(
        accountId: String,
        peerJid: String,
        messageKind: MessageKind,
        threadId: String,
        parentThreadId: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSequence(sequence: AccountMessageSequenceEntity): Long

    @Query("SELECT nextValue FROM account_message_sequences WHERE accountId = :accountId")
    abstract suspend fun nextSequence(accountId: String): Long?

    @Query(
        """
        UPDATE account_message_sequences SET nextValue = :nextValue
        WHERE accountId = :accountId AND nextValue = :expectedValue
        """,
    )
    abstract suspend fun advanceSequence(
        accountId: String,
        expectedValue: Long,
        nextValue: Long,
    ): Int

    @Insert
    abstract suspend fun insertMessage(message: MessageEntity)

    @Update
    abstract suspend fun updateMessage(message: MessageEntity)

    @Delete
    abstract suspend fun deleteMessage(message: MessageEntity)

    @Insert
    abstract suspend fun insertArchivePosition(position: ArchiveMessagePositionEntity)

    @Query(
        """
        SELECT * FROM archive_message_positions
        WHERE accountId = :accountId
          AND archiveAuthority = :archiveAuthority
          AND archiveScope = :archiveScope
          AND messageId = :messageId
        """,
    )
    abstract suspend fun archivePosition(
        accountId: String,
        archiveAuthority: String,
        archiveScope: String,
        messageId: String,
    ): ArchiveMessagePositionEntity?

    @Query(
        """
        SELECT * FROM archive_message_positions
        WHERE accountId = :accountId AND messageId = :messageId
        ORDER BY archiveAuthority, archiveScope, archiveOrdinal
        """,
    )
    abstract suspend fun archivePositions(
        accountId: String,
        messageId: String,
    ): List<ArchiveMessagePositionEntity>

    @Query(
        """
        SELECT * FROM archive_message_positions
        WHERE accountId = :accountId
          AND archiveAuthority = :archiveAuthority
          AND archiveScope = :archiveScope
        ORDER BY archiveOrdinal, messageId
        """,
    )
    abstract suspend fun archiveScopePositions(
        accountId: String,
        archiveAuthority: String,
        archiveScope: String,
    ): List<ArchiveMessagePositionEntity>

    @Query(
        """
        UPDATE archive_message_positions SET messageId = :winnerId
        WHERE accountId = :accountId AND messageId = :loserId
          AND archiveAuthority = :archiveAuthority AND archiveScope = :archiveScope
          AND archiveOrdinal = :archiveOrdinal
        """,
    )
    abstract suspend fun reparentArchivePosition(
        accountId: String,
        loserId: String,
        winnerId: String,
        archiveAuthority: String,
        archiveScope: String,
        archiveOrdinal: Long,
    ): Int

    @Query(
        """
        DELETE FROM archive_message_positions
        WHERE accountId = :accountId AND messageId = :messageId
          AND archiveAuthority = :archiveAuthority AND archiveScope = :archiveScope
          AND archiveOrdinal = :archiveOrdinal
        """,
    )
    abstract suspend fun deleteArchivePosition(
        accountId: String,
        messageId: String,
        archiveAuthority: String,
        archiveScope: String,
        archiveOrdinal: Long,
    ): Int

    @Query(
        """
        UPDATE messages SET archiveOrdinal = :archiveOrdinal
        WHERE accountId = :accountId AND localMessageId = :messageId
          AND archiveOrdinal IS NULL
        """,
    )
    abstract suspend fun setCanonicalArchiveOrdinalIfAbsent(
        accountId: String,
        messageId: String,
        archiveOrdinal: Long,
    ): Int

    @Query(
        """
        SELECT * FROM messages
        WHERE accountId = :accountId AND localMessageId = :messageId
        """,
    )
    abstract suspend fun message(accountId: String, messageId: String): MessageEntity?

    @Query(
        """
        SELECT * FROM messages WHERE accountId = :accountId
        ORDER BY archiveOrdinal IS NULL, archiveOrdinal, localSequence, localMessageId
        """,
    )
    abstract suspend fun messages(accountId: String): List<MessageEntity>

    @Query(
        """
        SELECT * FROM messages WHERE accountId = :accountId
        ORDER BY archiveOrdinal IS NULL, archiveOrdinal, localSequence, localMessageId
        """,
    )
    abstract fun observeMessages(accountId: String): Flow<List<MessageEntity>>

    @Query(
        """
        WITH positioned_messages AS (
          SELECT messages.accountId AS accountId,
            messages.localMessageId AS localMessageId,
            messages.peerJid AS peerJid,
            messages.body AS preview,
            messages.localSequence AS localSequence,
            messages.messageKind AS messageKind,
            messages.sentAtEpochMs AS sentAtEpochMs,
            messages.sentTimeSource AS sentTimeSource,
            CASE
              WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
              ELSE accounts.bareJid
            END AS conversationArchiveAuthority,
            CASE
              WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
              ELSE 'ACCOUNT'
            END AS conversationArchiveScope,
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
        ),
        latest_archive_ordinals AS (
          SELECT peerJid, conversationArchiveAuthority, conversationArchiveScope,
            MAX(conversationArchiveOrdinal) AS conversationArchiveOrdinal
          FROM positioned_messages
          WHERE conversationArchiveOrdinal IS NOT NULL
          GROUP BY peerJid, conversationArchiveAuthority, conversationArchiveScope
        ),
        latest_archived AS (
          SELECT positioned.*
          FROM positioned_messages AS positioned
          JOIN latest_archive_ordinals AS latest
            ON latest.peerJid = positioned.peerJid
           AND latest.conversationArchiveAuthority = positioned.conversationArchiveAuthority
           AND latest.conversationArchiveScope = positioned.conversationArchiveScope
           AND latest.conversationArchiveOrdinal = positioned.conversationArchiveOrdinal
        ),
        latest_loose_times AS (
          SELECT peerJid,
            MAX(sentAtEpochMs IS NOT NULL) AS hasSentTime,
            MAX(sentAtEpochMs) AS sentAtEpochMs
          FROM positioned_messages
          WHERE conversationArchiveOrdinal IS NULL
          GROUP BY peerJid
        ),
        latest_loose_sequences AS (
          SELECT positioned.peerJid AS peerJid,
            MAX(positioned.localSequence) AS localSequence
          FROM positioned_messages AS positioned
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
          FROM positioned_messages AS positioned
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
          messages.preview AS preview,
          messages.localSequence AS localSequence,
          messages.messageKind AS messageKind,
          messages.sentAtEpochMs AS sentAtEpochMs,
          messages.sentTimeSource AS sentTimeSource,
          messages.conversationArchiveOrdinal AS conversationArchiveOrdinal,
          peers.displayName AS displayName,
          peers.localNickname AS localNickname,
          peers.photoBytes AS photoBytes,
          peers.photoMime AS photoMime,
          COALESCE(peers.room, 0) AS room
        FROM latest_messages AS messages
        LEFT JOIN peers
          ON peers.accountId = messages.accountId
         AND peers.jid = messages.peerJid
        ORDER BY messages.localSequence, messages.localMessageId
        """,
    )
    abstract fun observeConversationSummaries(accountId: String): Flow<List<ConversationListRow>>

    @Query(
        """
        SELECT * FROM messages
        WHERE accountId = :accountId AND messageKind = 'CHAT'
        ORDER BY archiveOrdinal IS NULL, archiveOrdinal, localSequence, localMessageId
        """,
    )
    abstract fun observeConversationMessages(accountId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT messages.*, message_outbox.operationId AS operationId,
          message_outbox.status AS outboxStatus,
          message_outbox.generation AS outboxGeneration,
          message_outbox.attempt AS outboxAttempt,
          (
            SELECT alias.value
            FROM trusted_identity_aliases AS alias
            WHERE alias.accountId = messages.accountId
              AND alias.messageId = messages.localMessageId
              AND alias.status = 'TRUSTED'
              AND (
                (messages.messageKind = 'GROUPCHAT'
                  AND alias.kind = 'STANZA_ID'
                  AND alias.authority = messages.peerJid)
                OR
                (messages.messageKind != 'GROUPCHAT'
                  AND alias.kind IN ('ORIGIN_ID', 'MESSAGE_ID'))
              )
            ORDER BY CASE alias.kind WHEN 'ORIGIN_ID' THEN 0 ELSE 1 END, alias.value
            LIMIT 1
          ) AS replyReferenceId,
          (
            SELECT position.archiveOrdinal
            FROM archive_message_positions AS position
            WHERE position.accountId = messages.accountId
              AND position.messageId = messages.localMessageId
              AND position.archiveAuthority = CASE
                WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
                ELSE (SELECT bareJid FROM accounts WHERE accounts.id = messages.accountId)
              END
              AND position.archiveScope = CASE
                WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
                ELSE 'ACCOUNT'
              END
            LIMIT 1
          ) AS conversationArchiveOrdinal,
          CASE
            WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
            ELSE (SELECT bareJid FROM accounts WHERE accounts.id = messages.accountId)
          END AS conversationArchiveAuthority,
          CASE
            WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
            ELSE 'ACCOUNT'
          END AS conversationArchiveScope
        FROM messages
        LEFT JOIN message_outbox
          ON message_outbox.accountId = messages.accountId
         AND message_outbox.messageId = messages.localMessageId
        WHERE messages.accountId = :accountId AND messages.peerJid = :peerJid
          AND messages.messageKind IN ('CHAT', 'GROUPCHAT')
        ORDER BY (
          SELECT position.archiveOrdinal
          FROM archive_message_positions AS position
          WHERE position.accountId = messages.accountId
            AND position.messageId = messages.localMessageId
            AND position.archiveAuthority = CASE
              WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
              ELSE (SELECT bareJid FROM accounts WHERE accounts.id = messages.accountId)
            END
            AND position.archiveScope = CASE
              WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
              ELSE 'ACCOUNT'
            END
          ORDER BY position.archiveAuthority
          LIMIT 1
        ) IS NULL,
        (
          SELECT position.archiveOrdinal
          FROM archive_message_positions AS position
          WHERE position.accountId = messages.accountId
            AND position.messageId = messages.localMessageId
            AND position.archiveAuthority = CASE
              WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
              ELSE (SELECT bareJid FROM accounts WHERE accounts.id = messages.accountId)
            END
            AND position.archiveScope = CASE
              WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
              ELSE 'ACCOUNT'
            END
          LIMIT 1
        ), messages.localSequence, messages.localMessageId
        """,
    )
    abstract fun observeDirectTimeline(accountId: String, peerJid: String): Flow<List<TimelineRow>>

    @Query("SELECT * FROM chat_navigation WHERE accountId = :accountId")
    abstract fun observeNavigation(accountId: String): Flow<ChatNavigationEntity?>

    @Upsert
    protected abstract suspend fun upsertNavigation(navigation: ChatNavigationEntity)

    @Query("DELETE FROM chat_navigation WHERE accountId = :accountId")
    protected abstract suspend fun deleteNavigation(accountId: String): Int

    @Transaction
    open suspend fun saveNavigation(navigation: ChatNavigationEntity) {
        require(accountExists(navigation.accountId)) { "Unknown navigation account" }
        upsertNavigation(navigation)
    }

    @Transaction
    open suspend fun clearNavigation(accountId: String) {
        require(accountExists(accountId)) { "Unknown navigation account" }
        deleteNavigation(accountId)
    }

    @Query(
        """
        SELECT * FROM message_drafts
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND messageKind = 'CHAT' AND threadKey = :threadKey
        """,
    )
    abstract fun observeDraft(
        accountId: String,
        peerJid: String,
        threadKey: String,
    ): Flow<MessageDraftEntity?>

    @Query(
        """
        SELECT * FROM message_drafts
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND messageKind = 'CHAT' AND threadKey = :threadKey
        """,
    )
    abstract suspend fun draft(
        accountId: String,
        peerJid: String,
        threadKey: String,
    ): MessageDraftEntity?

    @Upsert
    abstract suspend fun upsertDraft(draft: MessageDraftEntity)

    @Query(
        """
        DELETE FROM message_drafts
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND messageKind = 'CHAT' AND threadKey = :threadKey
        """,
    )
    abstract suspend fun deleteDraft(accountId: String, peerJid: String, threadKey: String): Int

    @Transaction
    open suspend fun saveDraft(
        accountId: String,
        peerJid: String,
        threadKey: String,
        body: String,
        replyToId: String? = null,
        replyToJid: String? = null,
        replyFallbackBody: String? = null,
        replyFallbackSender: String? = null,
    ) {
        require(accountExists(accountId)) { "Unknown draft account" }
        insertPeer(PeerEntity(accountId, peerJid))
        if (body.isEmpty() && replyToId == null) {
            deleteDraft(accountId, peerJid, threadKey)
        } else {
            upsertDraft(
                MessageDraftEntity(
                    accountId = accountId,
                    peerJid = peerJid,
                    messageKind = MessageKind.CHAT,
                    threadKey = threadKey,
                    body = body,
                    replyToId = replyToId,
                    replyToJid = replyToJid,
                    replyFallbackBody = replyFallbackBody,
                    replyFallbackSender = replyFallbackSender,
                ),
            )
        }
    }

    fun observeDirectDraft(accountId: String, peerJid: String): Flow<MessageDraftEntity?> =
        observeDraft(accountId, peerJid, "")

    suspend fun directDraft(accountId: String, peerJid: String): MessageDraftEntity? =
        draft(accountId, peerJid, "")

    suspend fun saveDirectDraft(accountId: String, peerJid: String, body: String) {
        saveDraft(accountId, peerJid, "", body)
    }

    suspend fun deleteDirectDraft(accountId: String, peerJid: String): Int =
        deleteDraft(accountId, peerJid, "")

    @Insert
    abstract suspend fun insertTrustedAlias(alias: TrustedIdentityAliasEntity)

    @Query(
        """
        SELECT * FROM trusted_identity_aliases
        WHERE accountId = :accountId AND kind = :kind
          AND authority = :authority AND value = :value AND status = 'TRUSTED'
        """,
    )
    abstract suspend fun trustedAlias(
        accountId: String,
        kind: IdentityAliasKind,
        authority: String,
        value: String,
    ): TrustedIdentityAliasEntity?

    @Query(
        """
        SELECT * FROM trusted_identity_aliases
        WHERE accountId = :accountId AND kind = :kind
          AND authority = :authority AND value = :value
        """,
    )
    abstract suspend fun identityAlias(
        accountId: String,
        kind: IdentityAliasKind,
        authority: String,
        value: String,
    ): TrustedIdentityAliasEntity?

    @Query(
        """
        UPDATE trusted_identity_aliases SET messageId = NULL, status = 'QUARANTINED'
        WHERE accountId = :accountId AND kind = :kind
          AND authority = :authority AND value = :value AND status = 'TRUSTED'
        """,
    )
    abstract suspend fun quarantineAlias(
        accountId: String,
        kind: IdentityAliasKind,
        authority: String,
        value: String,
    ): Int

    @Query(
        """
        UPDATE trusted_identity_aliases SET messageId = :winnerId
        WHERE accountId = :accountId AND messageId = :loserId AND status = 'TRUSTED'
        """,
    )
    abstract suspend fun reparentAliases(accountId: String, loserId: String, winnerId: String): Int

    @Query("SELECT * FROM trusted_identity_aliases WHERE accountId = :accountId ORDER BY kind, authority, value")
    abstract suspend fun trustedAliases(accountId: String): List<TrustedIdentityAliasEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertConflict(conflict: IdentityConflictEntity): Long

    @Delete
    abstract suspend fun deleteConflict(conflict: IdentityConflictEntity)

    @Query(
        """
        SELECT * FROM identity_conflicts
        WHERE accountId = :accountId
          AND (firstMessageId = :messageId OR secondMessageId = :messageId)
        """,
    )
    abstract suspend fun conflictsForMessage(
        accountId: String,
        messageId: String,
    ): List<IdentityConflictEntity>


    @Query("SELECT * FROM identity_conflicts WHERE accountId = :accountId ORDER BY detectedAtSequence")
    abstract suspend fun conflicts(accountId: String): List<IdentityConflictEntity>

    @Insert
    abstract suspend fun insertOutbox(outbox: OutboxEntity)

    @Update
    abstract suspend fun updateOutbox(outbox: OutboxEntity)

    @Query(
        """
        SELECT * FROM message_outbox
        WHERE accountId = :accountId AND operationId = :operationId
        """,
    )
    abstract suspend fun outbox(accountId: String, operationId: String): OutboxEntity?

    @Query(
        """
        SELECT * FROM message_outbox
        WHERE accountId = :accountId AND originId = :originId
        """,
    )
    abstract suspend fun outboxByOrigin(accountId: String, originId: String): OutboxEntity?

    @Query(
        """
        SELECT * FROM message_outbox
        WHERE accountId = :accountId AND messageId IN (:messageIds)
        """,
    )
    abstract suspend fun outboxesForMessages(accountId: String, messageIds: List<String>): List<OutboxEntity>

    @Query(
        """
        UPDATE message_outbox SET messageId = :winnerId
        WHERE accountId = :accountId AND messageId = :loserId
        """,
    )
    abstract suspend fun reparentOutbox(accountId: String, loserId: String, winnerId: String): Int

    @Query("SELECT * FROM message_outbox WHERE accountId = :accountId ORDER BY operationId")
    abstract suspend fun outboxes(accountId: String): List<OutboxEntity>

    @Query(
        """
        SELECT message_outbox.operationId, message_outbox.originId,
          messages.localMessageId, messages.peerJid, messages.senderJid,
          messages.messageKind, messages.threadId, messages.parentThreadId, messages.body,
          messages.attachmentUrl, messages.attachmentName, messages.attachmentMime,
          messages.attachmentSize, messages.replyToId, messages.replyToJid,
          messages.replyFallbackBody
        FROM message_outbox
        INNER JOIN messages
          ON messages.accountId = message_outbox.accountId
         AND messages.localMessageId = message_outbox.messageId
        WHERE message_outbox.accountId = :accountId AND message_outbox.status = 'PENDING'
        ORDER BY messages.localSequence, messages.localMessageId
        """,
    )
    abstract suspend fun pendingOutbound(accountId: String): List<PendingOutbound>

    @Query(
        """
        SELECT * FROM archive_cursors
        WHERE accountId = :accountId AND archiveAuthority = :archiveAuthority AND scope = :scope
        """,
    )
    abstract suspend fun archiveCursor(
        accountId: String,
        archiveAuthority: String,
        scope: String,
    ): ArchiveCursorEntity?

    @Upsert
    abstract suspend fun upsertArchiveCursor(cursor: ArchiveCursorEntity)

    @Query(
        """
        SELECT MIN(archiveOrdinal) FROM archive_message_positions
        WHERE accountId = :accountId
          AND archiveAuthority = :archiveAuthority
          AND archiveScope = :archiveScope
        """,
    )
    abstract suspend fun minimumArchiveOrdinal(
        accountId: String,
        archiveAuthority: String,
        archiveScope: String,
    ): Long?

    @Query(
        """
        SELECT MAX(archiveOrdinal) FROM archive_message_positions
        WHERE accountId = :accountId
          AND archiveAuthority = :archiveAuthority
          AND archiveScope = :archiveScope
        """,
    )
    abstract suspend fun maximumArchiveOrdinal(
        accountId: String,
        archiveAuthority: String,
        archiveScope: String,
    ): Long?
}

data class ConversationListRow(
    val localMessageId: String,
    val peerJid: String,
    val preview: String,
    val localSequence: Long,
    val messageKind: MessageKind,
    val displayName: String?,
    val localNickname: String?,
    val photoBytes: ByteArray?,
    val photoMime: String?,
    val room: Boolean,
    val sentAtEpochMs: Long?,
    val sentTimeSource: MessageTimeSource?,
    val conversationArchiveOrdinal: Long?,
) {
    val groupChat: Boolean
        get() = messageKind == MessageKind.GROUPCHAT || room
}

data class TimelineRow(
    val accountId: String,
    val localMessageId: String,
    val peerJid: String,
    val senderJid: String,
    val direction: MessageDirection,
    val messageKind: MessageKind,
    val threadId: String?,
    val parentThreadId: String?,
    val body: String,
    val localSequence: Long,
    val archiveOrdinal: Long?,
    val sentAtEpochMs: Long?,
    val sentTimeSource: MessageTimeSource?,
    val operationId: String?,
    val outboxStatus: String?,
    val outboxGeneration: Long?,
    val outboxAttempt: Int?,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val replyToId: String? = null,
    val replyToJid: String? = null,
    val replyFallbackBody: String? = null,
    val replyReferenceId: String? = null,
    val conversationArchiveOrdinal: Long? = null,
    val conversationArchiveAuthority: String,
    val conversationArchiveScope: String,
)

data class RetryUncertainKey(
    val accountId: String,
    val operationId: String,
    val generation: Long,
    val attempt: Int,
)

data class PendingOutbound(
    val operationId: String,
    val originId: String,
    val localMessageId: String,
    val peerJid: String,
    val senderJid: String,
    val messageKind: MessageKind,
    val threadId: String?,
    val parentThreadId: String?,
    val body: String,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val replyToId: String? = null,
    val replyToJid: String? = null,
    val replyFallbackBody: String? = null,
)

data class TrustedIdentityAlias(
    val kind: IdentityAliasKind,
    val authority: String,
    val value: String,
) {
    init {
        require(authority.isNotEmpty()) { "Alias authority must not be empty" }
        require(value.isNotEmpty()) { "Alias value must not be empty" }
    }
}

data class IncomingMessage(
    val accountId: String,
    val localMessageId: String,
    val peerJid: String,
    val senderJid: String,
    val direction: MessageDirection,
    val messageKind: MessageKind,
    val threadId: String?,
    val parentThreadId: String?,
    val body: String,
    val archiveOrdinal: Long?,
    val aliases: List<TrustedIdentityAlias>,
    val archiveAuthority: String? = null,
    val archiveScope: String? = null,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val replyToId: String? = null,
    val replyToJid: String? = null,
    val replyFallbackBody: String? = null,
    val sentAtEpochMs: Long? = null,
    val sentTimeSource: MessageTimeSource? = null,
) {
    init {
        require(accountId.isNotEmpty()) { "Account ID must not be empty" }
        require(localMessageId.isNotEmpty()) { "Local message ID must not be empty" }
        require(peerJid.isNotEmpty()) { "Peer JID must not be empty" }
        require(senderJid.isNotEmpty()) { "Sender JID must not be empty" }
        require(threadId != null || parentThreadId == null) { "Parent thread requires a thread ID" }
        require(threadId == null || threadId.isNotEmpty()) { "Thread ID must not be empty" }
        require(parentThreadId == null || parentThreadId.isNotEmpty()) { "Parent thread ID must not be empty" }
        require(threadId == null || threadId != parentThreadId) { "Thread cannot parent itself" }
        require(aliases.distinct().size == aliases.size) { "Message aliases must be unique" }
        require(listOf(archiveOrdinal, archiveAuthority, archiveScope).all { it == null } ||
            listOf(archiveOrdinal, archiveAuthority, archiveScope).all { it != null }) {
            "Archive position must be completely qualified"
        }
        require(replyToId == null || replyToId.isNotEmpty()) { "Reply ID must not be empty" }
        require(replyToJid == null || replyToJid.isNotEmpty()) { "Reply JID must not be empty" }
        require(replyToId != null || (replyToJid == null && replyFallbackBody == null)) {
            "Reply metadata requires a reply ID"
        }
        require((sentAtEpochMs == null) == (sentTimeSource == null)) {
            "Message time and provenance must be stored together"
        }
    }
}

data class OutboundIntent(
    val accountId: String,
    val operationId: String,
    val localMessageId: String,
    val originId: String,
    val peerJid: String,
    val senderJid: String,
    val messageKind: MessageKind,
    val threadId: String?,
    val parentThreadId: String?,
    val body: String,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val replyToId: String? = null,
    val replyToJid: String? = null,
    val replyFallbackBody: String? = null,
) {
    init {
        require(accountId.isNotEmpty()) { "Account ID must not be empty" }
        require(operationId.isNotEmpty()) { "Operation ID must not be empty" }
        require(localMessageId.isNotEmpty()) { "Local message ID must not be empty" }
        require(originId.isNotEmpty()) { "Origin ID must not be empty" }
        require(peerJid.isNotEmpty()) { "Peer JID must not be empty" }
        require(senderJid.isNotEmpty()) { "Sender JID must not be empty" }
        require(threadId != null || parentThreadId == null) { "Parent thread requires a thread ID" }
        require(threadId == null || threadId.isNotEmpty()) { "Thread ID must not be empty" }
        require(parentThreadId == null || parentThreadId.isNotEmpty()) { "Parent thread ID must not be empty" }
        require(threadId == null || threadId != parentThreadId) { "Thread cannot parent itself" }
        require(replyToId == null || replyToId.isNotEmpty()) { "Reply ID must not be empty" }
        require(replyToJid == null || replyToJid.isNotEmpty()) { "Reply JID must not be empty" }
        require(replyToId != null || (replyToJid == null && replyFallbackBody == null)) {
            "Reply metadata requires a reply ID"
        }
    }
}

data class IngestionResult(
    val messageId: String,
    val mergedRows: Int,
    val identityConflict: Boolean,
)

class OutboxClaim internal constructor(
    val accountId: String,
    val operationId: String,
    val generation: Long,
    val attempt: Int,
)

internal enum class MessageWriteBoundary {
    AFTER_MESSAGE,
    AFTER_ALIAS,
    AFTER_OUTBOX,
    AFTER_DEPENDENT_REPARENT,
    BEFORE_ARCHIVE_CURSOR,
    AFTER_ARCHIVE_CURSOR,
}

private data class MappedArchivePosition(
    val index: Int,
    val messageId: String,
    val ordinal: Long,
)

class MessageStore private constructor(
    private val database: NemaDatabase,
    private val writeBoundary: (MessageWriteBoundary) -> Unit,
    private val clock: () -> Long,
) {
    constructor(database: NemaDatabase) : this(database, {}, System::currentTimeMillis)

    internal constructor(database: NemaDatabase, clock: () -> Long) : this(database, {}, clock)

    suspend fun compose(intent: OutboundIntent): OutboxEntity = database.withTransaction {
        composeInTransaction(intent)
    }

    suspend fun composeDirectDraft(
        accountId: String,
        operationId: String,
        localMessageId: String,
        originId: String,
        peerJid: String,
        senderJid: String,
        body: String,
        thread: ThreadRef? = null,
        draftThread: ThreadRef? = thread,
        messageKind: MessageKind = MessageKind.CHAT,
        attachmentUrl: String? = null,
        attachmentName: String? = null,
        attachmentMime: String? = null,
        attachmentSize: Long? = null,
        replyToId: String? = null,
        replyToJid: String? = null,
        replyFallbackBody: String? = null,
        replyFallbackSender: String? = null,
    ): OutboxEntity? = database.withTransaction {
        val dao = database.messageDao()
        if (body.isBlank()) return@withTransaction null
        val threadKey = draftThread.draftKey()
        val durableReplyBody = replyToId?.let { replyFallbackBody.orEmpty() }
        val durableReplySender = if (replyToId == null) {
            null
        } else {
            replyFallbackSender ?: replyToJid?.substringAfterLast('/')?.substringBefore('@') ?: "message"
        }
        dao.saveDraft(
            accountId,
            peerJid,
            threadKey,
            body,
            replyToId,
            replyToJid,
            durableReplyBody,
            durableReplySender,
        )
        val outbox = composeInTransaction(
            OutboundIntent(
                accountId = accountId,
                operationId = operationId,
                localMessageId = localMessageId,
                originId = originId,
                peerJid = peerJid,
                senderJid = senderJid,
                messageKind = messageKind,
                threadId = thread?.id?.value,
                parentThreadId = thread?.parentId?.value,
                body = body,
                attachmentUrl = attachmentUrl,
                attachmentName = attachmentName,
                attachmentMime = attachmentMime,
                attachmentSize = attachmentSize,
                replyToId = replyToId,
                replyToJid = replyToJid,
                replyFallbackBody = replyFallbackBody,
            ),
        )
        check(dao.deleteDraft(accountId, peerJid, threadKey) == 1) {
            "Direct draft changed during send transaction"
        }
        outbox
    }

    private suspend fun composeInTransaction(intent: OutboundIntent): OutboxEntity {
        val dao = database.messageDao()
        dao.outbox(intent.accountId, intent.operationId)?.let { existing ->
            val message = requireNotNull(dao.message(intent.accountId, existing.messageId))
            require(existing.originId == intent.originId && message.matches(intent)) {
                "Operation ID identifies different outbound intent"
            }
            return existing
        }
        require(dao.outboxByOrigin(intent.accountId, intent.originId) == null) {
            "Origin ID already identifies another outbound intent"
        }
        ensureScope(
            accountId = intent.accountId,
            peerJid = intent.peerJid,
            messageKind = intent.messageKind,
            threadId = intent.threadId,
            parentThreadId = intent.parentThreadId,
            preserveStoredThreadLineage = false,
        )
        val message = MessageEntity(
            accountId = intent.accountId,
            localMessageId = intent.localMessageId,
            peerJid = intent.peerJid,
            senderJid = intent.senderJid,
            direction = MessageDirection.OUTBOUND,
            messageKind = intent.messageKind,
            threadId = intent.threadId,
            parentThreadId = intent.parentThreadId,
            body = intent.body,
            localSequence = allocateSequence(intent.accountId),
            archiveOrdinal = null,
            sentAtEpochMs = clock(),
            sentTimeSource = MessageTimeSource.LOCAL,
            attachmentUrl = intent.attachmentUrl,
            attachmentName = intent.attachmentName,
            attachmentMime = intent.attachmentMime,
            attachmentSize = intent.attachmentSize,
            replyToId = intent.replyToId,
            replyToJid = intent.replyToJid,
            replyFallbackBody = intent.replyFallbackBody,
        )
        dao.insertMessage(message)
        writeBoundary(MessageWriteBoundary.AFTER_MESSAGE)

        val originAlias = TrustedIdentityAlias(
            kind = IdentityAliasKind.ORIGIN_ID,
            authority = OUTBOUND_ORIGIN_AUTHORITY,
            value = intent.originId,
        )
        check(dao.identityAlias(intent.accountId, originAlias.kind, originAlias.authority, originAlias.value) == null) {
            "Origin ID is quarantined"
        }
        dao.insertTrustedAlias(originAlias.toEntity(intent.accountId, intent.localMessageId))
        writeBoundary(MessageWriteBoundary.AFTER_ALIAS)

        return OutboxEntity(
            accountId = intent.accountId,
            operationId = intent.operationId,
            messageId = intent.localMessageId,
            originId = intent.originId,
            status = OutboxStatus.PENDING,
            generation = null,
            attempt = 0,
            failureReason = null,
        ).also {
            dao.insertOutbox(it)
            writeBoundary(MessageWriteBoundary.AFTER_OUTBOX)
        }
    }

    suspend fun ingest(incoming: IncomingMessage): IngestionResult = database.withTransaction {
        val position = incoming.archivePosition()
        val result = ingestInTransaction(incoming.withoutArchivePosition())
        if (position != null) attachArchivePosition(result.messageId, incoming.accountId, position)
        result
    }

    private suspend fun ingestInTransaction(
        received: IncomingMessage,
        preserveStoredThreadLineage: Boolean = false,
    ): IngestionResult {
        val timed = received.withStoredTime(clock)
        val incoming = timed.copy(
            parentThreadId = ensureScope(
                accountId = timed.accountId,
                peerJid = timed.peerJid,
                messageKind = timed.messageKind,
                threadId = timed.threadId,
                parentThreadId = timed.parentThreadId,
                preserveStoredThreadLineage = preserveStoredThreadLineage,
            ),
        )
        val dao = database.messageDao()
        val existingLocal = dao.message(incoming.accountId, incoming.localMessageId)
        require(existingLocal == null || existingLocal.isCompatibleWith(incoming)) {
            "Local message ID identifies incompatible content"
        }

        val mappedMessages = incoming.aliases.mapNotNull { alias ->
            dao.trustedAlias(incoming.accountId, alias.kind, alias.authority, alias.value)
                ?.let {
                    requireNotNull(
                        dao.message(incoming.accountId, requireNotNull(it.messageId)),
                    )
                }
        }.distinctBy(MessageEntity::localMessageId)

        val matched = existingLocal ?: mappedMessages
            .filter { it.isCompatibleWith(incoming) }
            .minWithOrNull(compareBy(MessageEntity::localSequence, MessageEntity::localMessageId))
        var winner: MessageEntity
        if (matched == null) {
            val created = incoming.toEntity(allocateSequence(incoming.accountId))
            dao.insertMessage(created)
            writeBoundary(MessageWriteBoundary.AFTER_MESSAGE)
            winner = created
        } else {
            winner = matched
        }

        var mergedRows = 0
        var identityConflict = false
        incoming.aliases.forEach { alias ->
            val mapped = dao.trustedAlias(incoming.accountId, alias.kind, alias.authority, alias.value)
            when {
                mapped == null -> {
                    if (dao.identityAlias(incoming.accountId, alias.kind, alias.authority, alias.value) == null) {
                        dao.insertTrustedAlias(alias.toEntity(incoming.accountId, winner.localMessageId))
                    }
                }
                mapped.messageId == winner.localMessageId -> Unit
                else -> {
                    val other = requireNotNull(
                        dao.message(incoming.accountId, requireNotNull(mapped.messageId)),
                    )
                    if (canMerge(winner, other, incoming)) {
                        winner = mergePair(winner, other)
                        mergedRows += 1
                    } else {
                        check(
                            dao.quarantineAlias(
                                incoming.accountId,
                                alias.kind,
                                alias.authority,
                                alias.value,
                            ) == 1,
                        ) { "Conflicting alias changed during transaction" }
                        recordConflict(incoming, alias, winner, other)
                        identityConflict = true
                    }
                }
            }
        }
        writeBoundary(MessageWriteBoundary.AFTER_ALIAS)

        val reconciled = winner.withPreferredTime(incoming.sentAtEpochMs, incoming.sentTimeSource)
        if (reconciled != winner) {
            dao.updateMessage(reconciled)
            winner = reconciled
        }

        confirmMatchingOutbox(incoming, winner.localMessageId)
        writeBoundary(MessageWriteBoundary.AFTER_OUTBOX)
        return IngestionResult(winner.localMessageId, mergedRows, identityConflict)
    }

    private suspend fun attachArchivePosition(
        messageId: String,
        accountId: String,
        position: ArchivePosition,
    ) {
        val dao = database.messageDao()
        val existing = dao.archivePosition(
            accountId,
            position.authority,
            position.scope,
            messageId,
        )
        require(existing == null || existing.archiveOrdinal == position.ordinal) {
            "Message has conflicting position in one archive scope"
        }
        if (existing == null) {
            dao.insertArchivePosition(
                ArchiveMessagePositionEntity(
                    accountId = accountId,
                    archiveAuthority = position.authority,
                    archiveScope = position.scope,
                    archiveOrdinal = position.ordinal,
                    messageId = messageId,
                ),
            )
        }
        dao.setCanonicalArchiveOrdinalIfAbsent(accountId, messageId, position.ordinal)
    }

    suspend fun archiveCursor(key: ArchiveCursorKey): ArchiveCursorEntity? =
        database.messageDao().archiveCursor(key.accountId, key.archiveAuthority, key.scope)

    internal suspend fun archivePositions(
        accountId: String,
        messageId: String,
    ): List<ArchiveMessagePositionEntity> = database.messageDao().archivePositions(accountId, messageId)

    suspend fun applyArchivePage(page: ArchivePage): ArchivePageResult = database.withTransaction {
        val dao = database.messageDao()
        require(dao.accountExists(page.key.accountId)) { "Unknown archive account" }
        val current = dao.archiveCursor(
            page.key.accountId,
            page.key.archiveAuthority,
            page.key.scope,
        )

        suspend fun retryable(reason: String): ArchivePageResult {
            val cursor = (current ?: page.key.emptyCursor()).copy(retryableError = reason)
            dao.upsertArchiveCursor(cursor)
            return ArchivePageResult(ArchivePageStatus.RETRYABLE_ERROR, cursor, 0)
        }

        if (!page.stable) return@withTransaction retryable("Archive page is unstable")
        if (page.messages.isEmpty() && !page.complete) {
            return@withTransaction retryable("Archive page made no progress")
        }
        if (page.messages.isNotEmpty() &&
            (page.firstId != page.messages.first().resultId || page.lastId != page.messages.last().resultId)
        ) {
            return@withTransaction retryable("Archive page boundaries do not match results")
        }
        if (page.messages.isEmpty() && (page.firstId != null || page.lastId != null)) {
            return@withTransaction retryable("Empty archive page has boundaries")
        }
        if (page.messages.any { it.message?.accountId?.let { accountId -> accountId != page.key.accountId } == true }) {
            return@withTransaction retryable("Archive page account mismatch")
        }
        when (page.direction) {
            ArchiveDirection.BOOTSTRAP -> if (current?.oldestId != null || current?.newestId != null) {
                return@withTransaction retryable("Archive bootstrap already installed")
            }
            ArchiveDirection.BEFORE -> if (current == null || current.oldestId != page.boundaryId) {
                return@withTransaction retryable("Archive backward boundary mismatch")
            }
            ArchiveDirection.AFTER -> if (current == null || current.newestId != page.boundaryId) {
                return@withTransaction retryable("Archive forward boundary mismatch")
            }
        }

        val aliasAuthority = page.key.aliasAuthority()
        suspend fun preserveStoredThreadLineage(message: IncomingMessage): IncomingMessage {
            val threadId = message.threadId ?: return message.copy(parentThreadId = null)
            val existing = dao.thread(
                message.accountId,
                message.peerJid,
                message.messageKind,
                threadId,
            ) ?: return message
            val parentThreadId = when {
                existing.parentThreadId == message.parentThreadId -> message.parentThreadId
                message.parentThreadId == null -> existing.parentThreadId
                existing.parentThreadId != null -> existing.parentThreadId
                dao.threadHasMessages(
                    message.accountId,
                    message.peerJid,
                    message.messageKind,
                    threadId,
                ) -> null
                else -> message.parentThreadId
            }
            return message.copy(parentThreadId = parentThreadId)
        }
        data class PageIdentityKey(
            val kind: IdentityAliasKind?,
            val authority: String,
            val value: String,
        )
        data class PageIdentityCandidate(
            val index: Int,
            val keys: Set<PageIdentityKey>,
            val seededMessageIds: Set<String>,
        )

        val candidates = mutableListOf<PageIdentityCandidate>()
        page.messages.forEachIndexed { index, archived ->
            val message = archived.message ?: return@forEachIndexed
            val archiveAlias = TrustedIdentityAlias(
                kind = IdentityAliasKind.MAM_RESULT,
                authority = aliasAuthority,
                value = archived.resultId,
            )
            val aliases = (message.aliases + archiveAlias).distinct()
            val normalizedMessage = preserveStoredThreadLineage(message)
            val keys = mutableSetOf(
                PageIdentityKey(null, "", message.localMessageId),
            )
            val seededMessageIds = mutableSetOf<String>()
            dao.message(page.key.accountId, message.localMessageId)
                ?.takeIf { it.isCompatibleWith(normalizedMessage) }
                ?.localMessageId
                ?.let(seededMessageIds::add)
            aliases.forEach { alias ->
                val stored = dao.identityAlias(
                    page.key.accountId,
                    alias.kind,
                    alias.authority,
                    alias.value,
                )
                if (stored?.status != IdentityAliasStatus.QUARANTINED) {
                    keys += PageIdentityKey(alias.kind, alias.authority, alias.value)
                }
                stored?.takeIf { it.status == IdentityAliasStatus.TRUSTED }
                    ?.messageId
                    ?.let(seededMessageIds::add)
            }
            candidates += PageIdentityCandidate(index, keys, seededMessageIds)
        }

        val components = mutableListOf<List<PageIdentityCandidate>>()
        val remaining = candidates.toMutableList()
        while (remaining.isNotEmpty()) {
            val component = mutableListOf(remaining.removeAt(0))
            val keys = component.single().keys.toMutableSet()
            val seededMessageIds = component.single().seededMessageIds.toMutableSet()
            var expanded: Boolean
            do {
                expanded = false
                val connected = remaining.filter { candidate ->
                    candidate.keys.any(keys::contains) ||
                        candidate.seededMessageIds.any(seededMessageIds::contains)
                }
                if (connected.isNotEmpty()) {
                    remaining.removeAll(connected.toSet())
                    component += connected
                    connected.forEach { candidate ->
                        keys += candidate.keys
                        seededMessageIds += candidate.seededMessageIds
                    }
                    expanded = true
                }
            } while (expanded)
            components += component
        }

        val mappedCandidates = mutableListOf<MappedArchivePosition>()
        for (component in components) {
            val positioned = mutableListOf<ArchiveMessagePositionEntity>()
            for (messageId in component.flatMap { it.seededMessageIds }.distinct()) {
                dao.archivePosition(
                    page.key.accountId,
                    page.key.archiveAuthority,
                    page.key.scope,
                    messageId,
                )?.let(positioned::add)
            }
            if (positioned.map(ArchiveMessagePositionEntity::archiveOrdinal).distinct().size > 1) {
                return@withTransaction retryable("Archive page identity evidence has conflicting positions")
            }
            val position = positioned.minByOrNull(ArchiveMessagePositionEntity::messageId)
                ?: continue
            component.forEach { candidate ->
                mappedCandidates += MappedArchivePosition(
                    candidate.index,
                    position.messageId,
                    position.archiveOrdinal,
                )
            }
        }
        var mapped = mappedCandidates.sortedBy(MappedArchivePosition::index)
        val migrationResetBootstrap = page.direction == ArchiveDirection.BOOTSTRAP &&
            current?.let { cursor ->
                cursor.oldestId == null && cursor.newestId == null &&
                    cursor.oldestOrdinal == null && cursor.newestOrdinal == null &&
                    cursor.hasEarlier
            } == true
        val scopePositions = if (migrationResetBootstrap || page.direction == ArchiveDirection.BEFORE) {
            dao.archiveScopePositions(
                page.key.accountId,
                page.key.archiveAuthority,
                page.key.scope,
            )
        } else {
            emptyList()
        }
        val migrationBeforeReconciliation = page.direction == ArchiveDirection.BEFORE &&
            current?.oldestOrdinal?.let { oldest ->
                scopePositions.any { it.archiveOrdinal < oldest }
            } == true
        val migrationReconciliationPage = migrationResetBootstrap || migrationBeforeReconciliation
        if (migrationReconciliationPage) {
            val mappedIds = mapped.map(MappedArchivePosition::messageId)
            if (mappedIds.distinct().size != mappedIds.size) {
                return@withTransaction retryable("Archive page repeats one logical message")
            }
        }
        val advancesBoundary = when (page.direction) {
            ArchiveDirection.BOOTSTRAP -> true
            ArchiveDirection.BEFORE -> page.firstId != current?.oldestId
            ArchiveDirection.AFTER -> page.lastId != current?.newestId
        }
        if (page.messages.isNotEmpty() && !advancesBoundary) {
            return@withTransaction retryable("Archive page repeated without progress")
        }
        if (page.direction != ArchiveDirection.BOOTSTRAP &&
            !migrationBeforeReconciliation &&
            page.messages.isNotEmpty() &&
            mapped.size == page.messages.size
        ) {
            return@withTransaction retryable("Archive page repeated without progress")
        }

        val startOrdinal = when {
            migrationResetBootstrap -> {
                val mappedIdSet = mapped.map(MappedArchivePosition::messageId).toSet()
                val outsideMax = scopePositions.asSequence()
                    .filterNot { it.messageId in mappedIdSet }
                    .maxOfOrNull(ArchiveMessagePositionEntity::archiveOrdinal)
                outsideMax?.let { Math.addExact(it, 1L) } ?: 0L
            }
            migrationBeforeReconciliation -> {
                val boundaryOrdinal = requireNotNull(current?.oldestOrdinal) {
                    "Archive backward boundary has no ordinal"
                }
                val returnedBeforeBoundary = page.messages.size.toLong() -
                    if (page.lastId == page.boundaryId) 1L else 0L
                Math.subtractExact(boundaryOrdinal, returnedBeforeBoundary)
            }
            mapped.isNotEmpty() -> {
                val first = mapped.first()
                Math.subtractExact(first.ordinal, first.index.toLong())
            }
            page.direction == ArchiveDirection.BEFORE -> {
                val boundaryOrdinal = requireNotNull(current?.oldestOrdinal) {
                    "Archive backward boundary has no ordinal"
                }
                val returnedBeforeBoundary = page.messages.size.toLong() -
                    if (page.lastId == page.boundaryId) 1L else 0L
                Math.subtractExact(boundaryOrdinal, returnedBeforeBoundary)
            }
            page.direction == ArchiveDirection.AFTER -> {
                val boundaryOrdinal = requireNotNull(current?.newestOrdinal) {
                    "Archive forward boundary has no ordinal"
                }
                Math.addExact(
                    boundaryOrdinal,
                    if (page.firstId == page.boundaryId) 0L else 1L,
                )
            }
            else -> 0L
        }
        if (migrationReconciliationPage) {
            val mappedIdSet = mapped.map(MappedArchivePosition::messageId).toSet()
            val prefixPositions = if (migrationBeforeReconciliation) {
                val oldestOrdinal = requireNotNull(current?.oldestOrdinal) {
                    "Archive backward boundary has no ordinal"
                }
                scopePositions.filter { position ->
                    position.archiveOrdinal < oldestOrdinal && position.messageId !in mappedIdSet
                }
            } else {
                emptyList()
            }
            val prefixShift = prefixPositions.maxOfOrNull(
                ArchiveMessagePositionEntity::archiveOrdinal,
            )?.let { prefixMax ->
                Math.subtractExact(Math.subtractExact(startOrdinal, 1L), prefixMax)
            } ?: 0L
            val rebasedPrefix = prefixPositions.map { position ->
                position.copy(archiveOrdinal = Math.addExact(position.archiveOrdinal, prefixShift))
            }
            val rebasedMapped = mapped.map { position ->
                position.copy(ordinal = Math.addExact(startOrdinal, position.index.toLong()))
            }
            prefixPositions.forEach { position ->
                check(
                    dao.deleteArchivePosition(
                        position.accountId,
                        position.messageId,
                        position.archiveAuthority,
                        position.archiveScope,
                        position.archiveOrdinal,
                    ) == 1,
                ) { "Migrated archive prefix changed during reconciliation" }
            }
            mapped.forEach { position ->
                check(
                    dao.deleteArchivePosition(
                        page.key.accountId,
                        position.messageId,
                        page.key.archiveAuthority,
                        page.key.scope,
                        position.ordinal,
                    ) == 1,
                ) { "Migrated archive position changed during reconciliation" }
            }
            rebasedPrefix.forEach { position ->
                dao.insertArchivePosition(position)
            }
            rebasedMapped.forEach { position ->
                dao.insertArchivePosition(
                    ArchiveMessagePositionEntity(
                        accountId = page.key.accountId,
                        archiveAuthority = page.key.archiveAuthority,
                        archiveScope = page.key.scope,
                        archiveOrdinal = position.ordinal,
                        messageId = position.messageId,
                    ),
                )
            }
            mapped = rebasedMapped
        }
        mapped.forEach { position ->
            require(position.ordinal == Math.addExact(startOrdinal, position.index.toLong())) {
                "Archive overlap has incompatible ordering"
            }
        }

        var ingested = 0
        page.messages.forEachIndexed { index, archived ->
            val message = archived.message ?: return@forEachIndexed
            val archiveAlias = TrustedIdentityAlias(
                kind = IdentityAliasKind.MAM_RESULT,
                authority = aliasAuthority,
                value = archived.resultId,
            )
            val aliases = (message.aliases + archiveAlias).distinct()
            val result = ingestInTransaction(
                message.withoutArchivePosition().copy(aliases = aliases),
                preserveStoredThreadLineage = true,
            )
            if (result.identityConflict) {
                throw ArchivePageRejectedException("Archive page contains conflicting identity evidence")
            }
            attachArchivePosition(
                result.messageId,
                page.key.accountId,
                ArchivePosition(
                    authority = page.key.archiveAuthority,
                    scope = page.key.scope,
                    ordinal = Math.addExact(startOrdinal, index.toLong()),
                ),
            )
            ingested++
        }

        val pageOldestOrdinal = page.firstId?.let { startOrdinal }
        val pageNewestOrdinal = page.lastId?.let {
            Math.addExact(startOrdinal, page.messages.lastIndex.toLong())
        }
        val next = ArchiveCursorEntity(
            accountId = page.key.accountId,
            archiveAuthority = page.key.archiveAuthority,
            scope = page.key.scope,
            oldestId = when (page.direction) {
                ArchiveDirection.BOOTSTRAP, ArchiveDirection.BEFORE -> page.firstId ?: current?.oldestId
                ArchiveDirection.AFTER -> current?.oldestId
            },
            newestId = when (page.direction) {
                ArchiveDirection.BOOTSTRAP, ArchiveDirection.AFTER -> page.lastId ?: current?.newestId
                ArchiveDirection.BEFORE -> current?.newestId
            },
            hasEarlier = when (page.direction) {
                ArchiveDirection.BOOTSTRAP, ArchiveDirection.BEFORE -> page.hasEarlier
                ArchiveDirection.AFTER -> current?.hasEarlier ?: false
            },
            retryableError = null,
            oldestOrdinal = when (page.direction) {
                ArchiveDirection.BOOTSTRAP, ArchiveDirection.BEFORE -> pageOldestOrdinal ?: current?.oldestOrdinal
                ArchiveDirection.AFTER -> current?.oldestOrdinal
            },
            newestOrdinal = when (page.direction) {
                ArchiveDirection.BOOTSTRAP, ArchiveDirection.AFTER -> pageNewestOrdinal ?: current?.newestOrdinal
                ArchiveDirection.BEFORE -> current?.newestOrdinal
            },
        )
        writeBoundary(MessageWriteBoundary.BEFORE_ARCHIVE_CURSOR)
        dao.upsertArchiveCursor(next)
        writeBoundary(MessageWriteBoundary.AFTER_ARCHIVE_CURSOR)
        ArchivePageResult(ArchivePageStatus.APPLIED, next, ingested)
    }

    suspend fun claim(accountId: String, operationId: String, generation: Long): OutboxClaim? {
        require(generation > 0) { "Connection generation must be positive" }
        return database.withTransaction {
            val current = database.messageDao().outbox(accountId, operationId)
                ?: return@withTransaction null
            if (current.status != OutboxStatus.PENDING) return@withTransaction null
            val claimed = current.copy(
                status = OutboxStatus.IN_FLIGHT,
                generation = generation,
                attempt = current.attempt + 1,
                failureReason = null,
            )
            database.messageDao().updateOutbox(claimed)
            OutboxClaim(accountId, operationId, generation, claimed.attempt)
        }
    }

    suspend fun recordDefinitePreHandoffFailure(claim: OutboxClaim): OutboxEntity? =
        transitionClaim(claim, setOf(OutboxStatus.IN_FLIGHT)) {
            it.copy(status = OutboxStatus.PENDING, generation = null, failureReason = null)
        }

    suspend fun recordPotentialDelivery(claim: OutboxClaim): OutboxEntity? =
        transitionClaim(claim, setOf(OutboxStatus.IN_FLIGHT)) {
            it.copy(status = OutboxStatus.UNCERTAIN, failureReason = null)
        }

    suspend fun retryUncertain(key: RetryUncertainKey): OutboxEntity? =
        database.withTransaction {
            val current = database.messageDao().outbox(key.accountId, key.operationId)
                ?: return@withTransaction null
            if (current.status != OutboxStatus.UNCERTAIN ||
                current.generation != key.generation ||
                current.attempt != key.attempt
            ) return@withTransaction null
            current.copy(status = OutboxStatus.PENDING, generation = null, failureReason = null).also {
                database.messageDao().updateOutbox(it)
            }
        }

    suspend fun recordDefiniteFailure(
        claim: OutboxClaim,
        reason: String,
    ): OutboxEntity? {
        require(reason.isNotEmpty()) { "Definite failure reason must not be empty" }
        return transitionClaim(
            claim,
            setOf(
                OutboxStatus.IN_FLIGHT,
                OutboxStatus.ACKNOWLEDGED,
                OutboxStatus.UNCERTAIN,
            ),
        ) {
            it.copy(status = OutboxStatus.FAILED, failureReason = reason)
        }
    }

    suspend fun recordProtocolFailure(
        accountId: String,
        generation: Long,
        operationId: String,
        peer: String,
        reason: String,
    ): OutboxEntity? {
        require(reason.isNotEmpty()) { "Protocol failure reason must not be empty" }
        return database.withTransaction {
            val dao = database.messageDao()
            val current = dao.outbox(accountId, operationId) ?: return@withTransaction null
            val message = dao.message(accountId, current.messageId) ?: return@withTransaction null
            if (current.status !in setOf(
                    OutboxStatus.IN_FLIGHT,
                    OutboxStatus.ACKNOWLEDGED,
                    OutboxStatus.UNCERTAIN,
                ) ||
                current.generation != generation ||
                current.attempt != 1 ||
                message.peerJid != peer ||
                message.messageKind != MessageKind.CHAT
            ) return@withTransaction null
            current.copy(status = OutboxStatus.FAILED, failureReason = reason).also {
                dao.updateOutbox(it)
            }
        }
    }

    suspend fun messages(accountId: String): List<MessageEntity> = database.messageDao().messages(accountId)

    suspend fun aliases(accountId: String): List<TrustedIdentityAliasEntity> =
        database.messageDao().trustedAliases(accountId)

    suspend fun conflicts(accountId: String): List<IdentityConflictEntity> =
        database.messageDao().conflicts(accountId)

    suspend fun outbox(accountId: String, operationId: String): OutboxEntity? =
        database.messageDao().outbox(accountId, operationId)

    suspend fun outboxes(accountId: String): List<OutboxEntity> = database.messageDao().outboxes(accountId)

    suspend fun pendingOutbound(accountId: String): List<PendingOutbound> =
        database.messageDao().pendingOutbound(accountId)

    suspend fun accountBareJid(accountId: String): String? = database.messageDao().accountBareJid(accountId)

    private suspend fun transition(
        accountId: String,
        operationId: String,
        expected: Set<OutboxStatus>,
        update: (OutboxEntity) -> OutboxEntity,
    ): OutboxEntity = database.withTransaction {
        val current = requireNotNull(database.messageDao().outbox(accountId, operationId)) {
            "Unknown outbox operation"
        }
        check(current.status in expected) {
            "Cannot transition ${current.status} operation $operationId"
        }
        val updated = update(current)
        database.messageDao().updateOutbox(updated)
        updated
    }

    private suspend fun transitionClaim(
        claim: OutboxClaim,
        expected: Set<OutboxStatus>,
        update: (OutboxEntity) -> OutboxEntity,
    ): OutboxEntity? = database.withTransaction {
        val current = database.messageDao().outbox(claim.accountId, claim.operationId)
            ?: return@withTransaction null
        if (current.status !in expected ||
            current.generation != claim.generation ||
            current.attempt != claim.attempt
        ) return@withTransaction null
        val updated = update(current)
        database.messageDao().updateOutbox(updated)
        updated
    }

    private suspend fun canMerge(
        first: MessageEntity,
        second: MessageEntity,
        incoming: IncomingMessage,
    ): Boolean {
        if (!first.isCompatibleWith(incoming) || !second.isCompatibleWith(incoming)) return false
        val archivePositions = (
            database.messageDao().archivePositions(first.accountId, first.localMessageId) +
                database.messageDao().archivePositions(second.accountId, second.localMessageId)
            )
            .groupBy { it.archiveAuthority to it.archiveScope }
        if (archivePositions.values.any { positions ->
                positions.map(ArchiveMessagePositionEntity::archiveOrdinal).distinct().size > 1
            }
        ) return false
        return database.messageDao().outboxesForMessages(
            incoming.accountId,
            listOf(first.localMessageId, second.localMessageId),
        ).size <= 1
    }

    private suspend fun mergePair(
        first: MessageEntity,
        second: MessageEntity,
    ): MessageEntity {
        val dao = database.messageDao()
        val winner = minOf(first, second, compareBy(MessageEntity::localSequence, MessageEntity::localMessageId))
        val loser = if (winner.localMessageId == first.localMessageId) second else first
        dao.reparentAliases(winner.accountId, loser.localMessageId, winner.localMessageId)
        dao.reparentOutbox(winner.accountId, loser.localMessageId, winner.localMessageId)
        reparentConflicts(loser, winner)
        reparentArchivePositions(loser, winner)
        writeBoundary(MessageWriteBoundary.AFTER_DEPENDENT_REPARENT)
        dao.deleteMessage(loser)
        val withArchive = if (winner.archiveOrdinal == null && loser.archiveOrdinal != null) {
            winner.copy(archiveOrdinal = loser.archiveOrdinal)
        } else winner
        val reconciled = withArchive.withPreferredTime(loser.sentAtEpochMs, loser.sentTimeSource)
        if (reconciled != winner) dao.updateMessage(reconciled)
        return reconciled
    }

    private suspend fun reparentArchivePositions(loser: MessageEntity, winner: MessageEntity) {
        val dao = database.messageDao()
        dao.archivePositions(loser.accountId, loser.localMessageId).forEach { position ->
            val winnerPosition = dao.archivePosition(
                winner.accountId,
                position.archiveAuthority,
                position.archiveScope,
                winner.localMessageId,
            )
            if (winnerPosition == null) {
                check(
                    dao.reparentArchivePosition(
                        accountId = loser.accountId,
                        loserId = loser.localMessageId,
                        winnerId = winner.localMessageId,
                        archiveAuthority = position.archiveAuthority,
                        archiveScope = position.archiveScope,
                        archiveOrdinal = position.archiveOrdinal,
                    ) == 1,
                ) { "Archive position changed during message merge" }
            } else {
                require(winnerPosition.archiveOrdinal == position.archiveOrdinal) {
                    "Compatible identities have conflicting positions in one archive scope"
                }
                check(
                    dao.deleteArchivePosition(
                        accountId = loser.accountId,
                        messageId = loser.localMessageId,
                        archiveAuthority = position.archiveAuthority,
                        archiveScope = position.archiveScope,
                        archiveOrdinal = position.archiveOrdinal,
                    ) == 1,
                ) { "Archive position changed during message merge" }
            }
        }
    }

    private suspend fun reparentConflicts(loser: MessageEntity, winner: MessageEntity) {
        val dao = database.messageDao()
        dao.conflictsForMessage(loser.accountId, loser.localMessageId).forEach { conflict ->
            dao.deleteConflict(conflict)
            val ids = listOf(
                if (conflict.firstMessageId == loser.localMessageId) {
                    winner.localMessageId
                } else {
                    conflict.firstMessageId
                },
                if (conflict.secondMessageId == loser.localMessageId) {
                    winner.localMessageId
                } else {
                    conflict.secondMessageId
                },
            ).sorted()
            if (ids[0] != ids[1]) {
                dao.insertConflict(
                    conflict.copy(firstMessageId = ids[0], secondMessageId = ids[1]),
                )
            }
        }
    }

    private suspend fun recordConflict(
        incoming: IncomingMessage,
        alias: TrustedIdentityAlias,
        first: MessageEntity,
        second: MessageEntity,
    ) {
        val ids = listOf(first.localMessageId, second.localMessageId).sorted()
        database.messageDao().insertConflict(
            IdentityConflictEntity(
                accountId = incoming.accountId,
                kind = alias.kind,
                authority = alias.authority,
                value = alias.value,
                firstMessageId = ids[0],
                secondMessageId = ids[1],
                detectedAtSequence = maxOf(first.localSequence, second.localSequence),
            ),
        )
    }

    private suspend fun confirmMatchingOutbox(incoming: IncomingMessage, messageId: String) {
        val dao = database.messageDao()
        incoming.aliases
            .filter {
                it.kind == IdentityAliasKind.ORIGIN_ID &&
                    it.authority == OUTBOUND_ORIGIN_AUTHORITY
            }
            .forEach { alias ->
                val trusted = dao.trustedAlias(incoming.accountId, alias.kind, alias.authority, alias.value)
                if (trusted?.messageId != messageId) return@forEach
                val outbox = dao.outboxByOrigin(incoming.accountId, alias.value) ?: return@forEach
                check(outbox.messageId == messageId) { "Origin alias resolved to a different message" }
                dao.updateOutbox(
                    outbox.copy(
                        status = OutboxStatus.CONFIRMED,
                        failureReason = null,
                    ),
                )
            }
    }

    private suspend fun allocateSequence(accountId: String): Long {
        val dao = database.messageDao()
        if (dao.insertSequence(AccountMessageSequenceEntity(accountId, nextValue = 2)) != -1L) {
            return 1
        }
        val current = requireNotNull(dao.nextSequence(accountId))
        check(current < Long.MAX_VALUE) { "Local message sequence exhausted" }
        check(dao.advanceSequence(accountId, current, current + 1) == 1) {
            "Local message sequence changed during transaction"
        }
        return current
    }

    private suspend fun ensureScope(
        accountId: String,
        peerJid: String,
        messageKind: MessageKind,
        threadId: String?,
        parentThreadId: String?,
        preserveStoredThreadLineage: Boolean,
    ): String? {
        val dao = database.messageDao()
        require(dao.accountExists(accountId)) { "Unknown message account" }
        if (messageKind == MessageKind.GROUPCHAT) {
            dao.savePeerRoom(accountId, peerJid, true)
        } else {
            dao.insertPeer(PeerEntity(accountId, peerJid))
        }
        if (threadId == null) return null
        val existing = dao.thread(accountId, peerJid, messageKind, threadId)
        if (existing == null) {
            insertParentPlaceholder(dao, accountId, peerJid, messageKind, parentThreadId)
            dao.insertThread(
                MessageThreadEntity(accountId, peerJid, messageKind, threadId, parentThreadId),
            )
            return parentThreadId
        }
        if (existing.parentThreadId == parentThreadId) return parentThreadId
        if (parentThreadId == null) {
            if (preserveStoredThreadLineage) return existing.parentThreadId
            throw IllegalArgumentException("Thread lineage conflict")
        }
        if (preserveStoredThreadLineage && existing.parentThreadId != null) return existing.parentThreadId
        if (existing.parentThreadId == null) {
            if (preserveStoredThreadLineage && dao.threadHasMessages(accountId, peerJid, messageKind, threadId)) {
                return null
            }
            insertParentPlaceholder(dao, accountId, peerJid, messageKind, parentThreadId)
            val resolved = dao.resolveThreadParent(accountId, peerJid, messageKind, threadId, parentThreadId) == 1
            if (resolved) return parentThreadId
            if (preserveStoredThreadLineage) return null
            throw IllegalArgumentException("Thread lineage conflict")
        }
        throw IllegalArgumentException("Thread lineage conflict")
    }

    private suspend fun insertParentPlaceholder(
        dao: MessageDao,
        accountId: String,
        peerJid: String,
        messageKind: MessageKind,
        parentThreadId: String?,
    ) {
        if (parentThreadId != null && dao.thread(accountId, peerJid, messageKind, parentThreadId) == null) {
            dao.insertThread(MessageThreadEntity(accountId, peerJid, messageKind, parentThreadId, null))
        }
    }

    companion object {
        const val OUTBOUND_ORIGIN_AUTHORITY = "OUTBOUND"

        internal fun observingWrites(
            database: NemaDatabase,
            observer: (MessageWriteBoundary) -> Unit,
        ): MessageStore = MessageStore(database, observer, System::currentTimeMillis)
    }
}

private fun IncomingMessage.toEntity(localSequence: Long) = MessageEntity(
    accountId = accountId,
    localMessageId = localMessageId,
    peerJid = peerJid,
    senderJid = senderJid,
    direction = direction,
    messageKind = messageKind,
    threadId = threadId,
    parentThreadId = parentThreadId,
    body = body,
    localSequence = localSequence,
    archiveOrdinal = archiveOrdinal,
    sentAtEpochMs = sentAtEpochMs,
    sentTimeSource = sentTimeSource,
    attachmentUrl = attachmentUrl,
    attachmentName = attachmentName,
    attachmentMime = attachmentMime,
    attachmentSize = attachmentSize,
    replyToId = replyToId,
    replyToJid = replyToJid,
    replyFallbackBody = replyFallbackBody,
)

private fun TrustedIdentityAlias.toEntity(accountId: String, messageId: String) =
    TrustedIdentityAliasEntity(accountId, kind, authority, value, messageId, IdentityAliasStatus.TRUSTED)

private fun MessageEntity.matches(intent: OutboundIntent): Boolean =
    accountId == intent.accountId &&
        localMessageId == intent.localMessageId &&
        peerJid == intent.peerJid &&
        senderJid == intent.senderJid &&
        direction == MessageDirection.OUTBOUND &&
        messageKind == intent.messageKind &&
        threadId == intent.threadId &&
        parentThreadId == intent.parentThreadId &&
        body == intent.body &&
        attachmentUrl == intent.attachmentUrl &&
        attachmentName == intent.attachmentName &&
        attachmentMime == intent.attachmentMime &&
        attachmentSize == intent.attachmentSize &&
        replyToId == intent.replyToId &&
        replyToJid == intent.replyToJid &&
        replyFallbackBody == intent.replyFallbackBody

private fun MessageEntity.isCompatibleWith(incoming: IncomingMessage): Boolean =
    accountId == incoming.accountId &&
        peerJid == incoming.peerJid &&
        (senderJid == incoming.senderJid ||
            (messageKind == MessageKind.GROUPCHAT &&
                direction == MessageDirection.OUTBOUND &&
                incoming.direction == MessageDirection.OUTBOUND)) &&
        direction == incoming.direction &&
        messageKind == incoming.messageKind &&
        threadId == incoming.threadId &&
        parentThreadId == incoming.parentThreadId &&
        body == incoming.body &&
        attachmentUrl == incoming.attachmentUrl &&
        attachmentName == incoming.attachmentName &&
        attachmentMime == incoming.attachmentMime &&
        attachmentSize == incoming.attachmentSize &&
        replyToId == incoming.replyToId &&
        replyToJid == incoming.replyToJid &&
        replyFallbackBody == incoming.replyFallbackBody

private data class ArchivePosition(
    val authority: String,
    val scope: String,
    val ordinal: Long,
)

private fun IncomingMessage.archivePosition(): ArchivePosition? = archiveOrdinal?.let {
    ArchivePosition(requireNotNull(archiveAuthority), requireNotNull(archiveScope), it)
}

private fun IncomingMessage.withoutArchivePosition(): IncomingMessage = copy(
    archiveOrdinal = null,
    archiveAuthority = null,
    archiveScope = null,
)

private fun IncomingMessage.withStoredTime(clock: () -> Long): IncomingMessage =
    if (sentAtEpochMs != null) this else copy(
        sentAtEpochMs = clock(),
        sentTimeSource = MessageTimeSource.LOCAL,
    )

private fun MessageEntity.withPreferredTime(
    candidateTime: Long?,
    candidateSource: MessageTimeSource?,
): MessageEntity {
    if (candidateTime == null || candidateSource == null) return this
    val currentSource = sentTimeSource
    if (currentSource == MessageTimeSource.LOCAL && candidateSource == MessageTimeSource.LOCAL) return this
    if (direction == MessageDirection.OUTBOUND &&
        (currentSource == MessageTimeSource.LOCAL || candidateSource == MessageTimeSource.LOCAL)
    ) {
        return if (candidateSource == MessageTimeSource.LOCAL) copy(
            sentAtEpochMs = candidateTime,
            sentTimeSource = candidateSource,
        ) else this
    }
    val preferCandidate = currentSource == null ||
        candidateSource.authorityRank > currentSource.authorityRank ||
        (candidateSource == currentSource && candidateTime < requireNotNull(sentAtEpochMs))
    return if (preferCandidate) copy(
        sentAtEpochMs = candidateTime,
        sentTimeSource = candidateSource,
    ) else this
}

private val MessageTimeSource.authorityRank: Int
    get() = when (this) {
        MessageTimeSource.LOCAL -> 0
        MessageTimeSource.DELAYED -> 1
        MessageTimeSource.CARBON -> 2
        MessageTimeSource.MAM -> 3
    }
