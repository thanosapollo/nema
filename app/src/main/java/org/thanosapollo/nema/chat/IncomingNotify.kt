package org.thanosapollo.nema.chat

import org.thanosapollo.nema.storage.ArchiveDirection
import org.thanosapollo.nema.storage.IngestionResult

fun shouldNotifyInsertedInbound(
    result: IngestionResult,
    inbound: Boolean,
    groupChat: Boolean,
    visiblePeer: String?,
    peerJid: String,
    direction: ArchiveDirection? = null,
): Boolean {
    if (!result.inserted || result.identityConflict || !inbound || groupChat) return false
    if (direction != null && direction != ArchiveDirection.AFTER) return false
    if (visiblePeer == peerJid) return false
    return peerJid.isNotBlank()
}
