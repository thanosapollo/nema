package org.thanosapollo.nema.session

import org.thanosapollo.nema.xmpp.threads.DirectoryAction
import org.thanosapollo.nema.xmpp.threads.ThreadDirectoryScope
import org.thanosapollo.nema.xmpp.threads.ThreadDirectorySnapshot
import org.thanosapollo.nema.xmpp.threads.ThreadDirectoryMutationResult
import org.thanosapollo.nema.xmpp.threads.ThreadDirectoryException

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ArchivePageEnvelope
import org.thanosapollo.nema.xmpp.transport.ArchivePageRequest
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.IncomingChatState
import org.thanosapollo.nema.xmpp.transport.IncomingReactionEnvelope
import org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.IncomingMessageSignal
import org.thanosapollo.nema.xmpp.transport.OutgoingFailureEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageSignal
import org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException
import org.thanosapollo.nema.xmpp.transport.CarbonCapabilityState
import org.thanosapollo.nema.xmpp.transport.SessionCapabilities
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingState
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingMutationResult
import org.thanosapollo.nema.xmpp.httpupload.LocalUploadRequest
import org.thanosapollo.nema.xmpp.httpupload.UploadedFile
import org.thanosapollo.nema.xmpp.vcard.RemoteVCardPayload

data class SessionIdentity(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
)

@JvmInline
value class ConnectionAttempt private constructor(val value: Long) {
    companion object {
        fun require(value: Long): ConnectionAttempt {
            require(value > 0) { "Connection attempt must be positive" }
            return ConnectionAttempt(value)
        }
    }
}

@JvmInline
value class LifecycleEpoch private constructor(val value: Long) {
    companion object {
        fun require(value: Long): LifecycleEpoch {
            require(value > 0) { "Lifecycle epoch must be positive" }
            return LifecycleEpoch(value)
        }
    }
}

data class SessionAttemptIdentity(
    val accountId: AccountId,
    val generation: ConnectionGeneration,
    val attempt: ConnectionAttempt,
    val epoch: LifecycleEpoch,
)

enum class SessionFailureReason {
    TLS_CERTIFICATE,
    AUTHENTICATION,
    NETWORK,
    TOR_UNAVAILABLE,
    CONFIGURATION,
    RETRY_EXHAUSTED,
    LOCAL_STORAGE,
    PROTOCOL,
}

class SessionFailure(
    val reason: SessionFailureReason,
    cause: Throwable? = null,
) : Exception(cause)

sealed interface SessionEvent {
    data class ConnectionLost(
        val attempt: SessionAttemptIdentity,
        val reason: SessionFailureReason,
    ) : SessionEvent
    data class Incoming(
        val attempt: SessionAttemptIdentity,
        val message: IncomingMessageEnvelope,
    ) : SessionEvent
    data class Signal(
        val attempt: SessionAttemptIdentity,
        val signal: IncomingMessageSignal,
    ) : SessionEvent
    data class ChatState(
        val attempt: SessionAttemptIdentity,
        val state: IncomingChatState,
    ) : SessionEvent
    data class RealTimeText(
        val attempt: SessionAttemptIdentity,
        val state: org.thanosapollo.nema.xmpp.transport.IncomingRealTimeText,
    ) : SessionEvent
    data class Reaction(
        val attempt: SessionAttemptIdentity,
        val reaction: IncomingReactionEnvelope,
    ) : SessionEvent {
        init {
            require(reaction.accountId == attempt.accountId)
            require(reaction.generation == attempt.generation)
        }
    }
    data class OutgoingFailure(
        val attempt: SessionAttemptIdentity,
        val failure: OutgoingFailureEnvelope,
    ) : SessionEvent
    data class RoomUpdated(
        val attempt: SessionAttemptIdentity,
        val view: org.thanosapollo.nema.xmpp.muc.RoomView,
    ) : SessionEvent
    data class RosterSnapshot(
        val attempt: SessionAttemptIdentity,
        val snapshot: org.thanosapollo.nema.storage.CompleteRosterSnapshot,
    ) : SessionEvent {
        init {
            require(snapshot.accountId == attempt.accountId.value)
        }
    }
}

class RoomRepairAuthorization internal constructor(
    val attempt: SessionAttemptIdentity,
    val room: String,
    val admit: () -> Boolean,
)

interface SessionConnection {
    suspend fun listThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, directory: ThreadDirectoryScope): ThreadDirectorySnapshot =
        throw ThreadDirectoryException.Unsupported()
    suspend fun mutateThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, action: DirectoryAction): ThreadDirectoryMutationResult =
        throw ThreadDirectoryException.Unsupported()

    val isUsable: Boolean
    val onionWithoutTls: Boolean get() = false
    fun revoke()
    suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity)
    suspend fun reconnect(attempt: SessionAttemptIdentity)
    fun updateAttempt(attempt: SessionAttemptIdentity)
    suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit)
    suspend fun sendSignal(signal: OutgoingMessageSignal) {
        throw UnsupportedOperationException("Message signals are unsupported")
    }
    suspend fun sendReaction(reaction: org.thanosapollo.nema.xmpp.transport.OutgoingReactionEnvelope) {
        throw UnsupportedOperationException("Reactions are unsupported")
    }
    suspend fun sendChatState(state: org.thanosapollo.nema.xmpp.transport.OutgoingChatState) {
        throw UnsupportedOperationException("Chat states are unsupported")
    }
    suspend fun discoverCapabilities(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): SessionCapabilities = SessionCapabilities(false, CarbonCapabilityState.UNSUPPORTED, false)
    suspend fun queryArchive(request: ArchivePageRequest): ArchivePageEnvelope =
        throw UnsupportedOperationException("Archive queries are unsupported")
    // Call-time probes. Do not promote these into unused SessionCapabilities bits.
    suspend fun loadVCard(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
    ): RemoteVCardPayload = throw UnsupportedOperationException("vCard is unsupported")
    suspend fun peerBlockingState(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
    ): PeerBlockingState = PeerBlockingState(supported = false)
    suspend fun setPeerBlocked(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
        blocked: Boolean,
        entered: () -> Unit,
    ): PeerBlockingMutationResult = PeerBlockingMutationResult.NotAttempted
    suspend fun fetchHttpFile(
        accountId: AccountId,
        generation: ConnectionGeneration,
        url: String,
    ): ByteArray? = null
    suspend fun uploadHttpFile(
        accountId: AccountId,
        generation: ConnectionGeneration,
        request: LocalUploadRequest,
    ): UploadedFile? = null
    suspend fun joinMuc(
        accountId: AccountId,
        generation: ConnectionGeneration,
        roomJid: String,
        nick: String? = null,
        password: String? = null,
    ): Boolean = false
    fun roomRepairAuthorization(room: String): RoomRepairAuthorization? = null
    suspend fun bookmarkedRooms(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): List<String> = bookmarkedRoomDetails(accountId, generation).bookmarks.map { it.roomJid }
    suspend fun bookmarkedRoomDetails(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): org.thanosapollo.nema.xmpp.bookmarks.RoomBookmarkSnapshot =
        org.thanosapollo.nema.xmpp.bookmarks.RoomBookmarkSnapshot(emptyList(), complete = false)
    suspend fun publishRoomBookmark(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bookmark: org.thanosapollo.nema.xmpp.bookmarks.RoomBookmark,
    ): Boolean = false
    // WIP-FOUNDATION: roster, presence, encryption, and calls have no methods until a writer exists.
    suspend fun disconnect()
}

fun interface SessionConnectionFactory {
    fun create(
        configuration: AccountConfiguration,
        identity: SessionIdentity,
        event: (SessionEvent) -> Unit,
    ): SessionConnection
}

sealed interface ConnectionState {
    data object Stopped : ConnectionState
    data class NeedsCredentials(val accountId: AccountId? = null) : ConnectionState
    data class Disconnected(val accountId: AccountId) : ConnectionState
    data class Switching(
        val fromAccountId: AccountId,
        val toAccountId: AccountId,
    ) : ConnectionState
    data class Connecting(
        val accountId: AccountId,
        val generation: ConnectionGeneration,
    ) : ConnectionState
    data class Connected(
        val accountId: AccountId,
        val generation: ConnectionGeneration,
        val onionWithoutTls: Boolean = false,
    ) : ConnectionState
    data class ReconnectWait(
        val accountId: AccountId,
        val generation: ConnectionGeneration,
    ) : ConnectionState
    data class Disconnecting(
        val accountId: AccountId,
        val generation: ConnectionGeneration,
    ) : ConnectionState
    data class Failed(
        val accountId: AccountId,
        val generation: ConnectionGeneration,
        val reason: SessionFailureReason,
    ) : ConnectionState
}

internal data class SessionLifecycleObservation(
    val state: ConnectionState,
    val epoch: LifecycleEpoch?,
    val owner: SessionIdentity?,
)

internal data class DispatchLease(
    val epoch: LifecycleEpoch,
    val identity: SessionIdentity,
)

internal enum class DispatchRevocationResult {
    COMPLETE,
    LOCAL_STORAGE_FAILED,
}

internal interface RevokedDispatch {
    fun freezeUnknownEntry()
    suspend fun finish(): DispatchRevocationResult
}

internal fun SessionLifecycleObservation.dispatchLease(): DispatchLease? {
    val connected = state as? ConnectionState.Connected ?: return null
    val exactOwner = owner ?: return null
    if (exactOwner.accountId != connected.accountId || exactOwner.generation != connected.generation) return null
    return DispatchLease(epoch ?: return null, exactOwner)
}

internal class ActiveSessionController(
    scope: CoroutineScope,
    private val factory: SessionConnectionFactory,
    private val durableEvent: suspend (SessionEvent) -> Unit = {},
    private val retryWait: suspend (attempt: Int) -> Unit = { attempt ->
        delay((1L shl (attempt - 1).coerceAtMost(5)) * 1_000L)
    },
    private val teardownTimeoutMillis: Long = 5_000L,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val revokeDispatch: (DispatchLease) -> RevokedDispatch? = { null },
    private val retireEphemeral: () -> Unit = {},
) {
    private val controllerJob = SupervisorJob()
    private val controllerScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + controllerJob)
    private val commandMutex = Mutex()
    private val inboundGate = Mutex()
    private val stateMutex = Mutex()
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    private val mutableLifecycle = MutableStateFlow(
        SessionLifecycleObservation(ConnectionState.Stopped, null, null),
    )
    val state: StateFlow<ConnectionState> = mutableState
    internal val lifecycle: StateFlow<SessionLifecycleObservation> = mutableLifecycle

    @Volatile
    private var current: OwnedSession? = null
    private var lastGeneration = 0L
    private var lifecycleEpoch = 0L
    private val revocations = mutableMapOf<DispatchLease, RevocationRecord>()
    private var latestRevocation: RevocationRecord? = null
    private var destroyRequested = false

    suspend fun start(configuration: AccountConfiguration, credential: CharArray) {
        val deadline = shutdownDeadline()
        commandMutex.withLock {
            stopCurrent(ConnectionState.Stopped, deadline)
            startConnection(configuration, credential)
        }
    }

    suspend fun switchTo(
        configuration: AccountConfiguration,
        credential: CharArray,
        switchingFrom: AccountId = configuration.id,
        persistActive: suspend (AccountId) -> Unit,
    ) {
        val deadline = shutdownDeadline()
        commandMutex.withLock {
            stopCurrent(ConnectionState.Switching(switchingFrom, configuration.id), deadline)
            persistActive(configuration.id)
            currentCoroutineContext().ensureActive()
            startConnection(configuration, credential)
        }
    }

    suspend fun stop() {
        val deadline = shutdownDeadline()
        commandMutex.withLock {
            stopCurrent(ConnectionState.Stopped, deadline, preserveTerminalStorageFailure = true)
        }
    }

    suspend fun serviceDestroyed() {
        val deadline = shutdownDeadline()
        commandMutex.withLock {
            val finalState = stateMutex.withLock {
                val pendingState = latestRevocation
                    ?.takeIf { revocations[it.lease] === it }
                    ?.publication
                    ?.state
                when {
                    mutableState.value is ConnectionState.NeedsCredentials -> mutableState.value
                    pendingState is ConnectionState.NeedsCredentials -> pendingState
                    else -> ConnectionState.Stopped
                }
            }
            stopCurrent(finalState, deadline, preserveTerminalStorageFailure = true)
        }
    }

    suspend fun destroy() {
        val deadline = shutdownDeadline()
        commandMutex.withLock {
            stopCurrent(ConnectionState.Stopped, deadline, preserveTerminalStorageFailure = true)
        }
        stateMutex.withLock {
            destroyRequested = true
            cancelControllerIfFinishedLocked()
        }
    }

    suspend fun requireCredentials(accountId: AccountId? = null) {
        val deadline = shutdownDeadline()
        commandMutex.withLock { stopCurrent(ConnectionState.NeedsCredentials(accountId), deadline) }
    }

    internal suspend fun outboxStorageFailed(
        accountId: AccountId,
        generation: ConnectionGeneration,
        observation: SessionLifecycleObservation?,
    ) {
        val deadline = shutdownDeadline()
        val failureState = ConnectionState.Failed(
            accountId,
            generation,
            SessionFailureReason.LOCAL_STORAGE,
        )
        val record = stateMutex.withLock {
            val owner = current
            when {
                owner?.identity?.accountId == accountId && owner.identity.generation == generation ->
                    revokeOwnerLocked(
                        owner,
                        failureState,
                        deadline,
                        currentCoroutineContext()[Job],
                        storageFailed = true,
                    )
                owner != null -> null
                else -> observation?.dispatchKey()?.let(revocations::get)?.also {
                    markStorageFailureLocked(it, failureState)
                }
            }
        }
        record?.let { awaitRevocation(it) }
    }

    suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) {
        val target = commandMutex.withLock {
            stateMutex.withLock {
                val candidate = current ?: throw SendNotAttemptedException()
                val lease = mutableLifecycle.value.dispatchLease() ?: throw SendNotAttemptedException()
                if (!owns(candidate) ||
                    lease.identity != candidate.identity ||
                    lease.identity.accountId != message.accountId ||
                    lease.identity.generation != message.generation
                ) {
                    throw SendNotAttemptedException()
                }
                candidate.connection
            }
        }
        target.send(message, entered)
    }

    suspend fun sendSignal(signal: OutgoingMessageSignal) {
        exactConnection(signal.accountId, signal.generation).sendSignal(signal)
    }

    suspend fun sendReaction(reaction: org.thanosapollo.nema.xmpp.transport.OutgoingReactionEnvelope) {
        exactConnection(reaction.accountId, reaction.generation).sendReaction(reaction)
    }

    suspend fun sendChatState(state: org.thanosapollo.nema.xmpp.transport.OutgoingChatState) {
        exactConnection(state.accountId, state.generation).sendChatState(state)
    }

    suspend fun discoverCapabilities(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): SessionCapabilities {
        val target = exactConnection(accountId, generation)
        return target.discoverCapabilities(accountId, generation)
    }

    suspend fun queryArchive(request: ArchivePageRequest): ArchivePageEnvelope {
        val target = exactConnection(request.accountId, request.generation)
        return target.queryArchive(request)
    }

    suspend fun loadVCard(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
    ): RemoteVCardPayload {
        val target = exactConnection(accountId, generation)
        return target.loadVCard(accountId, generation, bareJid)
    }

    suspend fun peerBlockingState(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
    ): PeerBlockingState {
        val target = exactConnection(accountId, generation)
        return target.peerBlockingState(accountId, generation, bareJid)
    }

    suspend fun setPeerBlocked(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bareJid: String,
        blocked: Boolean,
    ): PeerBlockingMutationResult {
        var entered = false
        return try {
            val target = exactConnection(accountId, generation)
            target.setPeerBlocked(accountId, generation, bareJid, blocked) {
                entered = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (entered) {
                PeerBlockingMutationResult.Uncertain
            } else {
                PeerBlockingMutationResult.NotAttempted
            }
        }
    }

    suspend fun fetchHttpFile(
        accountId: AccountId,
        generation: ConnectionGeneration,
        url: String,
    ): ByteArray? = exactConnection(accountId, generation).fetchHttpFile(accountId, generation, url)

    suspend fun uploadHttpFile(
        accountId: AccountId,
        generation: ConnectionGeneration,
        request: LocalUploadRequest,
    ): UploadedFile? {
        val target = exactConnection(accountId, generation)
        return target.uploadHttpFile(accountId, generation, request)
    }

    suspend fun joinMuc(
        accountId: AccountId,
        generation: ConnectionGeneration,
        roomJid: String,
        nick: String? = null,
        password: String? = null,
    ): Boolean {
        val target = exactConnection(accountId, generation)
        return target.joinMuc(accountId, generation, roomJid, nick, password)
    }

    suspend fun bookmarkedRooms(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): List<String> = bookmarkedRoomDetails(accountId, generation).bookmarks.map { it.roomJid }

    suspend fun bookmarkedRoomDetails(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): org.thanosapollo.nema.xmpp.bookmarks.RoomBookmarkSnapshot {
        val target = exactConnection(accountId, generation)
        return target.bookmarkedRoomDetails(accountId, generation)
    }

    suspend fun publishRoomBookmark(
        accountId: AccountId,
        generation: ConnectionGeneration,
        bookmark: org.thanosapollo.nema.xmpp.bookmarks.RoomBookmark,
    ): Boolean {
        val target = exactConnection(accountId, generation)
        return target.publishRoomBookmark(accountId, generation, bookmark)
    }

    internal suspend fun roomRepairAuthorization(lease: DispatchLease, room: String): RoomRepairAuthorization? {
        val target = exactConnection(lease.identity.accountId, lease.identity.generation)
        if (mutableLifecycle.value.dispatchLease() != lease) return null
        return target.roomRepairAuthorization(room)?.takeIf {
            it.attempt.accountId == lease.identity.accountId && it.attempt.generation == lease.identity.generation &&
                it.attempt.epoch == lease.epoch && mutableLifecycle.value.dispatchLease() == lease
        }
    }

    internal suspend fun <T> commitIfConnected(
        identity: SessionIdentity,
        isAuthoritative: () -> Boolean,
        commit: suspend () -> T,
    ): T? {
        stateMutex.lock()
        try {
            val lease = mutableLifecycle.value.dispatchLease()
            if (lease?.identity != identity || !isAuthoritative()) return null
            return commit()
        } finally {
            stateMutex.unlock()
        }
    }

    // Shares the lifecycle lock: a suspended durable writer cannot revive a retired projection.
    internal suspend fun applyEphemeral(attempt: SessionAttemptIdentity, apply: () -> Unit) {
        stateMutex.withLock {
            val owner = current ?: return
            if (!owns(owner, attempt) || !isHealthy(owner, attempt)) return
            if (mutableState.value !is ConnectionState.Connected &&
                mutableState.value !is ConnectionState.Connecting
            ) return
            apply()
        }
    }

    suspend fun listThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, directory: ThreadDirectoryScope): ThreadDirectorySnapshot =
        exactConnection(accountId, generation).listThreadDirectory(accountId, generation, directory)

    suspend fun mutateThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, action: DirectoryAction): ThreadDirectoryMutationResult =
        exactConnection(accountId, generation).mutateThreadDirectory(accountId, generation, action)

    private suspend fun exactConnection(
        accountId: AccountId,
        generation: ConnectionGeneration,
    ): SessionConnection = commandMutex.withLock {
        stateMutex.withLock {
            val candidate = current ?: throw SendNotAttemptedException()
            val lease = mutableLifecycle.value.dispatchLease() ?: throw SendNotAttemptedException()
            if (!owns(candidate) || lease.identity != candidate.identity ||
                lease.identity.accountId != accountId || lease.identity.generation != generation
            ) {
                throw SendNotAttemptedException()
            }
            candidate.connection
        }
    }

    private suspend fun startConnection(
        configuration: AccountConfiguration,
        credential: CharArray,
    ) {
        val (identity, epoch) = stateMutex.withLock {
            SessionIdentity(configuration.id, nextGeneration()) to currentEpoch()
        }
        val connection = try {
            factory.create(configuration, identity, ::recordEvent)
        } catch (_: Exception) {
            stateMutex.withLock {
                if (lifecycleEpoch == epoch.value && current == null) {
                    publishStateLocked(
                        ConnectionState.Failed(
                            configuration.id,
                            identity.generation,
                            SessionFailureReason.CONFIGURATION,
                        ),
                        identity,
                    )
                }
            }
            return
        }
        val owned = OwnedSession(identity, epoch, connection)
        val attempt = stateMutex.withLock {
            if (lifecycleEpoch != epoch.value || current != null) return@withLock null
            current = owned
            publishStateLocked(ConnectionState.Connecting(identity.accountId, identity.generation))
            owned.nextAttempt()
        }
        if (attempt == null) {
            disconnectUnowned(connection)
            return
        }
        try {
            connection.connect(credential, attempt)
            stateMutex.withLock {
                if (!owns(owned, attempt)) return@withLock
                if (!isHealthy(owned, attempt)) {
                    beginReconnectLocked(owned)
                } else {
                    publishStateLocked(ConnectionState.Connected(identity.accountId, identity.generation, connection.onionWithoutTls))
                }
            }
        } catch (failure: CancellationException) {
            withContext(NonCancellable) {
                val record = stateMutex.withLock {
                    if (!owns(owned, attempt)) return@withLock null
                    revokeOwnerLocked(
                        owned,
                        ConnectionState.Stopped,
                        shutdownDeadline(),
                        currentCoroutineContext()[Job],
                    )
                }
                record?.let { awaitRevocation(it) }
            }
            throw failure
        } catch (failure: SessionFailure) {
            if (failure.reason == SessionFailureReason.NETWORK) {
                stateMutex.withLock {
                    if (owns(owned, attempt)) beginReconnectLocked(owned)
                }
            } else {
                fail(owned, attempt, failure.reason)
            }
        } catch (failure: Exception) {
            fail(owned, attempt, SessionFailureReason.CONFIGURATION)
        }
    }

    private fun recordEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.ConnectionLost -> {
                val owner = current
                if (owner?.accepts(event.attempt) == true) {
                    owner.lossObserved.set(event.attempt)
                }
                controllerScope.launch { handleConnectionLoss(event) }
            }
            is SessionEvent.Incoming -> runBlocking { handleDurableEvent(event.attempt, event) }
            is SessionEvent.Signal -> runBlocking { handleDurableEvent(event.attempt, event) }
            is SessionEvent.ChatState -> runBlocking { handleDurableEvent(event.attempt, event) }
            is SessionEvent.RealTimeText -> runBlocking { handleDurableEvent(event.attempt, event) }
            is SessionEvent.Reaction -> runBlocking { handleDurableEvent(event.attempt, event) }
            is SessionEvent.OutgoingFailure -> runBlocking { handleDurableEvent(event.attempt, event) }
            is SessionEvent.RoomUpdated -> runBlocking { handleIncomingRoom(event) }
            is SessionEvent.RosterSnapshot -> runBlocking { handleDurableEvent(event.attempt, event) }
        }
    }

    private suspend fun handleConnectionLoss(event: SessionEvent.ConnectionLost) {
        val terminalOwner = stateMutex.withLock {
            val owner = current
            if (owner == null || !owns(owner, event.attempt)) return
            if (owner.lossObserved.get() != event.attempt) return
            if (event.reason == SessionFailureReason.NETWORK) {
                beginReconnectLocked(owner)
                null
            } else {
                owner
            }
        }
        terminalOwner?.let { fail(it, event.attempt, event.reason) }
    }

    private suspend fun handleDurableEvent(
        attempt: SessionAttemptIdentity,
        event: SessionEvent,
    ) {
        inboundGate.withLock {
            val owner = stateMutex.withLock {
                current?.takeIf { owns(it, attempt) && it.connection.isUsable }
            } ?: return
            try {
                durableEvent(event)
            } catch (_: Exception) {
                stateMutex.withLock {
                    if (owns(owner, attempt)) {
                        revokeOwnerLocked(
                            owner,
                            ConnectionState.Failed(
                                owner.identity.accountId,
                                owner.identity.generation,
                                SessionFailureReason.LOCAL_STORAGE,
                            ),
                            shutdownDeadline(),
                            currentCoroutineContext()[Job],
                            storageFailed = true,
                        )
                    }
                }
            }
        }
    }

    private suspend fun handleIncomingRoom(event: SessionEvent.RoomUpdated) {
        inboundGate.withLock {
            val owner = stateMutex.withLock {
                current?.takeIf { owns(it, event.attempt) && it.connection.isUsable }
            } ?: return
            try {
                durableEvent(event)
            } catch (_: Exception) {
                // Soft failure: occupant/subject snapshots must not tear down the session.
            }
        }
    }

    private fun beginReconnectLocked(owner: OwnedSession): Boolean {
        if (!owns(owner) || owner.reconnectJob?.isActive == true) return false
        val lease = mutableLifecycle.value.dispatchLease()
            ?.takeIf { it.identity == owner.identity }
        publishStateLocked(ConnectionState.ReconnectWait(
            owner.identity.accountId,
            owner.identity.generation,
        ))
        val retiredDispatch = lease?.let(::retireDispatchLocked)
        val job = controllerScope.launch(start = CoroutineStart.LAZY) {
            reconnect(owner, retiredDispatch)
        }
        owner.reconnectJob = job
        job.start()
        return true
    }

    private suspend fun reconnect(owner: OwnedSession, retiredDispatch: RetiredDispatch?) {
        if (!settleRetiredDispatch(owner, retiredDispatch)) return
        for (retry in 1..MAX_RECONNECT_ATTEMPTS) {
            retryWait(retry)
            val attempt = inboundGate.withLock {
                stateMutex.withLock {
                    if (!owns(owner)) return
                    owner.identity = SessionIdentity(owner.identity.accountId, nextGeneration())
                    owner.nextAttempt().also(owner.connection::updateAttempt).also {
                        publishStateLocked(ConnectionState.Connecting(
                            owner.identity.accountId,
                            owner.identity.generation,
                        ))
                    }
                }
            }
            val connected = try {
                owner.connection.reconnect(attempt)
                true
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: SessionFailure) {
                if (failure.reason != SessionFailureReason.NETWORK) {
                    fail(owner, attempt, failure.reason)
                    return
                }
                false
            } catch (_: Exception) {
                false
            }
            if (!connected) {
                stateMutex.withLock {
                    if (owns(owner, attempt)) publishReconnectWait(owner)
                }
                continue
            }
            val finished = inboundGate.withLock {
                stateMutex.withLock {
                    if (!owns(owner, attempt)) return@withLock true
                    if (!isHealthy(owner, attempt)) {
                        publishReconnectWait(owner)
                        return@withLock false
                    }
                    owner.reconnectJob = null
                    publishStateLocked(ConnectionState.Connected(
                        owner.identity.accountId,
                        owner.identity.generation,
                        owner.connection.onionWithoutTls,
                    ))
                    true
                }
            }
            if (finished) return
        }
        val finalAttempt = stateMutex.withLock {
            if (!owns(owner)) return
            owner.attemptIdentity ?: return
        }
        fail(owner, finalAttempt, SessionFailureReason.RETRY_EXHAUSTED)
    }

    private fun retireDispatchLocked(lease: DispatchLease): RetiredDispatch? {
        val attachment = revokeDispatch(lease) ?: return null
        return RetiredDispatch(
            lease = lease,
            attachment = attachment,
            deadlineMillis = shutdownDeadline(),
            settlement = controllerScope.async { attachment.finish() },
        )
    }

    private suspend fun settleRetiredDispatch(
        owner: OwnedSession,
        retired: RetiredDispatch?,
    ): Boolean {
        if (retired == null || !stateMutex.withLock { owns(owner) }) return true
        val remaining = retired.deadlineMillis - nowMillis()
        val result = if (remaining <= 0L) {
            null
        } else {
            withTimeoutOrNull(remaining) { retired.settlement.await() }
        }
        if (result == null) {
            retired.attachment.freezeUnknownEntry()
            controllerScope.launch {
                if (retired.settlement.await() == DispatchRevocationResult.LOCAL_STORAGE_FAILED) {
                    failOwnedSessionForStorage(owner, retired)
                }
            }
        }
        if (result == DispatchRevocationResult.LOCAL_STORAGE_FAILED) {
            failOwnedSessionForStorage(owner, retired)
            return false
        }
        return true
    }

    private suspend fun failOwnedSessionForStorage(
        owner: OwnedSession,
        retiredDispatch: RetiredDispatch,
    ) {
        val record = stateMutex.withLock {
            if (!owns(owner)) return
            val exactRetired = retiredDispatch.takeIf {
                it.lease == DispatchLease(owner.epoch, owner.identity)
            }
            revokeOwnerLocked(
                owner,
                ConnectionState.Failed(
                    owner.identity.accountId,
                    owner.identity.generation,
                    SessionFailureReason.LOCAL_STORAGE,
                ),
                exactRetired?.deadlineMillis ?: shutdownDeadline(),
                currentCoroutineContext()[Job],
                storageFailed = true,
                retiredDispatch = exactRetired,
            )
        }
        awaitRevocation(record)
    }

    private suspend fun fail(
        owner: OwnedSession,
        attempt: SessionAttemptIdentity,
        reason: SessionFailureReason,
    ) {
        val deadline = shutdownDeadline()
        val record = stateMutex.withLock {
            if (!owns(owner, attempt)) return
            revokeOwnerLocked(
                owner,
                ConnectionState.Failed(owner.identity.accountId, owner.identity.generation, reason),
                deadline,
                currentCoroutineContext()[Job],
            )
        }
        awaitRevocation(record)
    }

    private suspend fun stopCurrent(
        finalState: ConnectionState,
        deadlineMillis: Long,
        preserveTerminalStorageFailure: Boolean = false,
    ) {
        val record = stateMutex.withLock {
            val owner = current
            when {
                owner != null -> revokeOwnerLocked(owner, finalState, deadlineMillis)
                latestRevocation != null && revocations[latestRevocation!!.lease] === latestRevocation ->
                    latestRevocation!!.also { updatePublicationLocked(it, finalState) }
                preserveTerminalStorageFailure && mutableState.value.isTerminalStorageFailure() -> null
                else -> {
                    nextEpochLocked()
                    publishStateLocked(finalState)
                    null
                }
            }
        }
        record?.let { awaitRevocation(it) }
    }

    private fun revokeOwnerLocked(
        owner: OwnedSession,
        finalState: ConnectionState,
        deadlineMillis: Long,
        initiatingJob: Job? = null,
        storageFailed: Boolean = false,
        retiredDispatch: RetiredDispatch? = null,
    ): RevocationRecord {
        check(current === owner && lifecycleEpoch == owner.epoch.value)
        val lease = DispatchLease(owner.epoch, owner.identity)
        check(retiredDispatch == null || retiredDispatch.lease == lease)
        revocations[lease]?.let { existing ->
            if (storageFailed) markStorageFailureLocked(existing, finalState)
            return existing
        }
        owner.connection.revoke()
        val publication = RevocationPublication(
            nextEpochLocked(),
            finalState,
            owner.identity,
        )
        current = null
        val teardownState = finalState.takeIf { it is ConnectionState.Switching }
            ?: ConnectionState.Disconnecting(owner.identity.accountId, owner.identity.generation)
        publishStateLocked(teardownState, owner.identity)
        val attachment = retiredDispatch?.attachment ?: revokeDispatch(lease)
        val record = RevocationRecord(
            lease = lease,
            owner = owner,
            publication = publication,
            deadlineMillis = deadlineMillis,
            attachment = attachment,
            dispatchSettlement = retiredDispatch?.settlement,
            storageFailed = storageFailed,
        )
        revocations[lease] = record
        latestRevocation = record
        startRevocationLocked(record, initiatingJob)
        return record
    }

    private fun startRevocationLocked(
        record: RevocationRecord,
        initiatingJob: Job?,
    ) {
        val owner = record.owner
        val reconnectJob = owner.reconnectJob
            .also { owner.reconnectJob = null }
            ?.takeUnless { it === initiatingJob }
        reconnectJob?.cancel()
        val disconnectJob = controllerScope.launch(start = CoroutineStart.LAZY) {
            disconnectSafely(owner.connection)
        }
        record.disconnectJob = disconnectJob
        record.cleanupJob = controllerScope.launch(start = CoroutineStart.LAZY) {
            inboundGate.withLock { Unit }
            reconnectJob?.join()
            val dispatchResult = record.dispatchSettlement?.await()
                ?: record.attachment?.finish()
                ?: DispatchRevocationResult.COMPLETE
            disconnectJob.join()
            withContext(NonCancellable) {
                stateMutex.withLock {
                    if (dispatchResult == DispatchRevocationResult.LOCAL_STORAGE_FAILED) {
                        markStorageFailureLocked(
                            record,
                            ConnectionState.Failed(
                                record.lease.identity.accountId,
                                record.lease.identity.generation,
                                SessionFailureReason.LOCAL_STORAGE,
                            ),
                        )
                    }
                    publishRevocationLocked(record)
                    revocations.remove(record.lease, record)
                    if (latestRevocation === record) latestRevocation = null
                    cancelControllerIfFinishedLocked()
                }
            }
        }
        disconnectJob.start()
        record.cleanupJob.start()
    }

    private suspend fun awaitRevocation(record: RevocationRecord) {
        val remaining = record.deadlineMillis - nowMillis()
        val completed = if (record.cleanupJob.isCompleted) {
            true
        } else if (remaining <= 0L) {
            false
        } else {
            withTimeoutOrNull(remaining) {
                record.cleanupJob.join()
                true
            } ?: false
        }
        if (!completed) {
            record.attachment?.freezeUnknownEntry()
            stateMutex.withLock { publishRevocationLocked(record) }
        }
    }

    private fun updatePublicationLocked(record: RevocationRecord, finalState: ConnectionState) {
        if (!record.storageFailed && record.publication.state == finalState) return
        val state = if (record.storageFailed) {
            ConnectionState.Failed(
                record.lease.identity.accountId,
                record.lease.identity.generation,
                SessionFailureReason.LOCAL_STORAGE,
            )
        } else {
            finalState
        }
        record.publication = RevocationPublication(nextEpochLocked(), state, record.publication.owner)
        record.publicationDone = false
    }

    private fun markStorageFailureLocked(record: RevocationRecord, failureState: ConnectionState) {
        if (record.storageFailed) return
        record.storageFailed = true
        record.publication = RevocationPublication(
            currentEpoch(),
            failureState,
            record.publication.owner,
        )
        record.publicationDone = false
    }

    private fun publishRevocationLocked(record: RevocationRecord) {
        if (record.publicationDone) return
        val publication = record.publication
        if (current == null && lifecycleEpoch == publication.epoch.value) {
            publishStateLocked(publication.state, publication.owner)
            record.publicationDone = true
        }
    }

    private fun ConnectionState.isTerminalStorageFailure(): Boolean =
        this is ConnectionState.Failed && reason == SessionFailureReason.LOCAL_STORAGE

    private suspend fun disconnectSafely(connection: SessionConnection) {
        try {
            connection.disconnect()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // Teardown remains fail-closed when transport close reports failure.
        }
    }

    private suspend fun disconnectUnowned(connection: SessionConnection) {
        connection.revoke()
        val job = controllerScope.launch { disconnectSafely(connection) }
        withTimeoutOrNull(teardownTimeoutMillis) { job.join() }
    }

    private fun shutdownDeadline(): Long {
        val now = nowMillis()
        return if (Long.MAX_VALUE - now < teardownTimeoutMillis) Long.MAX_VALUE else now + teardownTimeoutMillis
    }

    private fun cancelControllerIfFinishedLocked() {
        if (destroyRequested && current == null && revocations.isEmpty()) controllerJob.cancel()
    }

    private fun SessionLifecycleObservation.dispatchKey(): DispatchLease? {
        val exactEpoch = epoch ?: return null
        val exactOwner = owner ?: return null
        return DispatchLease(exactEpoch, exactOwner)
    }

    private fun nextEpochLocked(): LifecycleEpoch {
        lifecycleEpoch += 1
        return LifecycleEpoch.require(lifecycleEpoch)
    }

    private fun currentEpoch(): LifecycleEpoch = LifecycleEpoch.require(lifecycleEpoch)

    private fun publishStateLocked(
        state: ConnectionState,
        owner: SessionIdentity? = current?.identity,
    ) {
        // Connecting starts a new generation; Connected keeps events received during login.
        if (state !is ConnectionState.Connected) retireEphemeral()
        mutableState.value = state
        mutableLifecycle.value = SessionLifecycleObservation(
            state,
            lifecycleEpoch.takeIf { it > 0 }?.let(LifecycleEpoch::require),
            owner,
        )
    }

    private fun owns(owner: OwnedSession, attempt: SessionAttemptIdentity? = null): Boolean {
        if (current !== owner || lifecycleEpoch != owner.epoch.value) return false
        return attempt == null ||
            (attempt.epoch == owner.epoch && owner.attemptIdentity == attempt)
    }

    private fun publishReconnectWait(owner: OwnedSession) {
        if (owns(owner)) {
            publishStateLocked(ConnectionState.ReconnectWait(
                owner.identity.accountId,
                owner.identity.generation,
            ))
        }
    }

    private fun nextGeneration(): ConnectionGeneration {
        lastGeneration += 1
        return ConnectionGeneration.require(lastGeneration)
    }

    private fun isHealthy(owner: OwnedSession, vararg acceptedAttempts: SessionAttemptIdentity): Boolean {
        if (owner.lossObserved.get() in acceptedAttempts) return false
        if (!owner.connection.isUsable) return false
        return owner.lossObserved.get() !in acceptedAttempts
    }

    private data class OwnedSession(
        @Volatile var identity: SessionIdentity,
        val epoch: LifecycleEpoch,
        val connection: SessionConnection,
        val lossObserved: AtomicReference<SessionAttemptIdentity?> = AtomicReference(null),
        @Volatile var attemptIdentity: SessionAttemptIdentity? = null,
        var lastAttempt: Long = 0,
        var reconnectJob: Job? = null,
    ) {
        fun nextAttempt(): SessionAttemptIdentity {
            lossObserved.set(null)
            lastAttempt += 1
            return SessionAttemptIdentity(
                identity.accountId,
                identity.generation,
                ConnectionAttempt.require(lastAttempt),
                epoch,
            ).also { attemptIdentity = it }
        }

        fun accepts(attempt: SessionAttemptIdentity): Boolean =
            attempt.epoch == epoch && attemptIdentity == attempt
    }

    private data class RetiredDispatch(
        val lease: DispatchLease,
        val attachment: RevokedDispatch,
        val deadlineMillis: Long,
        val settlement: Deferred<DispatchRevocationResult>,
    )

    private data class RevocationPublication(
        val epoch: LifecycleEpoch,
        val state: ConnectionState,
        val owner: SessionIdentity,
    )

    private class RevocationRecord(
        val lease: DispatchLease,
        val owner: OwnedSession,
        var publication: RevocationPublication,
        val deadlineMillis: Long,
        val attachment: RevokedDispatch?,
        val dispatchSettlement: Deferred<DispatchRevocationResult>?,
        var storageFailed: Boolean,
    ) {
        var publicationDone = false
        lateinit var disconnectJob: Job
        lateinit var cleanupJob: Job
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
    }
}
