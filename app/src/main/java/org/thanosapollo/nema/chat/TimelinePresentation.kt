package org.thanosapollo.nema.chat

import java.util.PriorityQueue
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.storage.RetryUncertainKey
import org.thanosapollo.nema.storage.TimelineRow
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.xmpp.transport.MessageReceiptStage

internal fun TimelineRow.toPresentation(replyReferenceIds: Set<String>) = TimelineMessage(
    id = localMessageId,
    senderJid = senderJid,
    body = if (protectedState == "NONE") correctedBody ?: body else body,
    protectedState = protectedState,
    outgoing = direction == MessageDirection.OUTBOUND,
    delivery = if (protectedState == "NONE" && direction == MessageDirection.OUTBOUND) {
        receiptStage?.let(MessageReceiptStage::valueOf)
            ?.toPresentation()
            ?: outboxStatus?.let(OutboxStatus::valueOf).toPresentation()
    } else {
        null
    },
    retryUncertainKey = if (protectedState == "NONE" && direction == MessageDirection.OUTBOUND &&
        receiptStage == null && outboxStatus == OutboxStatus.UNCERTAIN.name
    ) {
        RetryUncertainKey(
            accountId = accountId,
            operationId = requireNotNull(operationId),
            generation = requireNotNull(outboxGeneration),
            attempt = requireNotNull(outboxAttempt),
        )
    } else {
        null
    },
    thread = threadId?.let {
        ThreadRef(
            id = ThreadId.require(it),
            parentId = parentThreadId?.let(ThreadId::require),
        )
    },
    groupChat = messageKind == MessageKind.GROUPCHAT,
    attachmentUrl = attachmentUrl.takeIf { protectedState == "NONE" },
    attachmentName = attachmentName.takeIf { protectedState == "NONE" },
    attachmentMime = attachmentMime.takeIf { protectedState == "NONE" },
    attachmentSize = attachmentSize.takeIf { protectedState == "NONE" },
    replyReferenceId = replyReferenceId,
    replyReferenceIds = replyReferenceIds,
    replyToId = replyToId.takeIf { protectedState == "NONE" },
    replyToJid = replyToJid.takeIf { protectedState == "NONE" },
    replyFallbackBody = replyFallbackBody.takeIf { protectedState == "NONE" },
    markable = protectedState == "NONE" && markable,
    markerTargetId = markerTargetId.takeIf { protectedState == "NONE" },
    edited = protectedState == "NONE" && edited,
    correctionReferenceId = operationId.takeIf { protectedState == "NONE" },
    sentAtEpochMs = sentAtEpochMs,
    unresolvedCorrection = messageKind != MessageKind.GROUPCHAT && replaceId != null,
)

internal fun chronologicalTimelineRows(rows: List<TimelineRow>) = chronological(
    rows,
    archiveOrdinal = { it.conversationArchiveOrdinal },
    archiveSpine = { it.conversationArchiveAuthority to it.conversationArchiveScope },
    sentAt = { it.sentAtEpochMs },
    sequence = { it.localSequence },
    id = { it.localMessageId },
)

private fun <T> chronological(
    rows: List<T>,
    archiveOrdinal: (T) -> Long?,
    archiveSpine: (T) -> Pair<String, String>,
    sentAt: (T) -> Long?,
    sequence: (T) -> Long,
    id: (T) -> String,
): List<T> {
    val archived = rows.filter { archiveOrdinal(it) != null }
        .groupBy(archiveSpine)
        .values
        .map { spine ->
            ChronologyChain(
                spine.sortedWith(compareBy({ requireNotNull(archiveOrdinal(it)) }, sequence, id)),
                archived = true,
            )
        }
    val loose = rows.filter { archiveOrdinal(it) == null }
        .sortedWith(compareBy({ sentAt(it) ?: Long.MIN_VALUE }, sequence, id))
    val latestFirst = Comparator<ChronologyChain<T>> { left, right ->
        compareValues(sentAt(right.current) ?: Long.MIN_VALUE, sentAt(left.current) ?: Long.MIN_VALUE)
            .takeIf { it != 0 }
            ?: compareValues(right.archived, left.archived).takeIf { it != 0 }
            ?: compareValues(sequence(right.current), sequence(left.current)).takeIf { it != 0 }
            ?: compareValues(id(right.current), id(left.current))
    }
    val pending = PriorityQueue(latestFirst)
    pending.addAll(archived)
    if (loose.isNotEmpty()) pending += ChronologyChain(loose, archived = false)
    val reverse = ArrayList<T>(rows.size)
    while (pending.isNotEmpty()) {
        val chain = pending.remove()
        reverse += chain.current
        if (chain.retreat()) pending += chain
    }
    reverse.reverse()
    return reverse
}

private class ChronologyChain<T>(private val rows: List<T>, val archived: Boolean) {
    private var index = rows.lastIndex
    val current: T
        get() = rows[index]

    fun retreat(): Boolean = --index >= 0
}

internal fun OutboxStatus?.toPresentation(): DeliveryPresentation? = when (this) {
    OutboxStatus.PENDING -> DeliveryPresentation.QUEUED
    OutboxStatus.IN_FLIGHT -> DeliveryPresentation.SENDING
    OutboxStatus.ACKNOWLEDGED -> DeliveryPresentation.SENT
    OutboxStatus.CONFIRMED -> DeliveryPresentation.CONFIRMED
    OutboxStatus.UNCERTAIN -> DeliveryPresentation.UNCERTAIN
    OutboxStatus.FAILED -> DeliveryPresentation.FAILED
    null -> null
}

internal fun MessageReceiptStage.toPresentation(): DeliveryPresentation = when (this) {
    MessageReceiptStage.RECEIVED -> DeliveryPresentation.DELIVERED
    MessageReceiptStage.DISPLAYED,
    MessageReceiptStage.ACKNOWLEDGED,
    -> DeliveryPresentation.READ
}
