package org.thanosapollo.nema.chat

import java.util.UUID
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.IdentityAliasKind
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.TrustedIdentityAlias
import org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope

class LiveMessageAdapter(
    private val store: MessageStore,
    private val localIds: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun ingest(envelope: IncomingMessageEnvelope): org.thanosapollo.nema.storage.IngestionResult =
        store.ingest(envelope.toIncomingMessage(localIds()))
}

internal fun IncomingMessageEnvelope.toIncomingMessage(localMessageId: String): IncomingMessage =
    IncomingMessage(
        accountId = accountId.value,
        localMessageId = localMessageId,
        peerJid = peer,
        senderJid = sender,
        direction = if (outbound) MessageDirection.OUTBOUND else MessageDirection.INBOUND,
        messageKind = kind,
        threadId = thread?.id?.value,
        parentThreadId = thread?.parentId?.value,
        body = body,
        archiveOrdinal = null,
        aliases = buildList {
            originId?.let {
                add(
                    TrustedIdentityAlias(
                        IdentityAliasKind.ORIGIN_ID,
                        if (outbound) MessageStore.OUTBOUND_ORIGIN_AUTHORITY else sender,
                        it,
                    ),
                )
            }
            messageId?.let {
                add(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, sender, it))
            }
            stanzaIds.forEach {
                add(TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, it.by, it.id))
            }
        },
        attachmentUrl = attachmentUrl,
        attachmentName = attachmentName,
        attachmentMime = attachmentMime,
        attachmentSize = attachmentSize,
        replyToId = reply?.id,
        replyToJid = reply?.to,
        replyFallbackBody = reply?.fallbackBody,
        sentAtEpochMs = sentAtEpochMs,
        sentTimeSource = sentTimeSource,
        markable = markable && !outbound && kind == org.thanosapollo.nema.thread.MessageKind.CHAT,
        markerTargetId = messageId?.takeIf {
            markable && !outbound && kind == org.thanosapollo.nema.thread.MessageKind.CHAT
        },
    )
