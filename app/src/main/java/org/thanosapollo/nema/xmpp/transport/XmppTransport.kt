package org.thanosapollo.nema.xmpp.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.xmpp.chatstates.ChatActivity

const val ACCOUNT_ARCHIVE_SCOPE = "ACCOUNT"

@JvmInline
value class AccountId private constructor(val value: String) {
    companion object {
        fun require(value: String): AccountId {
            require(value.isNotEmpty()) { "Account ID must not be empty" }
            return AccountId(value)
        }
    }
}

@JvmInline
value class ConnectionGeneration private constructor(val value: Long) {
    companion object {
        fun require(value: Long): ConnectionGeneration {
            require(value > 0) { "Connection generation must be positive" }
            return ConnectionGeneration(value)
        }
    }
}

enum class TransportConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}

enum class MessageTimeSource {
    LOCAL,
    DELAYED,
    CARBON,
    MAM,
}

enum class MessageReceiptStage {
    RECEIVED,
    DISPLAYED,
    ACKNOWLEDGED,
}

enum class MessageSignalProtocol {
    DELIVERY_RECEIPT,
    CHAT_MARKER,
}

data class IncomingMessageSignal(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val peer: String,
    val sender: String,
    val targetId: String,
    val stage: MessageReceiptStage,
    val protocol: MessageSignalProtocol,
) {
    init {
        require(peer.isNotEmpty()) { "Peer must not be empty" }
        require(sender.isNotEmpty()) { "Sender must not be empty" }
        require(targetId.isNotEmpty()) { "Receipt target must not be empty" }
    }
}

data class IncomingChatState(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val peer: String,
    val actor: String,
    val groupChat: Boolean,
    val activity: ChatActivity,
) {
    init {
        require(peer.isNotEmpty()) { "Peer must not be empty" }
        require(actor.isNotEmpty()) { "Actor must not be empty" }
    }
}

data class IncomingReactionEnvelope(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val accountBareJid: String,
    val peer: String,
    val senderBareJid: String,
    val targetId: String,
    val emojis: List<String>,
    val delayedAtMs: Long? = null,
) {
    init {
        require(accountBareJid.isNotEmpty()) { "Account JID must not be empty" }
        require(peer.isNotEmpty()) { "Peer must not be empty" }
        require(senderBareJid.isNotEmpty()) { "Sender must not be empty" }
        require(targetId.isNotEmpty()) { "Reaction target must not be empty" }
    }
}

data class OutgoingReactionEnvelope(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val recipient: String,
    val targetId: String,
    val emojis: List<String>,
) {
    init {
        require(recipient.isNotEmpty()) { "Recipient must not be empty" }
        require(targetId.isNotEmpty()) { "Reaction target must not be empty" }
    }
}

data class ConnectionEnvelope(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val state: TransportConnectionState,
)

data class IncomingMessageEnvelope(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val peer: String,
    val sender: String,
    val outbound: Boolean,
    val originId: String?,
    val body: String,
    val thread: ThreadRef?,
    val stanzaIds: List<StanzaIdEnvelope> = emptyList(),
    val messageId: String? = null,
    val kind: MessageKind = MessageKind.CHAT,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val reply: MessageReplyEnvelope? = null,
    val sentAtEpochMs: Long? = null,
    val sentTimeSource: MessageTimeSource? = null,
    val receiptRequested: Boolean = false,
    val markable: Boolean = false,
    val replaceId: String? = null,
) {
    init {
        require(peer.isNotEmpty()) { "Peer must not be empty" }
        require(sender.isNotEmpty()) { "Sender must not be empty" }
        require(originId == null || originId.isNotEmpty()) { "Origin ID must not be empty" }
        require(replaceId == null || replaceId.isNotEmpty()) { "Correction target must not be empty" }
        // WIP-FOUNDATION: crypto/protection later. Empty body is legal only with an attachment.
        // Mapper still copies OOB URL into body for persist/preview; do not change that here.
        require(body.isNotEmpty() || !attachmentUrl.isNullOrEmpty()) { "Message payload must not be empty" }
        require(stanzaIds.distinct().size == stanzaIds.size) { "Stanza IDs must be unique" }
        require((sentAtEpochMs == null) == (sentTimeSource == null)) {
            "Message time and provenance must be stored together"
        }
    }
}

data class OutgoingMessageSignal(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val recipient: String,
    val targetId: String,
    val stage: MessageReceiptStage,
    val protocol: MessageSignalProtocol,
) {
    init {
        require(recipient.isNotEmpty()) { "Signal recipient must not be empty" }
        require(targetId.isNotEmpty()) { "Signal target must not be empty" }
        require(protocol == MessageSignalProtocol.CHAT_MARKER || stage == MessageReceiptStage.RECEIVED) {
            "Delivery receipts can only acknowledge receipt"
        }
    }
}

data class OutgoingFailureEnvelope(
    val operationId: String,
    val peer: String,
    val reason: String,
) {
    init {
        require(operationId.isNotEmpty()) { "Operation ID must not be empty" }
        require(peer.isNotEmpty()) { "Peer must not be empty" }
        require(reason.isNotEmpty()) { "Failure reason must not be empty" }
    }
}

data class MessageReplyEnvelope(
    val id: String,
    val to: String? = null,
    val fallbackBody: String? = null,
    val fallbackSender: String? = null,
) {
    init {
        require(id.isNotEmpty()) { "Reply ID must not be empty" }
        require(to == null || to.isNotEmpty()) { "Reply author must not be empty" }
    }
}

data class StanzaIdEnvelope(
    val id: String,
    val by: String,
) {
    init {
        require(id.isNotEmpty()) { "Stanza ID must not be empty" }
        require(by.isNotEmpty()) { "Stanza ID authority must not be empty" }
    }
}

// Disco bits actually consumed today. Blocking/upload/bookmarks/MUC/vCard stay call-time probes.
// WIP-FOUNDATION: roster, presence, encryption, and calls get no fields until a writer exists.
data class SessionCapabilities(
    val mamV2: Boolean,
    val carbons: Boolean,
    val carbonsEnabled: Boolean,
    val stableIds: Boolean,
)

enum class ArchivePageDirection {
    BOOTSTRAP,
    BEFORE,
    AFTER,
}

data class ArchivePageRequest(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val archiveAuthority: String,
    val scope: String,
    val direction: ArchivePageDirection,
    val boundaryId: String?,
    val pageSize: Int,
) {
    init {
        require(archiveAuthority.isNotEmpty()) { "Archive authority must not be empty" }
        require(scope.isNotEmpty()) { "Archive scope must not be empty" }
        require(pageSize in 1..100) { "Archive page size must be between 1 and 100" }
        require((direction == ArchivePageDirection.BOOTSTRAP) == (boundaryId == null)) {
            "Only bootstrap omits an archive boundary"
        }
        require(boundaryId == null || boundaryId.isNotEmpty()) { "Archive boundary must not be empty" }
    }
}

data class ArchiveMessageEnvelope(
    val resultId: String,
    val message: IncomingMessageEnvelope?,
    val signal: IncomingMessageSignal? = null,
) {
    init {
        require(resultId.isNotEmpty()) { "MAM result ID must not be empty" }
        require(message == null || signal == null) { "MAM result cannot be both content and control" }
    }
}

data class ArchivePageEnvelope(
    val request: ArchivePageRequest,
    val stable: Boolean,
    val complete: Boolean,
    val hasEarlier: Boolean,
    val firstId: String?,
    val lastId: String?,
    val messages: List<ArchiveMessageEnvelope>,
) {
    init {
        require(messages.map(ArchiveMessageEnvelope::resultId).distinct().size == messages.size) {
            "MAM page result IDs must be unique"
        }
    }
}

data class OutgoingMessageEnvelope(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val attempt: Int,
    val operationId: String,
    val originId: String,
    val recipient: String,
    val body: String,
    val thread: ThreadRef?,
    val kind: MessageKind = MessageKind.CHAT,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val reply: MessageReplyEnvelope? = null,
    val replaceId: String? = null,
) {
    init {
        require(attempt > 0) { "Send attempt must be positive" }
        require(operationId.isNotEmpty()) { "Operation ID must not be empty" }
        require(originId.isNotEmpty()) { "Origin ID must not be empty" }
        require(recipient.isNotEmpty()) { "Recipient must not be empty" }
        // WIP-FOUNDATION: crypto/protection later. Empty body is legal only with an attachment.
        require(body.isNotEmpty() || !attachmentUrl.isNullOrEmpty()) { "Message payload must not be empty" }
        require(replaceId == null || replaceId.isNotEmpty()) { "Correction target must not be empty" }
        require(kind == MessageKind.CHAT || replaceId == null) { "Only direct chat messages may be corrected" }
    }
}

class SendNotAttemptedException : Exception()

interface XmppTransport {
    val connectionStates: StateFlow<ConnectionEnvelope>
    val incomingMessages: Flow<IncomingMessageEnvelope>

    suspend fun send(message: OutgoingMessageEnvelope)

    suspend fun close()
}
