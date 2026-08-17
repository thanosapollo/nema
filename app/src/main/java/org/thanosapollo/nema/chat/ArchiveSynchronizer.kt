package org.thanosapollo.nema.chat

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.storage.ArchiveCursorKey
import org.thanosapollo.nema.storage.ArchiveDirection
import org.thanosapollo.nema.storage.ArchivePage
import org.thanosapollo.nema.storage.ArchivePageRejectedException
import org.thanosapollo.nema.storage.ArchivePageResult
import org.thanosapollo.nema.storage.ArchivePageStatus
import org.thanosapollo.nema.storage.ArchivedIncomingMessage
import org.thanosapollo.nema.storage.ArchivedReceiptSignal
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.xmpp.transport.ACCOUNT_ARCHIVE_SCOPE
import org.thanosapollo.nema.xmpp.transport.ArchivePageDirection
import org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope
import org.thanosapollo.nema.xmpp.transport.ArchivePageRequest
import org.thanosapollo.nema.xmpp.transport.SessionCapabilities

sealed interface ArchiveSyncState {
    data object Idle : ArchiveSyncState
    data class Discovering(val identity: SessionIdentity) : ArchiveSyncState
    data class Unsupported(
        val identity: SessionIdentity,
        val capabilities: SessionCapabilities,
    ) : ArchiveSyncState
    data class Syncing(
        val identity: SessionIdentity,
        val capabilities: SessionCapabilities,
        val direction: ArchivePageDirection,
    ) : ArchiveSyncState
    data class Ready(
        val identity: SessionIdentity,
        val capabilities: SessionCapabilities,
    ) : ArchiveSyncState
    data class RetryableError(
        val identity: SessionIdentity,
        val capabilities: SessionCapabilities?,
        val reason: String,
    ) : ArchiveSyncState
}

class ArchiveStorageFailure(
    val identity: SessionIdentity,
    cause: Throwable,
) : Exception(cause)

class ArchiveSynchronizer(
    private val store: MessageStore,
    private val discover: suspend (SessionIdentity) -> SessionCapabilities,
    private val query: suspend (ArchivePageRequest) -> ArchivePageEnvelope,
    private val localIds: () -> String = { UUID.randomUUID().toString() },
    private val commit: suspend (
        SessionIdentity,
        ArchivePage,
        () -> Boolean,
    ) -> ArchivePageResult? = { _, page, isAuthoritative ->
        if (isAuthoritative()) store.applyArchivePage(page) else null
    },
) {
    private val mutableState = MutableStateFlow<ArchiveSyncState>(ArchiveSyncState.Idle)
    private val operationMutex = Mutex()
    val state: StateFlow<ArchiveSyncState> = mutableState

    suspend fun synchronize(
        identity: SessionIdentity,
        archiveAuthority: String,
        scope: String = ACCOUNT_ARCHIVE_SCOPE,
        isAuthoritative: () -> Boolean,
    ) = operationMutex.withLock {
        synchronizeLocked(identity, archiveAuthority, isAuthoritative, scope)
    }

    private suspend fun synchronizeLocked(
        identity: SessionIdentity,
        archiveAuthority: String,
        isAuthoritative: () -> Boolean,
        scope: String,
    ) {
        mutableState.value = ArchiveSyncState.Discovering(identity)
        val capabilities = try {
            discover(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isAuthoritative()) {
                mutableState.value = ArchiveSyncState.RetryableError(
                    identity,
                    null,
                    failure.javaClass.simpleName,
                )
            }
            return
        }
        if (!isAuthoritative()) return
        if (!capabilities.mamV2) {
            mutableState.value = ArchiveSyncState.Unsupported(identity, capabilities)
            return
        }

        val key = ArchiveCursorKey(identity.accountId.value, archiveAuthority, scope)
        var cursor = storage(identity) { store.archiveCursor(key) }
        var direction = if (cursor?.newestId == null) {
            ArchivePageDirection.BOOTSTRAP
        } else {
            ArchivePageDirection.AFTER
        }
        var boundary = cursor?.newestId

        repeat(MAX_PAGES_PER_RUN) {
            if (!isAuthoritative()) return
            mutableState.value = ArchiveSyncState.Syncing(identity, capabilities, direction)
            val request = ArchivePageRequest(
                accountId = identity.accountId,
                generation = identity.generation,
                archiveAuthority = archiveAuthority,
                scope = scope,
                direction = direction,
                boundaryId = boundary,
                pageSize = PAGE_SIZE,
            )
            val page = queryPage(identity, capabilities, request, isAuthoritative) ?: return
            if (!isAuthoritative()) return
            val result = commitPage(identity, capabilities, page, isAuthoritative) ?: return
            if (result.status == ArchivePageStatus.RETRYABLE_ERROR) {
                mutableState.value = ArchiveSyncState.RetryableError(
                    identity,
                    capabilities,
                    requireNotNull(result.cursor.retryableError),
                )
                return
            }
            cursor = result.cursor
            when {
                direction == ArchivePageDirection.BEFORE && cursor.hasEarlier -> {
                    boundary = requireNotNull(cursor.oldestId) {
                        "MAM backfill did not advance oldest cursor"
                    }
                }
                direction == ArchivePageDirection.BEFORE -> {
                    mutableState.value = ArchiveSyncState.Ready(identity, capabilities)
                    return
                }
                !page.complete -> {
                    direction = ArchivePageDirection.AFTER
                    boundary = requireNotNull(cursor.newestId) {
                        "MAM page did not advance newest cursor"
                    }
                }
                else -> {
                    mutableState.value = ArchiveSyncState.Ready(identity, capabilities)
                    return
                }
            }
        }
        mutableState.value = ArchiveSyncState.RetryableError(
            identity,
            capabilities,
            "Archive page limit reached",
        )
    }

    suspend fun backfillOnePage(
        identity: SessionIdentity,
        archiveAuthority: String,
        isAuthoritative: () -> Boolean,
    ): Boolean = operationMutex.withLock {
        backfillOnePageLocked(identity, archiveAuthority, isAuthoritative)
    }

    private suspend fun backfillOnePageLocked(
        identity: SessionIdentity,
        archiveAuthority: String,
        isAuthoritative: () -> Boolean,
    ): Boolean {
        val ready = mutableState.value as? ArchiveSyncState.Ready ?: return false
        if (ready.identity != identity) return false
        val key = ArchiveCursorKey(identity.accountId.value, archiveAuthority, ACCOUNT_ARCHIVE_SCOPE)
        val cursor = storage(identity) { store.archiveCursor(key) } ?: return false
        if (!cursor.hasEarlier || cursor.oldestId == null || !isAuthoritative()) return false
        val request = ArchivePageRequest(
            accountId = identity.accountId,
            generation = identity.generation,
            archiveAuthority = archiveAuthority,
            scope = ACCOUNT_ARCHIVE_SCOPE,
            direction = ArchivePageDirection.BEFORE,
            boundaryId = cursor.oldestId,
            pageSize = PAGE_SIZE,
        )
        mutableState.value = ArchiveSyncState.Syncing(identity, ready.capabilities, request.direction)
        val page = queryPage(identity, ready.capabilities, request, isAuthoritative) ?: return false
        if (!isAuthoritative()) return false
        val result = commitPage(identity, ready.capabilities, page, isAuthoritative) ?: return false
        mutableState.value = if (result.status == ArchivePageStatus.APPLIED) {
            ArchiveSyncState.Ready(identity, ready.capabilities)
        } else {
            ArchiveSyncState.RetryableError(
                identity,
                ready.capabilities,
                requireNotNull(result.cursor.retryableError),
            )
        }
        return result.status == ArchivePageStatus.APPLIED
    }

    fun disconnected() {
        mutableState.value = ArchiveSyncState.Idle
    }

    private suspend fun queryPage(
        identity: SessionIdentity,
        capabilities: SessionCapabilities,
        request: ArchivePageRequest,
        isAuthoritative: () -> Boolean,
    ): ArchivePageEnvelope? = try {
        query(request).also { page ->
            require(page.request == request) { "MAM response scope mismatch" }
            require(
                page.messages.all {
                    listOfNotNull(it.message?.accountId, it.signal?.accountId).all { account ->
                        account == request.accountId
                    } && listOfNotNull(it.message?.generation, it.signal?.generation).all { generation ->
                        generation == request.generation
                    }
                },
            ) { "MAM response message scope mismatch" }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (isAuthoritative()) {
            mutableState.value = ArchiveSyncState.RetryableError(
                identity,
                capabilities,
                failure.javaClass.simpleName,
            )
        }
        null
    }

    private suspend fun commitPage(
        identity: SessionIdentity,
        capabilities: SessionCapabilities,
        page: ArchivePageEnvelope,
        isAuthoritative: () -> Boolean,
    ): ArchivePageResult? = try {
        storage(identity) {
            commit(identity, page.toStoragePage(localIds), isAuthoritative)
        }
    } catch (rejected: ArchivePageRejectedException) {
        if (isAuthoritative()) {
            mutableState.value = ArchiveSyncState.RetryableError(
                identity,
                capabilities,
                rejected.javaClass.simpleName,
            )
        }
        null
    }

    private suspend fun <T> storage(identity: SessionIdentity, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: ArchivePageRejectedException) {
        throw rejected
    } catch (failure: Exception) {
        throw ArchiveStorageFailure(identity, failure)
    }

    companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES_PER_RUN = 100
    }
}

private fun ArchivePageEnvelope.toStoragePage(localIds: () -> String): ArchivePage = ArchivePage(
    key = ArchiveCursorKey(
        request.accountId.value,
        request.archiveAuthority,
        request.scope,
    ),
    direction = when (request.direction) {
        ArchivePageDirection.BOOTSTRAP -> ArchiveDirection.BOOTSTRAP
        ArchivePageDirection.BEFORE -> ArchiveDirection.BEFORE
        ArchivePageDirection.AFTER -> ArchiveDirection.AFTER
    },
    boundaryId = request.boundaryId,
    complete = complete,
    hasEarlier = hasEarlier,
    stable = stable,
    firstId = firstId,
    lastId = lastId,
    messages = messages.map {
        ArchivedIncomingMessage(
            resultId = it.resultId,
            message = it.message?.toIncomingMessage(localIds()),
            signal = it.signal?.let { signal ->
                ArchivedReceiptSignal(
                    peerJid = signal.peer,
                    senderJid = signal.sender,
                    targetId = signal.targetId,
                    stage = signal.stage,
                )
            },
        )
    },
)
