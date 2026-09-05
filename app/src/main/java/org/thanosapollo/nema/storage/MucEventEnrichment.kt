package org.thanosapollo.nema.storage

import org.thanosapollo.nema.thread.MessageKind

// Sender IDs locate correction targets, never ordinary room events. Preserve existing
// origin/SID/MAM matching roles; only room-owned SID/MAM identities permit enrichment.
internal fun IncomingMessage.eventAliases(): List<TrustedIdentityAlias> = aliases.filter {
    messageKind != MessageKind.GROUPCHAT || it.kind != IdentityAliasKind.MESSAGE_ID
}

internal fun IncomingMessage.enrichmentAliases(): List<TrustedIdentityAlias> = eventAliases().filter {
    (it.kind == IdentityAliasKind.STANZA_ID && it.authority == peerJid) ||
        (it.kind == IdentityAliasKind.MAM_RESULT &&
            it.authority == ArchiveCursorKey(accountId, peerJid, peerJid).aliasAuthority())
}

internal fun MessageEntity.mucFacts() = MucEventFacts(
    mucMessageId, mucReplaceId, mucClaimState, mucOccupantId, mucOccupantEvidence, mucPayloadState,
)

// Only call after event identity and immutable content have been checked. Omissions do not
// erase established facts. A differing positive claim/actor is durable conflicting evidence.
internal fun MessageEntity.enrichMuc(facts: MucEventFacts?): MessageEntity {
    if (messageKind != MessageKind.GROUPCHAT || facts == null) return this
    val idConflict = mucMessageId != null && facts.messageId != null && mucMessageId != facts.messageId
    val claimConflict = mucClaimState == MucClaimState.CONFLICT || facts.claim == MucClaimState.CONFLICT ||
        (mucClaimState != MucClaimState.UNKNOWN && facts.claim != MucClaimState.UNKNOWN &&
            facts.claim != MucClaimState.NONE &&
            (mucClaimState != facts.claim || mucReplaceId != facts.replaceId))
    val actorConflict = mucOccupantEvidence == MucOccupantEvidence.CONFLICT ||
        facts.evidence == MucOccupantEvidence.CONFLICT ||
        (mucOccupantId != null && facts.occupantId != null && mucOccupantId != facts.occupantId)
    val evidence = when {
        actorConflict -> MucOccupantEvidence.CONFLICT
        mucOccupantEvidence == MucOccupantEvidence.UNKNOWN -> facts.evidence
        facts.evidence == MucOccupantEvidence.UNKNOWN || mucOccupantEvidence == facts.evidence -> mucOccupantEvidence
        else -> MucOccupantEvidence.BOTH
    }
    val claim = when {
        idConflict || claimConflict -> MucClaimState.CONFLICT
        mucClaimState == MucClaimState.UNKNOWN -> facts.claim
        else -> mucClaimState
    }
    val payload = when {
        mucPayloadState == MucPayloadState.UNSUPPORTED || facts.payload == MucPayloadState.UNSUPPORTED -> MucPayloadState.UNSUPPORTED
        mucPayloadState == MucPayloadState.UNKNOWN -> facts.payload
        else -> mucPayloadState
    }
    val revoke = claim == MucClaimState.CONFLICT || actorConflict || payload == MucPayloadState.UNSUPPORTED
    return copy(
        mucMessageId = mucMessageId ?: facts.messageId,
        mucReplaceId = if (mucClaimState == MucClaimState.UNKNOWN) facts.replaceId else mucReplaceId,
        mucClaimState = claim,
        mucOccupantId = mucOccupantId ?: facts.occupantId,
        mucOccupantEvidence = evidence,
        mucPayloadState = payload,
        replaceId = if (revoke) null else replaceId,
        correctionTargetMessageId = if (revoke) null else correctionTargetMessageId,
        mucCorrectionSelected = if (revoke) false else mucCorrectionSelected,
    )
}

internal fun MessageEntity.mucFactsCompatible(other: MessageEntity): Boolean {
    if (messageKind != MessageKind.GROUPCHAT) return true
    val merged = enrichMuc(other.mucFacts())
    val reverse = other.enrichMuc(mucFacts())
    return merged.mucClaimState != MucClaimState.CONFLICT &&
        reverse.mucClaimState != MucClaimState.CONFLICT &&
        merged.mucOccupantEvidence != MucOccupantEvidence.CONFLICT &&
        (mucPayloadState == MucPayloadState.UNKNOWN || other.mucPayloadState == MucPayloadState.UNKNOWN ||
            mucPayloadState == other.mucPayloadState)
}
