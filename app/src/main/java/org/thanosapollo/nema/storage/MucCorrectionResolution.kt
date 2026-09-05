package org.thanosapollo.nema.storage

import org.thanosapollo.nema.thread.MessageKind

internal const val MUC_CORRECTION_CAP = 128

internal data class MucCorrectionKey(val account: String, val room: String, val sender: String, val target: String)

/** Owned by one writer transaction; old keys survive destructive alias and row changes. */
internal class MucCorrectionBatch(private val dao: MessageDao) {
    private val affected = linkedMapOf<MucCorrectionKey, MutableSet<String>>()

    fun capture(row: MessageEntity?) {
        if (row == null || row.messageKind != MessageKind.GROUPCHAT) return
        listOfNotNull(row.mucMessageId, row.mucReplaceId).forEach { target ->
            val roots = affected.getOrPut(MucCorrectionKey(row.accountId, row.peerJid, row.senderJid, target)) { linkedSetOf() }
            if (target == row.mucMessageId) roots += row.localMessageId
            if (target == row.mucReplaceId) row.correctionTargetMessageId?.let(roots::add)
        }
    }

    suspend fun capture(account: String, id: String) = capture(dao.message(account, id))

    suspend fun beforeMerge(row: MessageEntity) {
        capture(row)
        clear(row.accountId, row.localMessageId)
        row.correctionTargetMessageId?.let { clear(row.accountId, it) }
    }

    private suspend fun clear(account: String, root: String) {
        val linked = dao.acceptedMucCorrections(account, root)
        check(linked.size <= MUC_CORRECTION_CAP) { "Oversized accepted MUC correction set" }
        linked.forEach { dao.updateMessage(it.withoutMucAcceptance()) }
    }

    suspend fun settle() {
        for ((key, formerRoots) in affected) {
            val rootId = dao.trustedAlias(key.account, IdentityAliasKind.MESSAGE_ID, key.sender, key.target)?.messageId
            (formerRoots + listOfNotNull(rootId)).forEach { clear(key.account, it) }
            // Count retained claims before authorization. Overflow is terminal visible fallback.
            val claims = dao.mucCorrectionClaims(key.account, key.room, key.sender, key.target)
            if (claims.size > MUC_CORRECTION_CAP || rootId == null) continue
            val root = dao.message(key.account, rootId) ?: continue
            val ids = claims.map { it.localMessageId } + rootId
            val conflicts = dao.mucConflictedEvents(key.account, ids).toSet()
            if (rootId in conflicts) continue
            val eligible = claims.filter { it.localMessageId !in conflicts && mucCorrectionFactsPermit(root, it) }
            val positions = dao.mucCorrectionPositions(key.account, key.room, ids).associateBy { it.messageId }
            val winner = selectMucCorrection(eligible, positions) ?: continue
            eligible.forEach { edit ->
                dao.updateMessage(edit.copy(replaceId = key.target, correctionTargetMessageId = rootId,
                    mucCorrectionSelected = edit.localMessageId == winner.localMessageId))
            }
        }
        affected.clear()
    }
}

internal fun MessageEntity.withoutMucAcceptance(): MessageEntity =
    if (messageKind == MessageKind.GROUPCHAT) copy(replaceId = null, correctionTargetMessageId = null,
        mucCorrectionSelected = false) else this
