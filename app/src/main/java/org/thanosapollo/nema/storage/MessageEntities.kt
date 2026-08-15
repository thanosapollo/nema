package org.thanosapollo.nema.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.TypeConverter
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@Entity(
    tableName = "peers",
    primaryKeys = ["accountId", "jid"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PeerEntity(
    val accountId: String,
    val jid: String,
    val displayName: String? = null,
    val localNickname: String? = null,
    val photoMime: String? = null,
    val photoBytes: ByteArray? = null,
    val photoSha1: String? = null,
    val vcardFetchedAtMs: Long? = null,
    val vcardFailureAtMs: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val room: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerEntity) return false
        return accountId == other.accountId &&
            jid == other.jid &&
            displayName == other.displayName &&
            localNickname == other.localNickname &&
            photoMime == other.photoMime &&
            photoSha1 == other.photoSha1 &&
            vcardFetchedAtMs == other.vcardFetchedAtMs &&
            vcardFailureAtMs == other.vcardFailureAtMs &&
            room == other.room &&
            photoBytes.contentEquals(other.photoBytes)
    }

    override fun hashCode(): Int {
        var result = accountId.hashCode()
        result = 31 * result + jid.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (localNickname?.hashCode() ?: 0)
        result = 31 * result + (photoMime?.hashCode() ?: 0)
        result = 31 * result + (photoSha1?.hashCode() ?: 0)
        result = 31 * result + (vcardFetchedAtMs?.hashCode() ?: 0)
        result = 31 * result + (vcardFailureAtMs?.hashCode() ?: 0)
        result = 31 * result + room.hashCode()
        result = 31 * result + (photoBytes?.contentHashCode() ?: 0)
        return result
    }
}

@Entity(
    tableName = "message_threads",
    primaryKeys = ["accountId", "peerJid", "messageKind", "threadId"],
    foreignKeys = [
        ForeignKey(
            entity = PeerEntity::class,
            parentColumns = ["accountId", "jid"],
            childColumns = ["accountId", "peerJid"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageThreadEntity::class,
            parentColumns = ["accountId", "peerJid", "messageKind", "threadId"],
            childColumns = ["accountId", "peerJid", "messageKind", "parentThreadId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["accountId", "peerJid"]),
        Index(value = ["accountId", "peerJid", "messageKind", "parentThreadId"]),
    ],
)
data class MessageThreadEntity(
    val accountId: String,
    val peerJid: String,
    val messageKind: MessageKind,
    val threadId: String,
    val parentThreadId: String?,
)

enum class MessageDirection {
    INBOUND,
    OUTBOUND,
}

@Entity(
    tableName = "messages",
    primaryKeys = ["accountId", "localMessageId"],
    foreignKeys = [
        ForeignKey(
            entity = PeerEntity::class,
            parentColumns = ["accountId", "jid"],
            childColumns = ["accountId", "peerJid"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageThreadEntity::class,
            parentColumns = ["accountId", "peerJid", "messageKind", "threadId"],
            childColumns = ["accountId", "peerJid", "messageKind", "threadId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["accountId", "localSequence"], unique = true),
        Index(value = ["accountId", "peerJid"]),
        Index(value = ["accountId", "peerJid", "messageKind", "threadId"]),
    ],
)
data class MessageEntity(
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
    val sentAtEpochMs: Long? = null,
    val sentTimeSource: MessageTimeSource? = null,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val replyToId: String? = null,
    val replyToJid: String? = null,
    val replyFallbackBody: String? = null,
)

@Entity(
    tableName = "archive_message_positions",
    primaryKeys = ["accountId", "archiveAuthority", "archiveScope", "archiveOrdinal"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["accountId", "localMessageId"],
            childColumns = ["accountId", "messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["accountId", "archiveAuthority", "archiveScope", "messageId"],
            unique = true,
        ),
        Index(value = ["accountId", "messageId"]),
    ],
)
data class ArchiveMessagePositionEntity(
    val accountId: String,
    val archiveAuthority: String,
    val archiveScope: String,
    val archiveOrdinal: Long,
    val messageId: String,
)

@Entity(
    tableName = "account_message_sequences",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["accountId"],
)
data class AccountMessageSequenceEntity(
    val accountId: String,
    val nextValue: Long,
)

enum class IdentityAliasKind {
    ORIGIN_ID,
    MESSAGE_ID,
    STANZA_ID,
    MAM_RESULT,
}

enum class IdentityAliasStatus {
    TRUSTED,
    QUARANTINED,
}

@Entity(
    tableName = "trusted_identity_aliases",
    primaryKeys = ["accountId", "kind", "authority", "value"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["accountId", "localMessageId"],
            childColumns = ["accountId", "messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["accountId", "messageId"])],
)
data class TrustedIdentityAliasEntity(
    val accountId: String,
    val kind: IdentityAliasKind,
    val authority: String,
    val value: String,
    val messageId: String?,
    val status: IdentityAliasStatus,
)

@Entity(
    tableName = "identity_conflicts",
    primaryKeys = [
        "accountId",
        "kind",
        "authority",
        "value",
        "firstMessageId",
        "secondMessageId",
    ],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["accountId", "localMessageId"],
            childColumns = ["accountId", "firstMessageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["accountId", "localMessageId"],
            childColumns = ["accountId", "secondMessageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountId", "firstMessageId"]),
        Index(value = ["accountId", "secondMessageId"]),
    ],
)
data class IdentityConflictEntity(
    val accountId: String,
    val kind: IdentityAliasKind,
    val authority: String,
    val value: String,
    val firstMessageId: String,
    val secondMessageId: String,
    val detectedAtSequence: Long,
)

enum class OutboxStatus {
    PENDING,
    IN_FLIGHT,
    ACKNOWLEDGED,
    CONFIRMED,
    UNCERTAIN,
    FAILED,
}

@Entity(
    tableName = "message_outbox",
    primaryKeys = ["accountId", "operationId"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["accountId", "localMessageId"],
            childColumns = ["accountId", "messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountId", "messageId"], unique = true),
        Index(value = ["accountId", "originId"], unique = true),
        Index(value = ["accountId", "status"]),
    ],
)
data class OutboxEntity(
    val accountId: String,
    val operationId: String,
    val messageId: String,
    val originId: String,
    val status: OutboxStatus,
    val generation: Long?,
    val attempt: Int,
    val failureReason: String?,
)

@Entity(
    tableName = "archive_cursors",
    primaryKeys = ["accountId", "archiveAuthority", "scope"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArchiveCursorEntity(
    val accountId: String,
    val archiveAuthority: String,
    val scope: String,
    val oldestId: String?,
    val newestId: String?,
    val hasEarlier: Boolean,
    val retryableError: String?,
    val oldestOrdinal: Long? = null,
    val newestOrdinal: Long? = null,
)

@Entity(
    tableName = "message_drafts",
    primaryKeys = ["accountId", "peerJid", "messageKind", "threadKey"],
    foreignKeys = [
        ForeignKey(
            entity = PeerEntity::class,
            parentColumns = ["accountId", "jid"],
            childColumns = ["accountId", "peerJid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["accountId", "peerJid"])],
)
data class MessageDraftEntity(
    val accountId: String,
    val peerJid: String,
    val messageKind: MessageKind,
    val threadKey: String,
    val body: String,
    val replyToId: String? = null,
    val replyToJid: String? = null,
    val replyFallbackBody: String? = null,
    val replyFallbackSender: String? = null,
) {
    init {
        require(replyToId == null || replyToId.isNotEmpty()) { "Draft reply ID must not be empty" }
        require(replyToId != null || listOf(replyToJid, replyFallbackBody, replyFallbackSender).all { it == null }) {
            "Draft reply metadata requires an ID"
        }
        require(replyToId == null || (replyFallbackBody != null && replyFallbackSender != null)) {
            "Draft reply requires fallback content"
        }
    }
}

@Entity(
    tableName = "chat_navigation",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["accountId"],
)
data class ChatNavigationEntity(
    val accountId: String,
    val peerJid: String,
    val threadId: String?,
    val parentThreadId: String?,
) {
    init {
        require(peerJid.isNotEmpty()) { "Navigation peer must not be empty" }
        require(threadId != null || parentThreadId == null) { "Navigation parent requires a thread" }
        require(threadId == null || threadId.isNotEmpty()) { "Navigation thread must not be empty" }
        require(parentThreadId == null || parentThreadId.isNotEmpty()) {
            "Navigation parent thread must not be empty"
        }
        require(threadId == null || threadId != parentThreadId) { "Navigation thread cannot parent itself" }
    }
}

class MessageConverters {
    @TypeConverter
    fun messageKind(value: MessageKind): String = value.name

    @TypeConverter
    fun messageKind(value: String): MessageKind = MessageKind.valueOf(value)

    @TypeConverter
    fun messageDirection(value: MessageDirection): String = value.name

    @TypeConverter
    fun messageDirection(value: String): MessageDirection = MessageDirection.valueOf(value)

    @TypeConverter
    fun aliasKind(value: IdentityAliasKind): String = value.name

    @TypeConverter
    fun aliasKind(value: String): IdentityAliasKind = IdentityAliasKind.valueOf(value)

    @TypeConverter
    fun aliasStatus(value: IdentityAliasStatus): String = value.name

    @TypeConverter
    fun aliasStatus(value: String): IdentityAliasStatus = IdentityAliasStatus.valueOf(value)

    @TypeConverter
    fun outboxStatus(value: OutboxStatus): String = value.name

    @TypeConverter
    fun outboxStatus(value: String): OutboxStatus = OutboxStatus.valueOf(value)

    @TypeConverter
    fun messageTimeSource(value: MessageTimeSource?): String? = value?.name

    @TypeConverter
    fun messageTimeSource(value: String?): MessageTimeSource? = value?.let(MessageTimeSource::valueOf)
}
