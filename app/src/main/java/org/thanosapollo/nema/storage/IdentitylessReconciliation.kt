package org.thanosapollo.nema.storage

import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

internal const val IDENTITYLESS_RECONCILIATION_WINDOW_MS = 30_000L

internal data class IdentitylessReconciliationCandidate(
    val message: MessageEntity,
    val aliases: List<TrustedIdentityAliasEntity>,
    val positions: List<ArchiveMessagePositionEntity>,
    val hasOutbox: Boolean = false,
    val hasConflict: Boolean = false,
)

internal data class IdentitylessReconciliationPair(
    val liveMessageId: String,
    val mamMessageId: String,
)

internal data class IdentitylessArchiveClosure(
    val key: ArchiveCursorKey,
    val observedThroughMs: Long?,
    val complete: Boolean,
)

private enum class CandidateKind { LIVE, MAM }

private data class ReconciliationFingerprint(
    val accountId: String,
    val peerJid: String,
    val senderJid: String,
    val direction: MessageDirection,
    val messageKind: MessageKind,
    val threadId: String?,
    val parentThreadId: String?,
    val body: String,
    val attachmentUrl: String?,
    val attachmentName: String?,
    val attachmentMime: String?,
    val attachmentSize: Long?,
    val replyToId: String?,
    val replyToJid: String?,
    val replyFallbackBody: String?,
    val markable: Boolean,
    val markerTargetId: String?,
    val replaceId: String?,
    val correctionTargetMessageId: String?,
)

private data class ReconciliationNode(
    val messageId: String,
    val kind: CandidateKind,
    val timeMs: Long,
    val fingerprint: ReconciliationFingerprint,
    val archiveKey: ArchiveCursorKey?,
)

internal fun closedIdentitylessPair(
    seedMamId: String,
    candidates: List<IdentitylessReconciliationCandidate>,
    archiveClosure: IdentitylessArchiveClosure,
    wallFloorMs: Long?,
): IdentitylessReconciliationPair? {
    val nodes = candidates.mapNotNull(IdentitylessReconciliationCandidate::node)
    if (nodes.map(ReconciliationNode::messageId).distinct().size != nodes.size) return null
    val seed = nodes.singleOrNull { it.messageId == seedMamId && it.kind == CandidateKind.MAM }
        ?: return null
    val component = mutableSetOf(seed)
    var expanded: Boolean
    do {
        expanded = false
        nodes.filterNot(component::contains).forEach { candidate ->
            if (component.any { it.connectedTo(candidate) }) {
                component += candidate
                expanded = true
            }
        }
    } while (expanded)
    if (component.size != 2) return null
    val live = component.singleOrNull { it.kind == CandidateKind.LIVE } ?: return null
    val mam = component.singleOrNull { it.kind == CandidateKind.MAM } ?: return null
    if (mam.archiveKey != archiveClosure.key) return null
    if (!archiveClosure.complete && !strictlyBeyond(live.timeMs, archiveClosure.observedThroughMs)) return null
    if (!strictlyBeyond(mam.timeMs, wallFloorMs)) return null
    return IdentitylessReconciliationPair(live.messageId, mam.messageId)
}

private fun IdentitylessReconciliationCandidate.node(): ReconciliationNode? {
    val message = message
    if (message.direction != MessageDirection.INBOUND || hasOutbox || hasConflict) return null
    val liveTime = message.reconciliationObservedAtMs
    val node = when {
        message.sentTimeSource == MessageTimeSource.LOCAL &&
            message.liveDeliveryObserved &&
            liveTime != null && liveTime >= 0 &&
            message.archiveOrdinal == null &&
            aliases.isEmpty() && positions.isEmpty() -> Triple(CandidateKind.LIVE, liveTime, null)
        message.sentTimeSource == MessageTimeSource.MAM &&
            !message.liveDeliveryObserved &&
            message.reconciliationObservedAtMs == null &&
            message.sentAtEpochMs != null && message.sentAtEpochMs >= 0 -> Triple(
                CandidateKind.MAM,
                message.sentAtEpochMs,
                mamArchiveKey() ?: return null,
            )
        else -> return null
    }
    return ReconciliationNode(
        message.localMessageId,
        node.first,
        node.second,
        message.fingerprint(),
        node.third,
    )
}

private fun IdentitylessReconciliationCandidate.mamArchiveKey(): ArchiveCursorKey? {
    val message = message
    val alias = aliases.singleOrNull() ?: return null
    val position = positions.singleOrNull() ?: return null
    val key = ArchiveCursorKey(message.accountId, position.archiveAuthority, position.archiveScope)
    return key.takeIf {
        message.archiveOrdinal != null &&
            alias.accountId == message.accountId &&
            alias.messageId == message.localMessageId &&
            alias.kind == IdentityAliasKind.MAM_RESULT &&
            alias.status == IdentityAliasStatus.TRUSTED &&
            alias.authority == key.aliasAuthority() &&
            position.accountId == message.accountId &&
            position.messageId == message.localMessageId &&
            position.archiveOrdinal == message.archiveOrdinal
    }
}

private fun ReconciliationNode.connectedTo(other: ReconciliationNode): Boolean =
    kind != other.kind &&
        fingerprint == other.fingerprint &&
        maxOf(timeMs, other.timeMs) - minOf(timeMs, other.timeMs) <=
        IDENTITYLESS_RECONCILIATION_WINDOW_MS

private fun strictlyBeyond(referenceMs: Long, observedMs: Long?): Boolean =
    observedMs != null && observedMs >= referenceMs &&
        observedMs - referenceMs > IDENTITYLESS_RECONCILIATION_WINDOW_MS

private fun MessageEntity.fingerprint() = ReconciliationFingerprint(
    accountId,
    peerJid,
    senderJid,
    direction,
    messageKind,
    threadId,
    parentThreadId,
    body,
    attachmentUrl,
    attachmentName,
    attachmentMime,
    attachmentSize,
    replyToId,
    replyToJid,
    replyFallbackBody,
    markable,
    markerTargetId,
    replaceId,
    correctionTargetMessageId,
)
