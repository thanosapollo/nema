package org.thanosapollo.nema.storage

import org.thanosapollo.nema.xmpp.transport.MessageReceiptStage

enum class ArchiveDirection {
    BOOTSTRAP,
    BEFORE,
    AFTER,
}

data class ArchiveCursorKey(
    val accountId: String,
    val archiveAuthority: String,
    val scope: String,
) {
    init {
        require(accountId.isNotEmpty()) { "Archive account must not be empty" }
        require(archiveAuthority.isNotEmpty()) { "Archive authority must not be empty" }
        require(scope.isNotEmpty()) { "Archive scope must not be empty" }
    }
}

data class ArchivedIncomingMessage(
    val resultId: String,
    val message: IncomingMessage?,
    val signal: ArchivedReceiptSignal? = null,
) {
    init {
        require(resultId.isNotEmpty()) { "MAM result ID must not be empty" }
        require(message == null || signal == null) { "Archive result cannot be both content and control" }
    }
}

data class ArchivedReceiptSignal(
    val peerJid: String,
    val senderJid: String,
    val targetId: String,
    val stage: MessageReceiptStage,
) {
    init {
        require(peerJid.isNotEmpty()) { "Receipt peer must not be empty" }
        require(senderJid.isNotEmpty()) { "Receipt sender must not be empty" }
        require(targetId.isNotEmpty()) { "Receipt target must not be empty" }
    }
}

data class ArchivePage(
    val key: ArchiveCursorKey,
    val direction: ArchiveDirection,
    val boundaryId: String?,
    val complete: Boolean,
    val hasEarlier: Boolean,
    val stable: Boolean,
    val firstId: String?,
    val lastId: String?,
    val messages: List<ArchivedIncomingMessage>,
) {
    init {
        require((direction == ArchiveDirection.BOOTSTRAP) == (boundaryId == null)) {
            "Only bootstrap omits an archive boundary"
        }
        require(boundaryId == null || boundaryId.isNotEmpty()) { "Archive boundary must not be empty" }
        require(messages.map(ArchivedIncomingMessage::resultId).distinct().size == messages.size) {
            "MAM page result IDs must be unique"
        }
    }
}

enum class ArchivePageStatus {
    APPLIED,
    RETRYABLE_ERROR,
}

data class InsertedInbound(
    val peerJid: String,
    val preview: String,
    val inbound: Boolean,
    val groupChat: Boolean,
    val thread: org.thanosapollo.nema.thread.ThreadRef? = null,
    val protectedState: String = "NONE",
)

data class ArchivePageResult(
    val status: ArchivePageStatus,
    val cursor: ArchiveCursorEntity,
    val ingested: Int,
    val inserted: Int = 0,
    val insertedInbound: List<InsertedInbound> = emptyList(),
)

internal class ArchivePageRejectedException(message: String) : Exception(message)

internal fun ArchiveCursorKey.aliasAuthority(): String =
    "${archiveAuthority.length}:$archiveAuthority$scope"

internal fun ArchiveCursorKey.emptyCursor(): ArchiveCursorEntity = ArchiveCursorEntity(
    accountId = accountId,
    archiveAuthority = archiveAuthority,
    scope = scope,
    oldestId = null,
    newestId = null,
    hasEarlier = false,
    retryableError = null,
)
