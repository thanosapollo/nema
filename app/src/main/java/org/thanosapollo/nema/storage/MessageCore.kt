package org.thanosapollo.nema.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadIdFactory
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.UuidThreadIdFactory
import org.thanosapollo.nema.thread.draftKey
import org.thanosapollo.nema.xmpp.reactions.decodeReactionEmojis
import org.thanosapollo.nema.xmpp.reactions.encodeReactionEmojis
import org.thanosapollo.nema.xmpp.reactions.reactionDisplaysFor
import org.thanosapollo.nema.xmpp.reactions.toggleReaction
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource
import org.thanosapollo.nema.xmpp.transport.MessageReceiptStage

private val REACTION_REFERENCE_KINDS = setOf(
    IdentityAliasKind.MESSAGE_ID,
    IdentityAliasKind.ORIGIN_ID,
    IdentityAliasKind.STANZA_ID,
)

enum class ReactionMutationOutcome { WRITTEN, SUPERSEDED }
data class PendingReactionSelection(
    val accepted: List<MessageReactionEntity>, val unsupported: List<MessageReactionEntity>,
)

@Dao
abstract class MessageDao {
    @Query("SELECT EXISTS(SELECT 1 FROM accounts WHERE id = :accountId)")
    abstract suspend fun accountExists(accountId: String): Boolean

    @Query("SELECT bareJid FROM accounts WHERE id = :accountId")
    abstract suspend fun accountBareJid(accountId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPeer(peer: PeerEntity): Long

    @Query(
        """
        UPDATE peers SET lastReadLocalSequence = (
          SELECT COALESCE(MAX(localSequence), 0) FROM messages
          WHERE accountId = :accountId AND peerJid = :peerJid
        )
        WHERE accountId = :accountId AND jid = :peerJid
        """,
    )
    abstract suspend fun updatePeerLastRead(accountId: String, peerJid: String): Int

    @Query(
        """
        UPDATE peers SET lastReadLocalSequence = MAX(lastReadLocalSequence, :localSequence)
        WHERE accountId = :accountId AND jid = :peerJid
        """,
    )
    abstract suspend fun advancePeerLastRead(accountId: String, peerJid: String, localSequence: Long): Int

    @Query(
        """
        SELECT messages.* FROM messages
        INNER JOIN trusted_identity_aliases AS alias
          ON alias.accountId = messages.accountId
         AND alias.messageId = messages.localMessageId
        WHERE messages.accountId = :accountId
          AND messages.peerJid = :peerJid
          AND messages.direction = 'INBOUND'
          AND messages.messageKind = 'CHAT'
          AND alias.value = :targetId
          AND alias.status = 'TRUSTED'
          AND alias.kind IN ('MESSAGE_ID', 'ORIGIN_ID', 'STANZA_ID')
        """,
    )
    abstract suspend fun inboundChatByAliasValue(
        accountId: String,
        peerJid: String,
        targetId: String,
    ): List<MessageEntity>

    @Query(
        """
        SELECT messages.* FROM messages
        INNER JOIN trusted_identity_aliases AS alias
          ON alias.accountId = messages.accountId
         AND alias.messageId = messages.localMessageId
        WHERE messages.accountId = :accountId
          AND messages.peerJid = :peerJid
          AND messages.direction = 'OUTBOUND'
          AND messages.messageKind = 'CHAT'
          AND alias.value = :targetId
          AND alias.status = 'TRUSTED'
          AND alias.kind IN ('MESSAGE_ID', 'ORIGIN_ID', 'STANZA_ID')
        """,
    )
    abstract suspend fun outboundChatByAliasValue(
        accountId: String,
        peerJid: String,
        targetId: String,
    ): List<MessageEntity>

    @Query(
        """
        SELECT messages.* FROM messages
        INNER JOIN trusted_identity_aliases AS alias
          ON alias.accountId = messages.accountId
         AND alias.messageId = messages.localMessageId
        WHERE messages.accountId = :accountId
          AND messages.peerJid = :peerJid
          AND messages.messageKind = 'CHAT'
          AND alias.kind = 'MESSAGE_ID'
          AND alias.value = :targetId
          AND alias.status = 'TRUSTED'
        """,
    )
    abstract suspend fun chatByMessageId(
        accountId: String,
        peerJid: String,
        targetId: String,
    ): List<MessageEntity>

    @Query(
        """
        SELECT * FROM message_reactions
        WHERE accountId = :accountId AND peerJid = :peerJid
        """,
    )
    abstract fun observeMessageReactions(accountId: String, peerJid: String): kotlinx.coroutines.flow.Flow<List<MessageReactionEntity>>

    @Query(
        """
        SELECT * FROM message_reactions
        WHERE accountId = :accountId AND peerJid = :peerJid
        """,
    )
    abstract suspend fun messageReactions(accountId: String, peerJid: String): List<MessageReactionEntity>

    @Query(
        """
        SELECT * FROM message_reactions
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND localMessageId = :localMessageId
        """,
    )
    abstract suspend fun messageReactionsForMessage(
        accountId: String,
        peerJid: String,
        localMessageId: String,
    ): List<MessageReactionEntity>

    @Query(
        """
        SELECT DISTINCT reaction.* FROM message_reactions AS reaction
        INNER JOIN trusted_identity_aliases AS alias
          ON alias.accountId = reaction.accountId
         AND alias.messageId = :messageId
         AND alias.value = reaction.wireTargetId
        WHERE reaction.accountId = :accountId AND reaction.peerJid = :peerJid
          AND reaction.localMessageId IS NULL
          AND alias.status = 'TRUSTED'
          AND alias.kind IN ('MESSAGE_ID', 'ORIGIN_ID', 'STANZA_ID')
        """,
    )
    abstract suspend fun pendingReactionsForMessageAliases(
        accountId: String,
        peerJid: String,
        messageId: String,
    ): List<MessageReactionEntity>

    @Query(
        """
        SELECT * FROM message_reactions
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND senderBareJid = :senderBareJid AND targetKey = :targetKey
        """,
    )
    abstract suspend fun messageReaction(
        accountId: String,
        peerJid: String,
        senderBareJid: String,
        targetKey: String,
    ): MessageReactionEntity?

    @Upsert
    protected abstract suspend fun upsertMessageReaction(reaction: MessageReactionEntity)

    @Query(
        """
        DELETE FROM message_reactions
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND senderBareJid = :senderBareJid AND targetKey = :targetKey
        """,
    )
    protected abstract suspend fun deleteMessageReaction(
        accountId: String,
        peerJid: String,
        senderBareJid: String,
        targetKey: String,
    ): Int

    @Query("SELECT changes()")
    protected abstract suspend fun changedRowCount(): Int

    private suspend fun requireValidReaction(row: MessageReactionEntity) {
        check(row.accountId.isNotBlank() && row.peerJid.isNotBlank() && row.senderBareJid.isNotBlank())
        check(row.wireTargetId.isNotBlank() && row.targetKey == reactionTargetKey(row.localMessageId, row.wireTargetId))
        check(encodeReactionEmojis(decodeReactionEmojis(row.emojis)) == row.emojis)
        check(row.revision in 1 until Long.MAX_VALUE)
        row.localMessageId?.let { id ->
            val owner = message(row.accountId, id)
            check(owner != null && owner.peerJid == row.peerJid)
        }
    }

    suspend fun validatedReaction(
        accountId: String,
        peerJid: String,
        senderBareJid: String,
        targetKey: String,
    ): MessageReactionEntity? = messageReaction(accountId, peerJid, senderBareJid, targetKey)
        ?.also { requireValidReaction(it) }

    private suspend fun writeReaction(candidate: MessageReactionEntity): ReactionMutationOutcome {
        requireValidReaction(candidate)
        val current = messageReaction(candidate.accountId, candidate.peerJid, candidate.senderBareJid, candidate.targetKey)
        current?.let { requireValidReaction(it) }
        check(current == null || current.revision < Long.MAX_VALUE - 1)
        upsertMessageReaction(candidate.copy(revision = (current?.revision ?: 0) + 1))
        check(changedRowCount() == 1)
        return ReactionMutationOutcome.WRITTEN
    }

    @Transaction
    internal open suspend fun writeReactionFullSetIfRevision(
        accountId: String,
        peerJid: String,
        senderBareJid: String,
        targetKey: String,
        expectedRevision: Long,
        candidateFactory: () -> MessageReactionEntity,
    ): Boolean {
        val current = messageReaction(accountId, peerJid, senderBareJid, targetKey)
        current?.let { requireValidReaction(it) }
        val observedRevision = current?.revision ?: 0
        if (expectedRevision !in 0L..Long.MAX_VALUE - 2) return false
        if (observedRevision == Long.MAX_VALUE - 1) return false
        if (observedRevision != expectedRevision) return false
        val candidate = candidateFactory()
        check(candidate.accountId == accountId && candidate.peerJid == peerJid &&
            candidate.senderBareJid == senderBareJid && candidate.targetKey == targetKey)
        check(writeReactionFullSet(candidate) == ReactionMutationOutcome.WRITTEN)
        return true
    }

    @Transaction
    open suspend fun writeReactionFullSet(
        candidate: MessageReactionEntity,
        keepNewest: Boolean = false,
    ): ReactionMutationOutcome {
        requireValidReaction(candidate)
        val current = messageReaction(candidate.accountId, candidate.peerJid, candidate.senderBareJid, candidate.targetKey)
        current?.let { requireValidReaction(it) }
        return if (keepNewest && current != null && current.updatedAtMs > candidate.updatedAtMs) {
            ReactionMutationOutcome.SUPERSEDED
        } else writeReaction(candidate)
    }

    @Transaction
    open suspend fun moveMessageReaction(
        source: MessageReactionEntity,
        destination: MessageReactionEntity,
    ): ReactionMutationOutcome {
        requireValidReaction(source)
        requireValidReaction(destination)
        check(source.targetKey != destination.targetKey && source.accountId == destination.accountId && source.peerJid == destination.peerJid && source.senderBareJid == destination.senderBareJid)
        check(messageReaction(source.accountId, source.peerJid, source.senderBareJid, source.targetKey) == source)
        val current = messageReaction(destination.accountId, destination.peerJid, destination.senderBareJid, destination.targetKey)
        current?.let { requireValidReaction(it) }
        val outcome = if (current != null && current.updatedAtMs > destination.updatedAtMs) {
            ReactionMutationOutcome.SUPERSEDED
        } else writeReaction(destination)
        check(deleteMessageReaction(source.accountId, source.peerJid, source.senderBareJid, source.targetKey) == 1)
        return outcome
    }

    @Transaction
    open suspend fun retireUnsupportedPendingReaction(source: MessageReactionEntity): Int {
        requireValidReaction(source)
        check(source.localMessageId == null)
        check(messageReaction(source.accountId, source.peerJid, source.senderBareJid, source.targetKey) == source)
        return deleteMessageReaction(
            source.accountId, source.peerJid, source.senderBareJid, source.targetKey,
        ).also { check(it == 1) }
    }

    open suspend fun classifyPendingReactions(
        accountId: String,
        peerJid: String,
        messageId: String,
    ): PendingReactionSelection? {
        val aliases = trustedAliasesForMessage(accountId, messageId).filter { it.kind in REACTION_REFERENCE_KINDS }
        val candidates = pendingReactionsForMessageAliases(accountId, peerJid, messageId)
        candidates.forEach { requireValidReaction(it) }
        val messageAliases = aliases.filter { it.kind == IdentityAliasKind.MESSAGE_ID }
        if (messageAliases.size != 1) return null
        val acceptedValue = messageAliases.single().value
        val owners = chatByMessageId(accountId, peerJid, acceptedValue).map { it.localMessageId }.distinct()
        if (owners != listOf(messageId)) return null
        val accepted = candidates.filter { it.wireTargetId == acceptedValue }
        val unsupported = candidates.filter { row ->
            row.wireTargetId != acceptedValue && chatByMessageId(accountId, peerJid, row.wireTargetId).isEmpty()
        }
        return PendingReactionSelection(accepted, unsupported)
    }

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

    @Query("SELECT * FROM peers WHERE accountId = :accountId AND room = 1 ORDER BY jid")
    abstract suspend fun rooms(accountId: String): List<PeerEntity>

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

    @Upsert
    abstract suspend fun saveDirectThreadSession(session: DirectThreadSessionEntity)

    @Query(
        """
        SELECT * FROM direct_thread_sessions
        WHERE accountId = :accountId AND peerJid = :peerJid
        """,
    )
    abstract suspend fun directThreadSession(accountId: String, peerJid: String): DirectThreadSessionEntity?

    @Query(
        """
        SELECT * FROM direct_thread_sessions
        WHERE accountId = :accountId AND peerJid = :peerJid
        """,
    )
    abstract fun observeDirectThreadSession(accountId: String, peerJid: String): Flow<DirectThreadSessionEntity?>

    @Query("DELETE FROM direct_thread_sessions WHERE accountId = :accountId AND peerJid = :peerJid")
    abstract suspend fun deleteDirectThreadSession(accountId: String, peerJid: String): Int

    @Upsert
    abstract suspend fun saveThreadTitle(title: MessageThreadTitleEntity)

    @Query(
        """
        SELECT * FROM message_thread_titles
        WHERE accountId = :accountId AND peerJid = :peerJid
        """,
    )
    abstract fun observeThreadTitles(accountId: String, peerJid: String): Flow<List<MessageThreadTitleEntity>>

    @Query(
        """
        DELETE FROM message_thread_titles
        WHERE accountId = :accountId AND peerJid = :peerJid
          AND messageKind = :messageKind AND threadId = :threadId
        """,
    )
    abstract suspend fun deleteThreadTitle(
        accountId: String,
        peerJid: String,
        messageKind: MessageKind,
        threadId: String,
    ): Int

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
          AND NOT EXISTS (
            SELECT 1 FROM direct_thread_sessions
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

    @Query(
        """
        UPDATE messages SET correctionTargetMessageId = :winnerId
        WHERE accountId = :accountId AND correctionTargetMessageId = :loserId
        """,
    )
    abstract suspend fun reparentCorrections(accountId: String, loserId: String, winnerId: String): Int

    @Query(
        """
        SELECT * FROM messages
        WHERE accountId = :accountId
          AND peerJid = :peerJid
          AND senderJid = :senderJid
          AND messageKind = 'CHAT'
          AND direction = :direction
          AND replaceId = :replaceId
          AND correctionTargetMessageId IS NULL
        ORDER BY localSequence, localMessageId
        """,
    )
    abstract suspend fun unresolvedCorrections(
        accountId: String,
        peerJid: String,
        senderJid: String,
        direction: MessageDirection,
        replaceId: String,
    ): List<MessageEntity>

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
        WHERE accountId = :accountId AND messageId IN (:messageIds)
        ORDER BY messageId, archiveAuthority, archiveScope, archiveOrdinal
        """,
    )
    abstract suspend fun archivePositionsForMessages(
        accountId: String,
        messageIds: List<String>,
    ): List<ArchiveMessagePositionEntity>

    @Query(
        """
        SELECT * FROM archive_message_positions
        WHERE accountId = :accountId
        ORDER BY messageId, archiveAuthority, archiveScope, archiveOrdinal
        """,
    )
    abstract suspend fun archivePositions(accountId: String): List<ArchiveMessagePositionEntity>

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
        SELECT * FROM messages
        WHERE accountId = :accountId AND peerJid = :peerJid AND direction = 'INBOUND'
          AND (
            (sentTimeSource = 'LOCAL' AND reconciliationObservedAtMs BETWEEN :lowerMs AND :upperMs)
            OR (sentTimeSource = 'MAM' AND sentAtEpochMs BETWEEN :lowerMs AND :upperMs)
          )
        ORDER BY localSequence, localMessageId
        LIMIT :limit
        """,
    )
    abstract suspend fun identitylessReconciliationCandidates(
        accountId: String,
        peerJid: String,
        lowerMs: Long,
        upperMs: Long,
        limit: Int,
    ): List<MessageEntity>

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
            AND messages.replaceId IS NULL
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
          messages.senderJid AS senderJid,
          messages.preview AS preview,
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
              AND unread.unreadEligible = 1
              AND unread.localSequence > COALESCE(peers.lastReadLocalSequence, 0)
          ) AS unreadCount
        FROM latest_messages AS messages
        LEFT JOIN peers
          ON peers.accountId = messages.accountId
         AND peers.jid = messages.peerJid
        ORDER BY messages.localSequence, messages.localMessageId
        """,
    )
    abstract fun observeConversationSummaries(accountId: String): Flow<List<ConversationListRow>>

    @Query(CHEAP_CONVERSATION_SUMMARIES)
    abstract suspend fun cachedConversationSummaries(accountId: String): List<ConversationListRow>

    @Query(CHEAP_CONVERSATION_SUMMARIES)
    abstract fun observeCachedConversationSummaries(accountId: String): Flow<List<ConversationListRow>>

    @Query(
        """
        SELECT * FROM messages
        WHERE accountId = :accountId AND messageKind = 'CHAT'
          AND replaceId IS NULL
        ORDER BY archiveOrdinal IS NULL, archiveOrdinal, localSequence, localMessageId
        """,
    )
    abstract fun observeConversationMessages(accountId: String): Flow<List<MessageEntity>>

    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT messages.*, message_outbox.operationId AS operationId,
          message_outbox.status AS outboxStatus,
          message_outbox.receiptStage AS receiptStage,
          message_outbox.generation AS outboxGeneration,
          message_outbox.attempt AS outboxAttempt,
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
          ) AS correctedBody,
          EXISTS(
            SELECT 1 FROM messages AS correction
            WHERE correction.accountId = messages.accountId
              AND correction.correctionTargetMessageId = messages.localMessageId
          ) AS edited,
          CASE
            WHEN messages.messageKind = 'GROUPCHAT' THEN (
              SELECT CASE WHEN COUNT(*) = 1 THEN MIN(alias.value) END
              FROM trusted_identity_aliases AS alias
              WHERE alias.accountId = messages.accountId
                AND alias.messageId = messages.localMessageId
                AND alias.status = 'TRUSTED'
                AND alias.kind = 'STANZA_ID'
                AND alias.authority = messages.peerJid
            )
            ELSE (
              SELECT alias.value
              FROM trusted_identity_aliases AS alias
              WHERE alias.accountId = messages.accountId
                AND alias.messageId = messages.localMessageId
                AND alias.status = 'TRUSTED'
                AND alias.kind IN ('ORIGIN_ID', 'MESSAGE_ID')
              ORDER BY CASE alias.kind WHEN 'ORIGIN_ID' THEN 0 ELSE 1 END, alias.value
              LIMIT 1
            )
          END AS replyReferenceId,
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
          AND messages.replaceId IS NULL
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

    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT messages.*, message_outbox.operationId AS operationId,
          message_outbox.status AS outboxStatus,
          message_outbox.receiptStage AS receiptStage,
          message_outbox.generation AS outboxGeneration,
          message_outbox.attempt AS outboxAttempt,
          NULL AS correctedBody,
          0 AS edited,
          NULL AS replyReferenceId,
          NULL AS conversationArchiveOrdinal,
          CASE
            WHEN messages.messageKind = 'GROUPCHAT' THEN messages.peerJid
            ELSE COALESCE((SELECT bareJid FROM accounts WHERE accounts.id = messages.accountId), '')
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
          AND messages.replaceId IS NULL
        ORDER BY messages.sentAtEpochMs IS NULL,
          messages.sentAtEpochMs DESC,
          messages.localSequence DESC,
          messages.localMessageId DESC
        LIMIT 80
        """,
    )
    abstract suspend fun cachedDirectTimeline(accountId: String, peerJid: String): List<TimelineRow>

    @Query(
        """
        SELECT alias.*
        FROM trusted_identity_aliases AS alias
        JOIN messages
          ON messages.accountId = alias.accountId
         AND messages.localMessageId = alias.messageId
        WHERE messages.accountId = :accountId
          AND messages.peerJid = :peerJid
          AND messages.messageKind = 'CHAT'
          AND alias.status = 'TRUSTED'
          AND alias.kind IN ('ORIGIN_ID', 'MESSAGE_ID')
        ORDER BY alias.messageId, alias.kind, alias.authority, alias.value
        """,
    )
    abstract fun observeDirectReplyAliases(
        accountId: String,
        peerJid: String,
    ): Flow<List<TrustedIdentityAliasEntity>>

    @Query("SELECT * FROM chat_navigation WHERE accountId = :accountId")
    abstract fun observeNavigation(accountId: String): Flow<ChatNavigationEntity?>

    @Query("SELECT * FROM chat_navigation WHERE accountId = :accountId")
    protected abstract suspend fun navigation(accountId: String): ChatNavigationEntity?

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
        if (peer(accountId, peerJid) == null) {
            insertPeer(PeerEntity(accountId, peerJid))
        }
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

    @Query(
        """
        SELECT EXISTS(
          SELECT 1
          FROM messages
          JOIN trusted_identity_aliases AS alias
            ON alias.accountId = messages.accountId
           AND alias.messageId = messages.localMessageId
           AND alias.status = 'TRUSTED'
           AND alias.kind IN ('ORIGIN_ID', 'MESSAGE_ID')
           AND alias.value = :replyReferenceId
          WHERE messages.accountId = :accountId
            AND messages.localMessageId = :messageId
            AND messages.peerJid = :peerJid
            AND messages.senderJid = :senderJid
            AND messages.body = :body
            AND messages.messageKind = 'CHAT'
            AND ((messages.threadId IS NULL AND :threadId IS NULL) OR messages.threadId = :threadId)
            AND ((messages.parentThreadId IS NULL AND :parentThreadId IS NULL)
              OR messages.parentThreadId = :parentThreadId)
        )
        """,
    )
    protected abstract suspend fun trustedDirectReplyTargetExists(
        accountId: String,
        messageId: String,
        peerJid: String,
        senderJid: String,
        body: String,
        threadId: String?,
        parentThreadId: String?,
        replyReferenceId: String,
    ): Boolean

    @Transaction
    open suspend fun saveNavigationWithDraft(
        expectedNavigation: ChatNavigationEntity,
        navigation: ChatNavigationEntity,
        targetMessageId: String,
        targetSenderJid: String,
        targetBody: String,
        targetThreadId: String?,
        targetParentThreadId: String?,
        draft: MessageDraftEntity,
    ): Boolean {
        require(accountExists(navigation.accountId)) { "Unknown navigation account" }
        require(expectedNavigation.accountId == navigation.accountId) {
            "Expected and destination navigation accounts differ"
        }
        require(draft.accountId == navigation.accountId) { "Draft and navigation accounts differ" }
        require(draft.peerJid == navigation.peerJid) { "Draft and navigation peers differ" }
        require(draft.messageKind == MessageKind.CHAT) { "Navigation draft must be direct chat" }
        val parent = navigation.parentThreadId.orEmpty()
        val navigationThreadKey = navigation.threadId?.let { "${parent.length}:$parent$it" }.orEmpty()
        require(draft.threadKey == navigationThreadKey) { "Draft and navigation threads differ" }
        val replyReferenceId = requireNotNull(draft.replyToId) {
            "Thread reply draft requires a reply reference"
        }
        if (navigation(expectedNavigation.accountId) != expectedNavigation) return false
        if (peer(expectedNavigation.accountId, expectedNavigation.peerJid)?.room == true) return false
        if (!trustedDirectReplyTargetExists(
                accountId = expectedNavigation.accountId,
                messageId = targetMessageId,
                peerJid = expectedNavigation.peerJid,
                senderJid = targetSenderJid,
                body = targetBody,
                threadId = targetThreadId,
                parentThreadId = targetParentThreadId,
                replyReferenceId = replyReferenceId,
            )
        ) return false
        insertPeer(PeerEntity(navigation.accountId, navigation.peerJid))
        upsertDraft(draft)
        upsertNavigation(navigation)
        return true
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
        WHERE accountId = :accountId AND messageId = :messageId AND status = 'TRUSTED'
        """,
    )
    abstract suspend fun trustedAliasesForMessage(
        accountId: String,
        messageId: String,
    ): List<TrustedIdentityAliasEntity>

    @Query(
        """
        SELECT * FROM trusted_identity_aliases
        WHERE accountId = :accountId AND messageId IN (:messageIds) AND status = 'TRUSTED'
        ORDER BY messageId, kind, authority, value
        """,
    )
    abstract suspend fun trustedAliasesForMessages(
        accountId: String,
        messageIds: List<String>,
    ): List<TrustedIdentityAliasEntity>

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

    @Query(
        """
        SELECT * FROM identity_conflicts
        WHERE accountId = :accountId
          AND (firstMessageId IN (:messageIds) OR secondMessageId IN (:messageIds))
        """,
    )
    abstract suspend fun conflictsForMessages(
        accountId: String,
        messageIds: List<String>,
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
        WHERE accountId = :accountId AND messageId = :messageId
        """,
    )
    abstract suspend fun outboxForMessage(accountId: String, messageId: String): OutboxEntity?

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
          messages.replyFallbackBody, messages.replaceId
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
    val unreadCount: Int = 0,
    val senderJid: String = "",
    val direction: MessageDirection = MessageDirection.INBOUND,
) {
    val groupChat: Boolean
        get() = room
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
    val receiptStage: String? = null,
    val outboxGeneration: Long?,
    val outboxAttempt: Int?,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val replyToId: String? = null,
    val replyToJid: String? = null,
    val replyFallbackBody: String? = null,
    val markable: Boolean = false,
    val markerTargetId: String? = null,
    val correctedBody: String? = null,
    val edited: Boolean = false,
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
    val replaceId: String? = null,
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

enum class ReactionApplyOutcome {
    APPLIED,
    PENDING,
    IGNORED,
}

data class IncomingReactionApply(
    val accountId: String,
    val accountBareJid: String,
    val peerJid: String,
    val senderBareJid: String,
    val targetId: String,
    val emojis: List<String>,
    val receivedAtMs: Long,
    val delayedAtMs: Long? = null,
)

internal data class DirectReactionTarget(
    val accountId: String,
    val peerJid: String,
    val canonicalLocalId: String,
    val wireTargetId: String,
)

internal interface OutgoingReactionCommand {
    val accountId: String
    val peerJid: String
    val canonicalLocalMessageId: String
    val wireTargetId: String
    val emojis: List<String>
}

private class PreparedOutgoingReaction(
    val owner: MessageStore,
    override val accountId: String,
    override val peerJid: String,
    val ownSender: String,
    val target: DirectReactionTarget,
    val expectedRevision: Long,
    val encodedEmojis: String,
) : OutgoingReactionCommand {
    override val canonicalLocalMessageId: String get() = target.canonicalLocalId
    override val wireTargetId: String get() = target.wireTargetId
    override val emojis: List<String> get() = decodeReactionEmojis(encodedEmojis).toMutableList()
}

internal fun reactionTargetKey(localMessageId: String?, wireTargetId: String): String =
    localMessageId ?: "pending:$wireTargetId"

private fun MessageReactionEntity.toApply(accountBare: String) = IncomingReactionApply(
    accountId, accountBare, peerJid, senderBareJid, wireTargetId, decodeReactionEmojis(emojis), updatedAtMs,
)

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
    val markable: Boolean = false,
    val markerTargetId: String? = null,
    val replaceId: String? = null,
    val unreadEligible: Boolean = true,
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
        require(!markable || !markerTargetId.isNullOrEmpty()) { "Markable messages require a target ID" }
        require(replaceId == null || replaceId.isNotEmpty()) { "Correction target must not be empty" }
        require(replaceId == null || messageKind == MessageKind.CHAT) {
            "Only direct messages may correct stored content"
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
    val replaceId: String? = null,
    val correctionTargetMessageId: String? = null,
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
        require((replaceId == null) == (correctionTargetMessageId == null)) {
            "Correction target identity must be complete"
        }
        require(replaceId == null || messageKind == MessageKind.CHAT) {
            "Only direct messages may be corrected"
        }
        require(replaceId == null || (attachmentUrl == null && replyToId == null)) {
            "Corrections cannot carry attachments or replies"
        }
    }
}

data class IngestionResult(
    val messageId: String,
    val mergedRows: Int,
    val identityConflict: Boolean,
    val inserted: Boolean,
    val firstLiveDelivery: Boolean = false,
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
    private val threadIds: ThreadIdFactory,
) {
    constructor(database: NemaDatabase) : this(database, {}, System::currentTimeMillis, UuidThreadIdFactory)

    internal constructor(database: NemaDatabase, clock: () -> Long) :
        this(database, {}, clock, UuidThreadIdFactory)

    internal constructor(database: NemaDatabase, clock: () -> Long, threadIds: ThreadIdFactory) :
        this(database, {}, clock, threadIds)

    suspend fun ensureDirectThreadSession(accountId: String, peerJid: String): ThreadRef =
        database.withTransaction {
            val dao = database.messageDao()
            ensureScope(accountId, peerJid, MessageKind.CHAT, null, null, false)
            validDirectThreadSession(dao, accountId, peerJid)
                ?: createDirectThreadSession(dao, accountId, peerJid)
        }

    suspend fun markConversationRead(accountId: String, peerJid: String): Boolean = database.withTransaction {
        val dao = database.messageDao()
        dao.insertPeer(PeerEntity(accountId, peerJid))
        dao.updatePeerLastRead(accountId, peerJid) == 1
    }

    suspend fun compose(intent: OutboundIntent): OutboxEntity = database.withTransaction {
        composeInTransaction(intent)
    }

    suspend fun recordReceiptSignal(
        accountId: String,
        peerJid: String,
        senderJid: String,
        targetId: String,
        stage: MessageReceiptStage,
    ): OutboxEntity? = database.withTransaction {
        recordReceiptSignalInTransaction(
            dao = database.messageDao(),
            accountId = accountId,
            peerJid = peerJid,
            senderJid = senderJid,
            targetId = targetId,
            stage = stage,
        )
    }

    private suspend fun recordReceiptSignalInTransaction(
        dao: MessageDao,
        accountId: String,
        peerJid: String,
        senderJid: String,
        targetId: String,
        stage: MessageReceiptStage,
    ): OutboxEntity? {
        val self = dao.accountBareJid(accountId)
        if (self != null &&
            senderJid == self &&
            peerJid != self &&
            stage == MessageReceiptStage.DISPLAYED
        ) {
            val matches = dao.inboundChatByAliasValue(accountId, peerJid, targetId)
                .distinctBy(MessageEntity::localMessageId)
            val target = matches.singleOrNull() ?: return null
            dao.advancePeerLastRead(accountId, peerJid, target.localSequence)
            return null
        }
        if (senderJid != peerJid) return null
        val outbox = dao.outbox(accountId, targetId)
            ?: dao.outboxByOrigin(accountId, targetId)
            ?: attachReceiptOutbox(dao, accountId, peerJid, targetId)
            ?: return null
        val message = dao.message(accountId, outbox.messageId) ?: return null
        if (message.direction != MessageDirection.OUTBOUND ||
            message.messageKind != MessageKind.CHAT ||
            message.peerJid != peerJid
        ) {
            return null
        }
        if ((outbox.receiptStage?.ordinal ?: -1) >= stage.ordinal) return outbox
        return outbox.copy(receiptStage = stage).also { dao.updateOutbox(it) }
    }

    private suspend fun attachReceiptOutbox(
        dao: MessageDao,
        accountId: String,
        peerJid: String,
        targetId: String,
    ): OutboxEntity? {
        val matches = dao.outboundChatByAliasValue(accountId, peerJid, targetId)
            .distinctBy(MessageEntity::localMessageId)
        val message = matches.singleOrNull() ?: return null
        dao.outboxForMessage(accountId, message.localMessageId)?.let { return it }
        if (dao.outbox(accountId, targetId) != null || dao.outboxByOrigin(accountId, targetId) != null) {
            return null
        }
        dao.insertOutbox(
            OutboxEntity(
                accountId = accountId,
                operationId = targetId,
                messageId = message.localMessageId,
                originId = targetId,
                status = OutboxStatus.CONFIRMED,
                generation = null,
                attempt = 1,
                failureReason = null,
            ),
        )
        return dao.outbox(accountId, targetId)
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
        replaceId: String? = null,
        correctionTargetMessageId: String? = null,
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
        val effectiveThread = if (messageKind == MessageKind.CHAT) {
            val existingThread = dao.outbox(accountId, operationId)
                ?.let { dao.message(accountId, it.messageId) }
                ?.threadRef()
            when {
                existingThread == null -> prepareDirectOutgoingThread(
                    dao = dao,
                    accountId = accountId,
                    peerJid = peerJid,
                    requested = thread,
                )
                thread == null || thread == existingThread -> existingThread
                else -> throw IllegalArgumentException("Operation ID identifies different thread lineage")
            }
        } else {
            thread
        }
        val intent = OutboundIntent(
            accountId = accountId,
            operationId = operationId,
            localMessageId = localMessageId,
            originId = originId,
            peerJid = peerJid,
            senderJid = senderJid,
            messageKind = messageKind,
            threadId = effectiveThread?.id?.value,
            parentThreadId = effectiveThread?.parentId?.value,
            body = body,
            attachmentUrl = attachmentUrl,
            attachmentName = attachmentName,
            attachmentMime = attachmentMime,
            attachmentSize = attachmentSize,
            replyToId = replyToId,
            replyToJid = replyToJid,
            replyFallbackBody = replyFallbackBody,
            replaceId = replaceId,
            correctionTargetMessageId = correctionTargetMessageId,
        )
        existingOutboxForIntent(dao, intent)?.let { return@withTransaction it }
        if (replaceId == null) {
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
        }
        val outbox = composeInTransaction(intent)
        if (replaceId == null) {
            check(dao.deleteDraft(accountId, peerJid, threadKey) == 1) {
                "Direct draft changed during send transaction"
            }
        }
        outbox
    }

    private suspend fun composeInTransaction(intent: OutboundIntent): OutboxEntity {
        val dao = database.messageDao()
        existingOutboxForIntent(dao, intent)?.let { return it }
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
            replaceId = intent.replaceId,
            correctionTargetMessageId = intent.correctionTargetMessageId,
        )
        intent.replaceId?.let { referenceId ->
            val target = requireNotNull(dao.message(intent.accountId, requireNotNull(intent.correctionTargetMessageId))) {
                "Unknown correction target"
            }
            val targetOutbox = requireNotNull(dao.outbox(intent.accountId, referenceId)) {
                "Correction target has no exact outbound identity"
            }
            require(targetOutbox.messageId == target.localMessageId &&
                targetOutbox.status in setOf(OutboxStatus.ACKNOWLEDGED, OutboxStatus.CONFIRMED) &&
                message.canCorrect(target) && message.body != target.body
            ) { "Correction target is not eligible" }
        }
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

    private suspend fun existingOutboxForIntent(dao: MessageDao, intent: OutboundIntent): OutboxEntity? =
        dao.outbox(intent.accountId, intent.operationId)?.also { existing ->
            val message = requireNotNull(dao.message(intent.accountId, existing.messageId))
            require(existing.originId == intent.originId && message.matches(intent)) {
                "Operation ID identifies different outbound intent"
            }
        }

    suspend fun ingest(incoming: IncomingMessage): IngestionResult = database.withTransaction {
        val position = incoming.archivePosition()
        val result = ingestInTransaction(
            incoming.withoutArchivePosition(),
            allowLiveReconciliation = position == null,
        )
        if (position != null) attachArchivePosition(result.messageId, incoming.accountId, position)
        result
    }

    suspend fun applyIncomingReaction(reaction: IncomingReactionApply): ReactionApplyOutcome =
        database.withTransaction { applyIncomingReactionInTransaction(reaction) }

    suspend fun reactionWireTarget(accountId: String, peerJid: String, localMessageId: String): String? =
        database.withTransaction {
            val dao = database.messageDao()
            val selected = dao.message(accountId, localMessageId)
                ?.takeIf { it.peerJid == peerJid && it.messageKind == MessageKind.CHAT }
                ?: return@withTransaction null
            val message = dao.directReactionTarget(selected) ?: return@withTransaction null
            dao.trustedAliasesForMessage(message.accountId, message.localMessageId)
                .filter { it.kind == IdentityAliasKind.MESSAGE_ID }
                .singleOrNull()
                ?.value
        }

    internal suspend fun resolveDirectReactionTarget(
        accountId: String,
        peerJid: String,
        localMessageId: String,
    ): DirectReactionTarget? = database.withTransaction {
        val dao = database.messageDao()
        val selected = dao.message(accountId, localMessageId)
            ?.takeIf { it.peerJid == peerJid && it.messageKind == MessageKind.CHAT }
            ?: return@withTransaction null
        if ((selected.replaceId == null) != (selected.correctionTargetMessageId == null)) {
            return@withTransaction null
        }
        val canonical = if (selected.replaceId == null) selected else {
            val target = dao.message(accountId, selected.correctionTargetMessageId!!) ?: return@withTransaction null
            if (!selected.canCorrect(target)) return@withTransaction null
            val link = dao.trustedAlias(
                accountId, IdentityAliasKind.MESSAGE_ID, selected.senderJid, selected.replaceId!!,
            )
            if (link?.messageId != target.localMessageId) return@withTransaction null
            target
        }
        if (canonical.replaceId != null || canonical.correctionTargetMessageId != null) return@withTransaction null
        val alias = dao.trustedAliasesForMessage(accountId, canonical.localMessageId)
            .filter { it.kind == IdentityAliasKind.MESSAGE_ID }
            .singleOrNull()
            ?.takeIf { it.authority == canonical.senderJid && it.value.isNotBlank() }
            ?: return@withTransaction null
        DirectReactionTarget(accountId, peerJid, canonical.localMessageId, alias.value)
    }

    internal suspend fun prepareOutgoingReaction(
        accountId: String,
        peerJid: String,
        localMessageId: String,
        ownSenderBareJid: String,
        emoji: String,
    ): OutgoingReactionCommand? = database.withTransaction {
        val dao = database.messageDao()
        if (emoji.trim().isEmpty() || dao.accountBareJid(accountId) != ownSenderBareJid) return@withTransaction null
        val target = resolveDirectReactionTarget(accountId, peerJid, localMessageId) ?: return@withTransaction null
        val current = dao.validatedReaction(accountId, peerJid, ownSenderBareJid, target.canonicalLocalId)
        if (current?.revision == Long.MAX_VALUE - 1) return@withTransaction null
        val encoded = encodeReactionEmojis(toggleReaction(emoji, current?.let { decodeReactionEmojis(it.emojis) }.orEmpty()))
        PreparedOutgoingReaction(this, accountId, peerJid, ownSenderBareJid, target, current?.revision ?: 0, encoded)
    }

    internal suspend fun commitOutgoingReaction(command: OutgoingReactionCommand): Boolean =
        database.withTransaction {
            val prepared = command as? PreparedOutgoingReaction
                ?: return@withTransaction false
            if (prepared.owner !== this) return@withTransaction false
            val dao = database.messageDao()
            if (dao.accountBareJid(prepared.accountId) != prepared.ownSender) return@withTransaction false
            val target = resolveDirectReactionTarget(
                prepared.target.accountId,
                prepared.target.peerJid,
                prepared.target.canonicalLocalId,
            )
            if (target != prepared.target) return@withTransaction false
            dao.writeReactionFullSetIfRevision(
                prepared.target.accountId,
                prepared.target.peerJid,
                prepared.ownSender,
                prepared.target.canonicalLocalId,
                prepared.expectedRevision,
            ) {
                MessageReactionEntity(
                    accountId = prepared.target.accountId,
                    peerJid = prepared.target.peerJid,
                    senderBareJid = prepared.ownSender,
                    targetKey = prepared.target.canonicalLocalId,
                    localMessageId = prepared.target.canonicalLocalId,
                    wireTargetId = prepared.target.wireTargetId,
                    emojis = prepared.encodedEmojis,
                    updatedAtMs = clock(),
                )
            }
        }

    suspend fun ownReactionEmojis(
        accountId: String,
        peerJid: String,
        localMessageId: String,
        senderBareJid: String,
    ): List<String> = database.withTransaction {
        val dao = database.messageDao()
        val selected = dao.message(accountId, localMessageId)
            ?.takeIf { it.peerJid == peerJid && it.messageKind == MessageKind.CHAT }
            ?: return@withTransaction emptyList()
        val target = dao.directReactionTarget(selected) ?: return@withTransaction emptyList()
        if (selected.localMessageId != target.localMessageId) reparentReactions(selected, target)
        dao.messageReactionsForMessage(accountId, peerJid, target.localMessageId)
            .firstOrNull { it.senderBareJid == senderBareJid }
            ?.let { decodeReactionEmojis(it.emojis) }
            .orEmpty()
    }

    suspend fun reactionDisplays(
        accountId: String,
        peerJid: String,
        chosenSender: String?,
    ): List<org.thanosapollo.nema.xmpp.reactions.ReactionDisplay> {
        val rows = database.messageDao().messageReactions(accountId, peerJid)
            .mapNotNull { row ->
                row.localMessageId?.takeIf { row.emojis.isNotEmpty() }?.let { it to row }
            }
        return rows.groupBy({ it.first }, { it.second }).flatMap { (localId, group) ->
            reactionDisplaysFor(
                localMessageId = localId,
                senderState = group.map { it.senderBareJid to decodeReactionEmojis(it.emojis) },
                chosenSender = chosenSender,
            )
        }
    }

    private suspend fun ingestInTransaction(
        received: IncomingMessage,
        preserveStoredThreadLineage: Boolean = false,
        allowDirectSessionTransition: Boolean = true,
        advanceOutboundLastRead: Boolean = true,
        allowLiveReconciliation: Boolean = true,
    ): IngestionResult {
        val dao = database.messageDao()
        val timed = received.withStoredTime(clock)
        val inheritedParent = timed.threadId
            ?.takeIf { timed.parentThreadId == null }
            ?.let { dao.thread(timed.accountId, timed.peerJid, timed.messageKind, it)?.parentThreadId }
        val incoming = timed.copy(
            parentThreadId = ensureScope(
                accountId = timed.accountId,
                peerJid = timed.peerJid,
                messageKind = timed.messageKind,
                threadId = timed.threadId,
                parentThreadId = timed.parentThreadId ?: inheritedParent,
                preserveStoredThreadLineage = preserveStoredThreadLineage,
            ),
        )
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
        val inserted = matched == null
        var winner: MessageEntity
        if (matched == null) {
            val observation = liveReconciliationObservation(incoming, allowLiveReconciliation)
            val created = incoming.toEntity(allocateSequence(incoming.accountId), observation)
            dao.insertMessage(created)
            if (observation != null) {
                check(
                    database.accountDao().advanceReconciliationWallFloor(
                        incoming.accountId,
                        observation,
                    ) == 1,
                ) { "Reconciliation state changed during live insert" }
            }
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

        winner = reconcileCorrections(dao, incoming, winner)
        val liveDelivery = incoming.sentTimeSource != MessageTimeSource.CARBON &&
            incoming.sentTimeSource != MessageTimeSource.MAM
        val firstLiveDelivery = liveDelivery && !winner.liveDeliveryObserved
        val reconciled = winner
            .withPreferredTime(incoming.sentAtEpochMs, incoming.sentTimeSource)
            .withAttachmentMetadata(
                incoming.attachmentName,
                incoming.attachmentMime,
                incoming.attachmentSize,
            )
            .withMarkerMetadata(incoming)
            .withLiveDeliveryObserved(liveDelivery)
            .withUnreadEligible(incoming.unreadEligible)
        if (reconciled != winner) {
            dao.updateMessage(reconciled)
            winner = reconciled
        }

        confirmMatchingOutbox(incoming, winner.localMessageId)
        writeBoundary(MessageWriteBoundary.AFTER_OUTBOX)
        if (allowDirectSessionTransition &&
            incoming.messageKind == MessageKind.CHAT &&
            incoming.sentTimeSource != MessageTimeSource.MAM &&
            !winner.directSessionTransitionApplied
        ) {
            reconcileDirectThreadSession(dao, incoming)
            winner = winner.copy(directSessionTransitionApplied = true)
            dao.updateMessage(winner)
        }
        if (advanceOutboundLastRead &&
            winner.direction == MessageDirection.OUTBOUND &&
            winner.messageKind == MessageKind.CHAT
        ) {
            dao.advancePeerLastRead(winner.accountId, winner.peerJid, winner.localSequence)
        }
        attachPendingReactions(dao, winner)
        return IngestionResult(
            messageId = winner.localMessageId,
            mergedRows = mergedRows,
            identityConflict = identityConflict,
            inserted = inserted,
            firstLiveDelivery = firstLiveDelivery,
        )
    }

    private suspend fun applyIncomingReactionInTransaction(
        reaction: IncomingReactionApply,
    ): ReactionApplyOutcome {
        val dao = database.messageDao()
        if (reaction.senderBareJid !in setOf(reaction.accountBareJid, reaction.peerJid)) {
            return ReactionApplyOutcome.IGNORED
        }
        val matches = dao.chatByMessageId(reaction.accountId, reaction.peerJid, reaction.targetId)
            .distinctBy(MessageEntity::localMessageId)
        return when (matches.size) {
            0 -> if (dao.hasKnownAliasForReactionTarget(reaction)) {
                ReactionApplyOutcome.IGNORED
            } else {
                writeReactionRow(dao, reaction, localMessageId = null)
            }
            1 -> {
                val match = matches.single()
                dao.directReactionTarget(match)?.let { target ->
                    writeReactionRow(dao, reaction, localMessageId = target.localMessageId)
                } ?: if (match.correctionTargetMessageId != null || match.replaceId?.let { replaceId ->
                        dao.trustedAlias(match.accountId, IdentityAliasKind.MESSAGE_ID, match.senderJid, replaceId)
                    } != null
                ) ReactionApplyOutcome.PENDING else writeReactionRow(dao, reaction, localMessageId = null)
            }
            else -> ReactionApplyOutcome.IGNORED
        }
    }

    private suspend fun MessageDao.hasKnownAliasForReactionTarget(reaction: IncomingReactionApply): Boolean =
        inboundChatByAliasValue(reaction.accountId, reaction.peerJid, reaction.targetId).isNotEmpty() ||
            outboundChatByAliasValue(reaction.accountId, reaction.peerJid, reaction.targetId).isNotEmpty()

    private suspend fun writeReactionRow(
        dao: MessageDao,
        reaction: IncomingReactionApply,
        localMessageId: String?,
        keepNewest: Boolean = false,
    ): ReactionApplyOutcome {
        val eventTime = reaction.delayedAtMs ?: reaction.receivedAtMs
        val key = reactionTargetKey(localMessageId, reaction.targetId)
        val outcome = dao.writeReactionFullSet(
            MessageReactionEntity(
                accountId = reaction.accountId,
                peerJid = reaction.peerJid,
                senderBareJid = reaction.senderBareJid,
                targetKey = key,
                localMessageId = localMessageId,
                wireTargetId = reaction.targetId,
                emojis = encodeReactionEmojis(reaction.emojis),
                updatedAtMs = eventTime,
            ),
            keepNewest || reaction.delayedAtMs != null,
        )
        if (outcome == ReactionMutationOutcome.SUPERSEDED) return ReactionApplyOutcome.IGNORED
        return if (localMessageId == null) ReactionApplyOutcome.PENDING else ReactionApplyOutcome.APPLIED
    }

    private suspend fun attachPendingReactions(dao: MessageDao, winner: MessageEntity) {
        if (winner.messageKind != MessageKind.CHAT) return
        val pending = dao.classifyPendingReactions(
            winner.accountId, winner.peerJid, winner.localMessageId,
        ) ?: return
        val target = dao.directReactionTarget(winner) ?: return
        pending.accepted.forEach { row ->
            dao.moveMessageReaction(
                row,
                row.copy(
                    targetKey = target.localMessageId,
                    localMessageId = target.localMessageId,
                ),
            )
        }
        pending.unsupported.forEach { dao.retireUnsupportedPendingReaction(it) }
    }

    private suspend fun reparentReactions(loser: MessageEntity, winner: MessageEntity) {
        val dao = database.messageDao()
        dao.messageReactionsForMessage(loser.accountId, loser.peerJid, loser.localMessageId)
            .forEach { row ->
            dao.moveMessageReaction(
                row,
                row.copy(
                    targetKey = winner.localMessageId,
                    localMessageId = winner.localMessageId,
                ),
            )
        }
    }

    private suspend fun MessageDao.directReactionTarget(message: MessageEntity): MessageEntity? {
        if (message.replaceId == null) return message.takeIf { it.correctionTargetMessageId == null }
        val targetId = message.correctionTargetMessageId ?: return null
        val target = message(message.accountId, targetId) ?: return null
        if (!message.canCorrect(target)) return null
        val alias = trustedAlias(
            message.accountId,
            IdentityAliasKind.MESSAGE_ID,
            message.senderJid,
            message.replaceId,
        )
        return target.takeIf { alias?.messageId == target.localMessageId }
    }

    private suspend fun reconcileCorrections(
        dao: MessageDao,
        incoming: IncomingMessage,
        stored: MessageEntity,
    ): MessageEntity {
        var winner = stored
        val replaceId = winner.replaceId
        if (replaceId != null && winner.correctionTargetMessageId == null) {
            val target = dao.trustedAlias(
                winner.accountId,
                IdentityAliasKind.MESSAGE_ID,
                winner.senderJid,
                replaceId,
            )?.messageId?.let { dao.message(winner.accountId, it) }
            if (target != null && winner.canCorrect(target)) {
                winner = winner.copy(correctionTargetMessageId = target.localMessageId)
                dao.updateMessage(winner)
            }
        }
        dao.directReactionTarget(winner)?.takeIf { it.localMessageId != winner.localMessageId }?.let { original ->
            reparentReactions(winner, original)
            attachPendingReactions(dao, winner)
        }
        if (winner.replaceId == null) {
            val resolved = mutableSetOf<String>()
            for (alias in incoming.aliases) {
                if (alias.kind != IdentityAliasKind.MESSAGE_ID || alias.authority != winner.senderJid) continue
                for (correction in dao.unresolvedCorrections(
                    winner.accountId,
                    winner.peerJid,
                    winner.senderJid,
                    winner.direction,
                    alias.value,
                )) {
                    if (resolved.add(correction.localMessageId) && correction.canCorrect(winner)) {
                        val linked = correction.copy(correctionTargetMessageId = winner.localMessageId)
                        dao.updateMessage(linked)
                        reparentReactions(correction, winner)
                        attachPendingReactions(dao, linked)
                    }
                }
            }
        }
        return winner
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
        if (components.any { it.size > 1 }) {
            return@withTransaction retryable("Archive page repeats one logical message")
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

        val ingestedContent = mutableListOf<Pair<ArchivedIncomingMessage, IngestionResult>>()
        page.messages.forEachIndexed { index, archived ->
            archived.signal?.let { signal ->
                recordReceiptSignalInTransaction(
                    dao = dao,
                    accountId = page.key.accountId,
                    peerJid = signal.peerJid,
                    senderJid = signal.senderJid,
                    targetId = signal.targetId,
                    stage = signal.stage,
                )
                return@forEachIndexed
            }
            val message = archived.message ?: return@forEachIndexed
            val archiveAlias = TrustedIdentityAlias(
                kind = IdentityAliasKind.MAM_RESULT,
                authority = aliasAuthority,
                value = archived.resultId,
            )
            val result = ingestInTransaction(
                message.withoutArchivePosition().copy(
                    aliases = (message.aliases + archiveAlias).distinct(),
                    unreadEligible = page.direction == ArchiveDirection.AFTER &&
                        archived.resultId != page.boundaryId,
                ),
                preserveStoredThreadLineage = true,
                allowDirectSessionTransition = false,
                advanceOutboundLastRead = page.direction != ArchiveDirection.BEFORE,
                allowLiveReconciliation = false,
            )
            attachArchivePosition(
                result.messageId,
                page.key.accountId,
                ArchivePosition(
                    authority = page.key.archiveAuthority,
                    scope = page.key.scope,
                    ordinal = Math.addExact(startOrdinal, index.toLong()),
                ),
            )
            ingestedContent += archived to result
        }

        val accountDao = database.accountDao()
        val floor = if (accountDao.advanceReconciliationWallFloor(
                page.key.accountId,
                maxOf(clock(), 0),
            ) == 1
        ) {
            accountDao.reconciliationState(page.key.accountId)?.wallFloorMs
        } else {
            null
        }
        val closure = IdentitylessArchiveClosure(
            key = page.key,
            observedThroughMs = page.messages.mapNotNull { archived ->
                archived.message?.takeIf { it.sentTimeSource == MessageTimeSource.MAM }
                    ?.sentAtEpochMs?.takeIf { it >= 0 }
            }.maxOrNull(),
            complete = page.complete,
        )
        val seedIds = ingestedContent.mapTo(mutableSetOf()) { it.second.messageId }
        page.boundaryId?.let { boundaryId ->
            dao.trustedAlias(
                page.key.accountId,
                IdentityAliasKind.MAM_RESULT,
                aliasAuthority,
                boundaryId,
            )?.messageId?.let(seedIds::add)
        }
        if (seedIds.size <= IDENTITYLESS_RECONCILIATION_CANDIDATE_CAP) {
            seedIds.forEach { reconcileIdentitylessArchiveSeed(it, closure, floor) }
        }

        var inserted = 0
        val insertedInbound = mutableListOf<InsertedInbound>()
        ingestedContent.forEach { (archived, result) ->
            val message = requireNotNull(archived.message)
            if (result.inserted && !result.identityConflict &&
                dao.message(page.key.accountId, result.messageId) != null
            ) {
                inserted++
                insertedInbound += InsertedInbound(
                    peerJid = message.peerJid,
                    preview = message.body,
                    inbound = message.direction == MessageDirection.INBOUND,
                    groupChat = message.messageKind == MessageKind.GROUPCHAT,
                )
            }
        }
        val ingested = ingestedContent.size

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
        ArchivePageResult(ArchivePageStatus.APPLIED, next, ingested, inserted, insertedInbound)
    }

    private suspend fun reconcileIdentitylessArchiveSeed(
        seedMessageId: String,
        closure: IdentitylessArchiveClosure,
        wallFloorMs: Long?,
    ) {
        if (wallFloorMs == null) return
        val dao = database.messageDao()
        val seed = dao.message(closure.key.accountId, seedMessageId) ?: return
        val seedTime = seed.sentAtEpochMs?.takeIf { it >= 0 } ?: return
        val radius = IDENTITYLESS_RECONCILIATION_WINDOW_MS * 2
        val lower = maxOf(seedTime - radius, 0)
        val upper = if (seedTime > Long.MAX_VALUE - radius) Long.MAX_VALUE else seedTime + radius
        val messages = dao.identitylessReconciliationCandidates(
            closure.key.accountId,
            seed.peerJid,
            lower,
            upper,
            IDENTITYLESS_RECONCILIATION_CANDIDATE_CAP + 1,
        )
        if (messages.isEmpty() || messages.size > IDENTITYLESS_RECONCILIATION_CANDIDATE_CAP) return
        val messageIds = messages.map(MessageEntity::localMessageId)
        val aliases = dao.trustedAliasesForMessages(closure.key.accountId, messageIds)
            .groupBy(TrustedIdentityAliasEntity::messageId)
        val positions = dao.archivePositionsForMessages(closure.key.accountId, messageIds)
            .groupBy(ArchiveMessagePositionEntity::messageId)
        val outboxIds = dao.outboxesForMessages(closure.key.accountId, messageIds)
            .mapTo(mutableSetOf(), OutboxEntity::messageId)
        val conflictIds = dao.conflictsForMessages(closure.key.accountId, messageIds)
            .flatMapTo(mutableSetOf()) { listOf(it.firstMessageId, it.secondMessageId) }
        val candidates = messages.map { message ->
            IdentitylessReconciliationCandidate(
                message = message,
                aliases = aliases[message.localMessageId].orEmpty(),
                positions = positions[message.localMessageId].orEmpty(),
                hasOutbox = message.localMessageId in outboxIds,
                hasConflict = message.localMessageId in conflictIds,
            )
        }
        val pair = closedIdentitylessPair(
            seedMessageId,
            candidates,
            closure,
            wallFloorMs,
        ) ?: return
        val live = dao.message(closure.key.accountId, pair.liveMessageId) ?: return
        val mam = dao.message(closure.key.accountId, pair.mamMessageId) ?: return
        mergePair(live, mam)
    }

    suspend fun repairIdentitylessDuplicates(accountId: String): AccountReconciliationStateEntity? =
        database.withTransaction {
            val accountDao = database.accountDao()
            val state = accountDao.reconciliationState(accountId) ?: return@withTransaction null
            if (state.status == ReconciliationRepairStatus.COMPLETE) return@withTransaction state
            val dao = database.messageDao()
            val messages = dao.messages(accountId)
            val aliases = dao.trustedAliases(accountId)
                .filter { it.status == IdentityAliasStatus.TRUSTED && it.messageId != null }
                .groupBy(TrustedIdentityAliasEntity::messageId)
            val positions = dao.archivePositions(accountId).groupBy(ArchiveMessagePositionEntity::messageId)
            val outboxIds = dao.outboxes(accountId).mapTo(mutableSetOf(), OutboxEntity::messageId)
            val conflictIds = dao.conflicts(accountId)
                .flatMapTo(mutableSetOf()) { listOf(it.firstMessageId, it.secondMessageId) }
            val candidates = messages.map { message ->
                IdentitylessReconciliationCandidate(
                    message,
                    aliases[message.localMessageId].orEmpty(),
                    positions[message.localMessageId].orEmpty(),
                    message.localMessageId in outboxIds,
                    message.localMessageId in conflictIds,
                )
            }
            val plan = requireNotNull(identitylessRepairPlan(candidates)) {
                "Identityless repair candidate IDs are not unique"
            }
            plan.pairs.forEach { pair ->
                val live = requireNotNull(dao.message(accountId, pair.liveMessageId))
                val mam = requireNotNull(dao.message(accountId, pair.mamMessageId))
                mergePair(live, mam)
            }
            val afterCount = dao.messages(accountId).size.toLong()
            check(
                accountDao.completeReconciliationState(
                    accountId,
                    messages.size.toLong(),
                    afterCount,
                    plan.pairs.size.toLong(),
                    plan.skippedComponents.toLong(),
                ) == 1,
            ) { "Identityless repair state changed during repair" }
            requireNotNull(accountDao.reconciliationState(accountId))
        }

    suspend fun attemptIdentitylessRepair(accountId: String): Boolean = try {
        repairIdentitylessDuplicates(accountId)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        database.withTransaction {
            database.accountDao().recordCaughtReconciliationError(accountId)
        }
        false
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

    private suspend fun liveReconciliationObservation(
        incoming: IncomingMessage,
        allowed: Boolean,
    ): Long? {
        val receivedAt = incoming.sentAtEpochMs
        if (!allowed ||
            incoming.direction != MessageDirection.INBOUND ||
            incoming.sentTimeSource != MessageTimeSource.LOCAL ||
            incoming.aliases.isNotEmpty() ||
            receivedAt == null || receivedAt < 0
        ) return null
        val state = database.accountDao().reconciliationState(incoming.accountId) ?: return null
        return maxOf(receivedAt, state.wallFloorMs)
    }

    private suspend fun canMerge(
        first: MessageEntity,
        second: MessageEntity,
        incoming: IncomingMessage,
    ): Boolean {
        if (!first.isCompatibleWith(incoming) || !second.isCompatibleWith(incoming)) return false
        if (first.attachmentUrl != second.attachmentUrl ||
            !optionalMetadataMatches(first.attachmentName, second.attachmentName) ||
            !optionalMetadataMatches(first.attachmentMime, second.attachmentMime) ||
            !optionalMetadataMatches(first.attachmentSize, second.attachmentSize)
        ) return false
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
        dao.reparentCorrections(winner.accountId, loser.localMessageId, winner.localMessageId)
        reparentReactions(loser, winner)
        writeBoundary(MessageWriteBoundary.AFTER_DEPENDENT_REPARENT)
        dao.deleteMessage(loser)
        val withArchive = if (winner.archiveOrdinal == null && loser.archiveOrdinal != null) {
            winner.copy(archiveOrdinal = loser.archiveOrdinal)
        } else winner
        val withTransition = if (loser.directSessionTransitionApplied) {
            withArchive.copy(directSessionTransitionApplied = true)
        } else {
            withArchive
        }
        val reconciled = withTransition
            .withLiveDeliveryObserved(loser.liveDeliveryObserved)
            .withUnreadEligible(loser.unreadEligible)
            .withPreferredTime(loser.sentAtEpochMs, loser.sentTimeSource)
            .withAttachmentMetadata(loser.attachmentName, loser.attachmentMime, loser.attachmentSize)
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

    private suspend fun prepareDirectOutgoingThread(
        dao: MessageDao,
        accountId: String,
        peerJid: String,
        requested: ThreadRef?,
    ): ThreadRef {
        ensureScope(accountId, peerJid, MessageKind.CHAT, null, null, false)
        val current = validDirectThreadSession(dao, accountId, peerJid)
        if (requested == null) return current ?: createDirectThreadSession(dao, accountId, peerJid)

        val existing = dao.thread(accountId, peerJid, MessageKind.CHAT, requested.id.value)
        if (requested.parentId != null) {
            if (existing != null) {
                require(existing.parentThreadId == requested.parentId.value) { "Thread lineage conflict" }
            } else {
                val parent = dao.thread(accountId, peerJid, MessageKind.CHAT, requested.parentId.value)
                require(parent != null) { "New child thread requires known parent" }
            }
            return requested
        }
        if (existing != null) {
            require(existing.parentThreadId == null) { "Child thread requires exact parent lineage" }
            commitDirectThreadSession(dao, accountId, peerJid, requested.id.value)
            return requested
        }
        val parent = current ?: createDirectThreadSession(dao, accountId, peerJid)
        return ThreadRef(requested.id, parent.id)
    }

    private suspend fun reconcileDirectThreadSession(dao: MessageDao, incoming: IncomingMessage) {
        if (incoming.messageKind != MessageKind.CHAT) return
        validDirectThreadSession(dao, incoming.accountId, incoming.peerJid)
        if (incoming.sentTimeSource == MessageTimeSource.MAM) return
        when {
            incoming.threadId == null -> createDirectThreadSession(dao, incoming.accountId, incoming.peerJid)
            incoming.parentThreadId == null -> {
                val thread = dao.thread(
                    incoming.accountId,
                    incoming.peerJid,
                    MessageKind.CHAT,
                    incoming.threadId,
                )
                if (thread?.parentThreadId == null) {
                    commitDirectThreadSession(dao, incoming.accountId, incoming.peerJid, incoming.threadId)
                }
            }
        }
    }

    private suspend fun validDirectThreadSession(
        dao: MessageDao,
        accountId: String,
        peerJid: String,
    ): ThreadRef? {
        val session = dao.directThreadSession(accountId, peerJid) ?: return null
        val thread = dao.thread(accountId, peerJid, MessageKind.CHAT, session.threadId)
        if (session.messageKind == MessageKind.CHAT && thread?.parentThreadId == null) {
            return ThreadRef(ThreadId.require(session.threadId))
        }
        dao.deleteDirectThreadSession(accountId, peerJid)
        return null
    }

    private suspend fun createDirectThreadSession(
        dao: MessageDao,
        accountId: String,
        peerJid: String,
    ): ThreadRef {
        val id = threadIds.create()
        require(dao.thread(accountId, peerJid, MessageKind.CHAT, id.value) == null) {
            "Generated direct session ID already exists"
        }
        ensureScope(accountId, peerJid, MessageKind.CHAT, id.value, null, false)
        commitDirectThreadSession(dao, accountId, peerJid, id.value)
        return ThreadRef(id)
    }

    private suspend fun commitDirectThreadSession(
        dao: MessageDao,
        accountId: String,
        peerJid: String,
        threadId: String,
    ) {
        val thread = requireNotNull(dao.thread(accountId, peerJid, MessageKind.CHAT, threadId))
        require(thread.parentThreadId == null) { "Direct session must be top-level" }
        dao.saveDirectThreadSession(DirectThreadSessionEntity(accountId, peerJid, threadId = threadId))
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
            val currentDirectSession = messageKind == MessageKind.CHAT &&
                dao.directThreadSession(accountId, peerJid)?.threadId == threadId
            if (preserveStoredThreadLineage &&
                (currentDirectSession || dao.threadHasMessages(accountId, peerJid, messageKind, threadId))
            ) {
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
            threadIds: ThreadIdFactory = UuidThreadIdFactory,
            observer: (MessageWriteBoundary) -> Unit,
        ): MessageStore = MessageStore(database, observer, System::currentTimeMillis, threadIds)
    }
}

private fun MessageEntity.threadRef(): ThreadRef? = threadId?.let {
    ThreadRef(
        id = ThreadId.require(it),
        parentId = parentThreadId?.let(ThreadId::require),
    )
}

private fun IncomingMessage.toEntity(
    localSequence: Long,
    reconciliationObservedAtMs: Long?,
) = MessageEntity(
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
    reconciliationObservedAtMs = reconciliationObservedAtMs,
    attachmentUrl = attachmentUrl,
    attachmentName = attachmentName,
    attachmentMime = attachmentMime,
    attachmentSize = attachmentSize,
    replyToId = replyToId,
    replyToJid = replyToJid,
    replyFallbackBody = replyFallbackBody,
    markable = markable,
    markerTargetId = markerTargetId,
    replaceId = replaceId,
    unreadEligible = unreadEligible,
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
        replyFallbackBody == intent.replyFallbackBody &&
        replaceId == intent.replaceId &&
        correctionTargetMessageId == intent.correctionTargetMessageId

private fun <T> optionalMetadataMatches(first: T?, second: T?): Boolean =
    first == null || second == null || first == second

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
        optionalMetadataMatches(attachmentName, incoming.attachmentName) &&
        optionalMetadataMatches(attachmentMime, incoming.attachmentMime) &&
        optionalMetadataMatches(attachmentSize, incoming.attachmentSize) &&
        replyToId == incoming.replyToId &&
        replyToJid == incoming.replyToJid &&
        replaceId == incoming.replaceId

private fun MessageEntity.canCorrect(target: MessageEntity): Boolean =
    replaceId != null &&
        localMessageId != target.localMessageId &&
        accountId == target.accountId &&
        peerJid == target.peerJid &&
        senderJid == target.senderJid &&
        direction == target.direction &&
        isPlainDirectText() &&
        target.isPlainDirectText() &&
        target.replaceId == null &&
        target.correctionTargetMessageId == null &&
        threadId == target.threadId &&
        parentThreadId == target.parentThreadId

private fun MessageEntity.isPlainDirectText(): Boolean =
    messageKind == MessageKind.CHAT &&
        body.isNotBlank() &&
        attachmentUrl == null &&
        attachmentName == null &&
        attachmentMime == null &&
        attachmentSize == null &&
        replyToId == null &&
        replyToJid == null &&
        replyFallbackBody == null

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

private fun MessageEntity.withAttachmentMetadata(
    name: String?,
    mime: String?,
    size: Long?,
): MessageEntity = copy(
    attachmentName = attachmentName ?: name,
    attachmentMime = attachmentMime ?: mime,
    attachmentSize = attachmentSize ?: size,
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

private fun MessageEntity.withLiveDeliveryObserved(observed: Boolean): MessageEntity =
    if (observed && !liveDeliveryObserved) copy(liveDeliveryObserved = true) else this

private fun MessageEntity.withUnreadEligible(eligible: Boolean): MessageEntity =
    if (eligible && !unreadEligible) copy(unreadEligible = true) else this

private fun MessageEntity.withMarkerMetadata(incoming: IncomingMessage): MessageEntity =
    if (!markable && incoming.markable) {
        copy(markable = true, markerTargetId = incoming.markerTargetId)
    } else {
        this
    }

private val MessageTimeSource.authorityRank: Int
    get() = when (this) {
        MessageTimeSource.LOCAL -> 0
        MessageTimeSource.DELAYED -> 1
        MessageTimeSource.CARBON -> 2
        MessageTimeSource.MAM -> 3
    }
