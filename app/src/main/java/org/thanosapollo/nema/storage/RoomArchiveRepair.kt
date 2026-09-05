package org.thanosapollo.nema.storage

import org.thanosapollo.nema.thread.MessageKind

internal fun roomArchiveRepairKey(room: String): String = "room-archive-uid-v1:${room.length}:$room"

internal data class RoomRepairComponent(
    val messages: List<MessageEntity>,
    val claims: List<TrustedIdentityAliasEntity>,
    val blocked: Boolean,
) {
    fun pair(room: String, positions: Map<String, List<ArchiveMessagePositionEntity>>): Pair<MessageEntity, MessageEntity>? {
        if (blocked || messages.size != 2 || messages.any {
                it.peerJid != room || it.messageKind != MessageKind.GROUPCHAT ||
                    it.replaceId != null || it.correctionTargetMessageId != null
            }) return null
        val live = messages.singleOrNull { it.liveDeliveryObserved && positions[it.localMessageId].isNullOrEmpty() }
            ?: return null
        val mam = messages.singleOrNull { !it.liveDeliveryObserved && !positions[it.localMessageId].isNullOrEmpty() }
            ?: return null
        val authority = ArchiveCursorKey(live.accountId, room, room).aliasAuthority()
        if (positions[mam.localMessageId].orEmpty().any {
                it.archiveAuthority != room || it.archiveScope != room
            } || live.senderJid != mam.senderJid) return null
        // Every retained claim must be canonical for this unit; malformed claims only block.
        if (claims.any {
                it.status != IdentityAliasStatus.TRUSTED || it.value.isEmpty() ||
                    when (it.kind) {
                        IdentityAliasKind.STANZA_ID -> it.authority != room
                        IdentityAliasKind.MAM_RESULT -> it.authority != authority || it.messageId != mam.localMessageId
                        else -> false
                    }
            }) return null
        // One room authority cannot assign contradictory UIDs to the proposed identity.
        if (claims.filter { it.kind == IdentityAliasKind.STANZA_ID || it.kind == IdentityAliasKind.MAM_RESULT }
                .map { it.value }.distinct().size != 1) return null
        val sids = claims.filter { it.kind == IdentityAliasKind.STANZA_ID && it.messageId == live.localMessageId }
        val uids = claims.filter { it.kind == IdentityAliasKind.MAM_RESULT && it.messageId == mam.localMessageId }
        if (sids.none { sid -> uids.any { it.value == sid.value } }) return null
        return live to mam
    }
}

/** Freeze account-wide connectivity before selecting a room. Ineligible owners still connect. */
internal fun roomRepairComponents(
    messages: List<MessageEntity>,
    aliases: List<TrustedIdentityAliasEntity>,
    conflicts: List<IdentityConflictEntity>,
    outboxIds: Set<String>,
): List<RoomRepairComponent> {
    val rows = messages.associateBy(MessageEntity::localMessageId)
    require(rows.size == messages.size)
    val links = rows.keys.associateWith { mutableSetOf<String>() }
    val claims = aliases.toMutableList()
    val blocked = outboxIds.toMutableSet()
    conflicts.forEach {
        links[it.firstMessageId]?.add(it.secondMessageId)
        links[it.secondMessageId]?.add(it.firstMessageId)
        blocked.addAll(listOf(it.firstMessageId, it.secondMessageId))
        // Quarantined aliases can have no owner. Conflict rows preserve both claimants.
        listOf(it.firstMessageId, it.secondMessageId).forEach { owner ->
            claims.add(TrustedIdentityAliasEntity(it.accountId, it.kind, it.authority, it.value,
                owner, IdentityAliasStatus.QUARANTINED))
        }
    }
    claims.filter { it.status == IdentityAliasStatus.QUARANTINED }.mapNotNullTo(blocked) { it.messageId }
    val identities = claims.filter { it.kind == IdentityAliasKind.STANZA_ID || it.kind == IdentityAliasKind.MAM_RESULT }
        .groupBy { claim ->
            val authority = if (claim.kind == IdentityAliasKind.STANZA_ID) claim.authority
                else decodedArchiveAuthority(claim.authority)
            authority to claim.value
        }
    identities.forEach { (identity, owners) ->
        val ids = owners.mapNotNull { it.messageId }.distinct()
        // A star is sufficient for transitive connectivity; no quadratic clique allocation.
        val first = ids.firstOrNull()
        ids.drop(1).forEach { id ->
            if (first != null) { links[first]?.add(id); links[id]?.add(first) }
        }
        if (identity.first == null || identity.second.isEmpty() || owners.any {
                it.status != IdentityAliasStatus.TRUSTED || it.messageId == null
            }) blocked.addAll(ids)
    }
    // An undecodable authority cannot safely separate its claimant from the same opaque UID.
    val undecodableValues = identities.filterKeys { it.first == null }.keys.map { it.second }.toSet()
    claims.filter { it.value in undecodableValues &&
        (it.kind == IdentityAliasKind.STANZA_ID || it.kind == IdentityAliasKind.MAM_RESULT)
    }.groupBy { it.value }.values.forEach { owners ->
        val ids = owners.mapNotNull { it.messageId }.distinct()
        blocked.addAll(ids)
        val first = ids.firstOrNull()
        ids.drop(1).forEach { id ->
            if (first != null) { links[first]?.add(id); links[id]?.add(first) }
        }
    }
    val byOwner = claims.groupBy(TrustedIdentityAliasEntity::messageId)
    val unseen = rows.keys.toMutableSet()
    return buildList {
        while (unseen.isNotEmpty()) {
            val pending = ArrayDeque<String>()
            pending.add(unseen.first())
            val component = mutableListOf<String>()
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (!unseen.remove(id)) continue
                component.add(id)
                links[id].orEmpty().forEach(pending::addLast)
            }
            add(RoomRepairComponent(component.map(rows::getValue), component.flatMap { byOwner[it].orEmpty() },
                component.any { it in blocked }))
        }
    }
}

// Permissive decoding is ONLY connectivity for blockers, never positive merge evidence.
private fun decodedArchiveAuthority(encoded: String): String? {
    val separator = encoded.indexOf(':')
    if (separator < 1) return null
    val length = encoded.substring(0, separator).toIntOrNull() ?: return null
    val rest = encoded.substring(separator + 1)
    return if (length > 0 && length <= rest.length) rest.substring(0, length) else null
}
