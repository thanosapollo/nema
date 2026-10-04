package org.thanosapollo.nema.storage

import org.thanosapollo.nema.xmpp.omemo.ProtectedContent
import org.thanosapollo.nema.xmpp.omemo.ProtectedContentCodec

internal fun MessageEntity.isProtected(): Boolean = protectedState != "NONE" || protectedEvidence != null

internal fun MessageEntity.protection(): ProtectedContent? =
    ProtectedContentCodec.decode(protectedState, protectedEvidence)

internal fun MessageEntity.protectedCompatible(incoming: IncomingMessage): Boolean {
    if (!isProtected()) return incoming.protection == null
    val previous = protection() ?: return false
    return incoming.protection?.let(previous::sameContent) == true && senderJid == incoming.senderJid
}

/** Called before any dependent reparent or owner deletion, including repair callers. */
internal fun preflightProtectedMerge(first: MessageEntity, second: MessageEntity): ProtectedContent? {
    if (!first.isProtected() && !second.isProtected()) return null
    require(first.accountId == second.accountId && first.peerJid == second.peerJid &&
        first.senderJid == second.senderJid && first.direction == second.direction &&
        first.messageKind == second.messageKind && first.threadId == second.threadId &&
        first.parentThreadId == second.parentThreadId && first.body == second.body &&
        first.attachmentUrl == second.attachmentUrl &&
        (first.attachmentName == null || second.attachmentName == null || first.attachmentName == second.attachmentName) &&
        (first.attachmentMime == null || second.attachmentMime == null || first.attachmentMime == second.attachmentMime) &&
        (first.attachmentSize == null || second.attachmentSize == null || first.attachmentSize == second.attachmentSize) &&
        first.replyToId == second.replyToId &&
        first.replyToJid == second.replyToJid && first.replyFallbackBody == second.replyFallbackBody) {
        "Protected merge scope or fallback differs"
    }
    val left = requireNotNull(first.protection()) { "Incomplete protected merge source" }
    val right = requireNotNull(second.protection()) { "Incomplete protected merge destination" }
    require(left.sameContent(right)) { "Protected content differs or is incomplete" }
    return if (first.localSequence <= second.localSequence) left.union(right) else right.union(left)
}
