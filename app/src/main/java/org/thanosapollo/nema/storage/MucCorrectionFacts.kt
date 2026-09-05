package org.thanosapollo.nema.storage

import org.thanosapollo.nema.thread.MessageKind

enum class MucClaimState { UNKNOWN, NONE, VALID, INVALID, CONFLICT }
enum class MucOccupantEvidence { UNKNOWN, LIVE_ROOM, ROOM_MAM, BOTH, CONFLICT }
enum class MucPayloadState { UNKNOWN, PLAIN, UNSUPPORTED }

// Pure, dormant decision contract. These helpers neither acquire facts nor mutate accepted links.
internal fun MessageEntity.isSupportedMucPlaintext(): Boolean =
    messageKind == MessageKind.GROUPCHAT && body.isNotBlank() && mucPayloadState == MucPayloadState.PLAIN &&
        attachmentUrl == null && attachmentName == null && attachmentMime == null && attachmentSize == null &&
        replyToId == null && replyToJid == null && replyFallbackBody == null

private fun MessageEntity.hasTrustedMucActor(): Boolean =
    !mucOccupantId.isNullOrEmpty() && mucOccupantEvidence in
        setOf(MucOccupantEvidence.LIVE_ROOM, MucOccupantEvidence.ROOM_MAM, MucOccupantEvidence.BOTH)

// Necessary retained-fact conditions only. The resolver must separately require a unique trusted
// sender MESSAGE_ID target alias and non-quarantined event identities before accepting a link.
internal fun mucCorrectionFactsPermit(root: MessageEntity, edit: MessageEntity): Boolean =
    root.isSupportedMucPlaintext() && edit.isSupportedMucPlaintext() &&
        root.accountId == edit.accountId && root.peerJid == edit.peerJid &&
        root.senderJid == edit.senderJid && root.senderJid.startsWith("${root.peerJid}/") &&
        root.senderJid.length > root.peerJid.length + 1 && root.direction == edit.direction &&
        root.threadId == edit.threadId && root.parentThreadId == edit.parentThreadId &&
        root.localMessageId != edit.localMessageId && root.mucClaimState == MucClaimState.NONE &&
        root.mucReplaceId == null && root.replaceId == null && root.correctionTargetMessageId == null &&
        edit.mucClaimState == MucClaimState.VALID && !edit.mucReplaceId.isNullOrEmpty() &&
        root.mucMessageId == edit.mucReplaceId && root.hasTrustedMucActor() && edit.hasTrustedMucActor() &&
        root.mucOccupantId == edit.mucOccupantId

// Use current scoped positions, never messages.archiveOrdinal. Not a comparator: mixed evidence
// can form cycles, so callers must not sort with this decision or infer transitive precedence.
internal fun mucProvenLater(
    later: MessageEntity,
    earlier: MessageEntity,
    positions: Map<String, ArchiveMessagePositionEntity>,
): Boolean {
    if (later.accountId != earlier.accountId || later.peerJid != earlier.peerJid ||
        later.localMessageId == earlier.localMessageId) return false
    fun position(row: MessageEntity): Long? = positions[row.localMessageId]?.takeIf {
        it.accountId == row.accountId && it.archiveAuthority == row.peerJid &&
            it.archiveScope == row.peerJid && it.messageId == row.localMessageId
    }?.archiveOrdinal
    val a = position(later)
    val b = position(earlier)
    if (a != null && b != null) return a > b
    return later.mucLiveOrderEpoch != null && later.mucLiveOrderEpoch == earlier.mucLiveOrderEpoch &&
        later.localSequence > earlier.localSequence
}

// Input is the complete, already authorized bounded target set. Unknown/cyclic order means fallback.
internal fun selectMucCorrection(
    candidates: List<MessageEntity>,
    positions: Map<String, ArchiveMessagePositionEntity>,
): MessageEntity? = candidates.singleOrNull { candidate ->
    candidates.all { other -> candidate === other || mucProvenLater(candidate, other, positions) }
}
