package org.thanosapollo.nema.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.thanosapollo.nema.NemaApplication
import org.thanosapollo.nema.MainActivity
import org.thanosapollo.nema.R
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.chat.ArchiveStorageFailure
import org.thanosapollo.nema.chat.ArchiveSynchronizer
import org.thanosapollo.nema.chat.ArchiveSyncState
import org.thanosapollo.nema.chat.LiveMessageAdapter
import org.thanosapollo.nema.chat.OutboxDispatcher
import org.thanosapollo.nema.chat.OutboxStorageFailure
import org.thanosapollo.nema.chat.DraftSnapshot
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.credentials.CredentialAccess
import org.thanosapollo.nema.credentials.CredentialVault
import org.thanosapollo.nema.session.ActiveSessionController
import org.thanosapollo.nema.session.ConnectionState
import org.thanosapollo.nema.session.DispatchLease
import org.thanosapollo.nema.session.SessionConnectionFactory
import org.thanosapollo.nema.session.SessionFailureReason
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.session.SessionLifecycleObservation
import org.thanosapollo.nema.session.dispatchLease
import org.thanosapollo.nema.storage.AccountRepository
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.PeerIdentityStore
import org.thanosapollo.nema.storage.RetryUncertainKey
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.xmpp.smack.SmackSessionConnectionFactory
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.SendNotAttemptedException
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingState
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingMutationResult
import org.thanosapollo.nema.xmpp.bookmarks.RoomBookmark
import org.thanosapollo.nema.xmpp.bookmarks.joinRoomBookmark
import org.thanosapollo.nema.xmpp.httpupload.LocalUploadRequest
import org.thanosapollo.nema.xmpp.httpupload.UploadedFile
import org.thanosapollo.nema.xmpp.muc.RoomStateStore
import org.thanosapollo.nema.xmpp.muc.RoomView
import org.thanosapollo.nema.xmpp.muc.roomDisplayNameToPersist
import org.thanosapollo.nema.xmpp.vcard.PeerVCardCoordinator
import org.thanosapollo.nema.xmpp.vcard.VCardLoader

fun privacySafeStatus(state: ConnectionState): String = when (state) {
    ConnectionState.Stopped -> "Stopped"
    is ConnectionState.NeedsCredentials -> "Credentials required"
    is ConnectionState.Disconnected -> "Disconnected"
    is ConnectionState.Switching -> "Switching account"
    is ConnectionState.Connecting -> "Connecting"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.ReconnectWait -> "Waiting to reconnect"
    is ConnectionState.Disconnecting -> "Disconnecting"
    is ConnectionState.Failed -> when (state.reason) {
        SessionFailureReason.TLS_CERTIFICATE -> "Secure connection failed"
        SessionFailureReason.AUTHENTICATION -> "Sign-in failed"
        SessionFailureReason.NETWORK -> "Network unavailable"
        SessionFailureReason.CONFIGURATION -> "Check connection settings"
        SessionFailureReason.RETRY_EXHAUSTED -> "Reconnect failed"
        SessionFailureReason.LOCAL_STORAGE -> "Local storage failed"
        SessionFailureReason.PROTOCOL -> "Server response invalid"
    }
}

enum class ConnectionCommandOutcome {
    RUNNING,
    NEEDS_CREDENTIALS,
    TERMINAL,
    STALE,
}

class SessionRuntime(
    private val accounts: AccountRepository,
    private val credentials: CredentialVault,
    private val messages: MessageStore,
    private val peerIdentities: PeerIdentityStore,
    runtimeScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    connectionFactory: SessionConnectionFactory = SmackSessionConnectionFactory(),
) {
    private val scope = runtimeScope
    private val accountCommands = Mutex()
    private val bookmarkMutations = Mutex()
    private val automaticConnectionClaimed = AtomicBoolean(false)
    private val pendingActivation = PendingActivationAuthority()
    private lateinit var controller: ActiveSessionController
    private val liveMessages = LiveMessageAdapter(messages)
    private val outbox = OutboxDispatcher(messages) { message, entered -> controller.send(message, entered) }
    private val archive = ArchiveSynchronizer(
        store = messages,
        discover = { controller.discoverCapabilities(it.accountId, it.generation) },
        query = { controller.queryArchive(it) },
        commit = { identity, page, isAuthoritative ->
            controller.commitIfConnected(identity, isAuthoritative) {
                messages.applyArchivePage(page)
            }
        },
    )
    private val roomArchive = ArchiveSynchronizer(
        store = messages,
        discover = { controller.discoverCapabilities(it.accountId, it.generation) },
        query = { controller.queryArchive(it) },
        commit = { identity, page, isAuthoritative ->
            controller.commitIfConnected(identity, isAuthoritative) {
                messages.applyArchivePage(page)
            }
        },
    )
    private val vcards = PeerVCardCoordinator(
        store = peerIdentities,
        loader = VCardLoader { accountId, generation, bareJid ->
            controller.loadVCard(accountId, generation, bareJid)
        },
    )
    private val pendingPeerIdentities = AtomicReference<Map<String, Set<String>>>(emptyMap())
    val rooms = RoomStateStore()
    val state: StateFlow<ConnectionState>
        get() = controller.state
    val archiveState: StateFlow<ArchiveSyncState>
        get() = archive.state
    val configuredAccounts: Flow<List<AccountConfiguration>> = accounts.configuredAccounts
    val activeAccount: Flow<AccountConfiguration?> = accounts.activeAccount

    init {
        controller = ActiveSessionController(
            scope = scope,
            factory = connectionFactory,
            durableEvent = { event ->
                when (event) {
                    is org.thanosapollo.nema.session.SessionEvent.Incoming -> {
                        liveMessages.ingest(event.message)
                        acknowledgeReceiptRequest(event.message)
                    }
                    is org.thanosapollo.nema.session.SessionEvent.Signal ->
                        messages.recordReceiptSignal(
                            accountId = event.signal.accountId.value,
                            peerJid = event.signal.peer,
                            senderJid = event.signal.sender,
                            targetId = event.signal.targetId,
                            stage = event.signal.stage,
                        )
                    is org.thanosapollo.nema.session.SessionEvent.OutgoingFailure ->
                        messages.recordProtocolFailure(
                            accountId = event.attempt.accountId.value,
                            generation = event.attempt.generation.value,
                            operationId = event.failure.operationId,
                            peer = event.failure.peer,
                            reason = event.failure.reason,
                        )
                    is org.thanosapollo.nema.session.SessionEvent.RoomUpdated -> {
                        rooms.apply(event.attempt.accountId.value, event.view)
                        persistRoomDisplayName(event.attempt.accountId.value, event.view)
                    }
                    is org.thanosapollo.nema.session.SessionEvent.ConnectionLost -> Unit
                }
            },
            revokeDispatch = outbox::revoke,
        )
        scope.launch {
            controller.lifecycle.collect { observation ->
                runOutboxLifecycleStep(
                    observation = observation,
                    action = {
                        val nextLease = observation.dispatchLease() ?: return@runOutboxLifecycleStep
                        launchDispatch(nextLease, observation)
                    },
                    failed = { failure, _ ->
                        reportOutboxStorageFailure(failure, observation)
                    },
                )
            }
        }
        scope.launch {
            var archiveJob: Job? = null
            controller.lifecycle.collect { observation ->
                archiveJob?.cancelAndJoin()
                archiveJob = null
                archive.disconnected()
                roomArchive.disconnected()
                val lease = observation.dispatchLease() ?: return@collect
                val authority = lookupArchiveAuthority(
                    identity = lease.identity,
                    lookup = { messages.accountBareJid(lease.identity.accountId.value) },
                    failed = { reportArchiveStorageFailure(it, observation) },
                ) ?: return@collect
                archiveJob = scope.launch {
                    try {
                        archive.synchronize(
                            identity = lease.identity,
                            archiveAuthority = authority,
                            isAuthoritative = { controller.lifecycle.value == observation },
                        )
                    } catch (failure: ArchiveStorageFailure) {
                        reportArchiveStorageFailure(failure, observation)
                    }
                }
                scope.launch {
                    restoreBookmarkedRooms(lease.identity, observation)
                }
            }
        }
        scope.launch {
            controller.lifecycle.collect { observation ->
                val lease = observation.dispatchLease() ?: return@collect
                val peers = pendingPeerIdentities.get()[lease.identity.accountId.value].orEmpty()
                if (peers.isEmpty()) return@collect
                try {
                    vcards.ensure(lease.identity.accountId, lease.identity.generation, peers)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Soft failure: keep collector alive for later Connected re-arm.
                }
            }
        }
    }

    suspend fun ensurePeerIdentities(accountId: AccountId, peerJids: Collection<String>) {
        val normalized = peerJids.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (normalized.isEmpty()) return
        pendingPeerIdentities.updateAndGet { previous ->
            val merged = previous[accountId.value].orEmpty() + normalized
            previous + (accountId.value to merged)
        }
        val lease = controller.lifecycle.value.dispatchLease() ?: return
        if (lease.identity.accountId != accountId) return
        vcards.ensure(lease.identity.accountId, lease.identity.generation, normalized)
    }

    suspend fun peerBlockingState(
        identity: SessionIdentity,
        key: DirectConversationKey,
    ): PeerBlockingState {
        val lease = blockingLease(identity, key)
        return controller.peerBlockingState(
            lease.identity.accountId,
            lease.identity.generation,
            key.canonicalBarePeer,
        )
    }

    suspend fun setPeerBlocked(
        identity: SessionIdentity,
        key: DirectConversationKey,
        blocked: Boolean,
    ): PeerBlockingMutationResult {
        val lease = try {
            blockingLease(identity, key)
        } catch (_: SendNotAttemptedException) {
            return PeerBlockingMutationResult.NotAttempted
        }
        return controller.setPeerBlocked(
            lease.identity.accountId,
            lease.identity.generation,
            key.canonicalBarePeer,
            blocked,
        )
    }

    suspend fun uploadHttpFile(request: LocalUploadRequest): UploadedFile? {
        val lease = controller.lifecycle.value.dispatchLease() ?: return null
        return controller.uploadHttpFile(lease.identity.accountId, lease.identity.generation, request)
    }

    suspend fun joinMuc(roomJid: String, nick: String? = null, password: String? = null): Boolean {
        val lease = controller.lifecycle.value.dispatchLease() ?: return false
        val joined = try {
            controller.joinMuc(lease.identity.accountId, lease.identity.generation, roomJid, nick, password)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (joined) {
            val observation = controller.lifecycle.value
            scope.launch {
                try {
                    roomArchive.synchronize(
                        identity = lease.identity,
                        archiveAuthority = roomJid,
                        scope = roomJid,
                        isAuthoritative = {
                            controller.lifecycle.value.dispatchLease()?.identity == lease.identity &&
                                controller.lifecycle.value == observation
                        },
                    )
                } catch (_: ArchiveStorageFailure) {
                    // Soft failure: live room traffic still works.
                }
            }
            scope.launch {
                persistJoinedRoomBookmark(lease, roomJid, nick, password)
            }
        }
        return joined
    }

    private suspend fun persistJoinedRoomBookmark(
        lease: DispatchLease,
        roomJid: String,
        nick: String?,
        password: String?,
    ) = bookmarkMutations.withLock {
        if (controller.lifecycle.value.dispatchLease() != lease) return@withLock
        val snapshot = try {
            controller.bookmarkedRoomDetails(lease.identity.accountId, lease.identity.generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock
        }
        if (!snapshot.complete || controller.lifecycle.value.dispatchLease() != lease) return@withLock
        val existing = snapshot.bookmarks.firstOrNull { it.roomJid == roomJid }
        try {
            controller.publishRoomBookmark(
                lease.identity.accountId,
                lease.identity.generation,
                joinRoomBookmark(roomJid, nick, password, existing),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Joining succeeded; bookmark persistence remains a soft failure.
        }
    }

    private suspend fun persistRoomDisplayName(accountId: String, view: RoomView) {
        peerIdentities.saveRoom(accountId, view.roomJid, true)
        val current = peerIdentities.peer(accountId, view.roomJid)?.displayName
        val fill = roomDisplayNameToPersist(current, view.discoName) ?: return
        peerIdentities.saveDisplayName(accountId, view.roomJid, fill)
    }

    private suspend fun restoreBookmarkedRooms(
        identity: SessionIdentity,
        observation: SessionLifecycleObservation,
    ) {
        val snapshot = try {
            controller.bookmarkedRoomDetails(identity.accountId, identity.generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        for (bookmark in snapshot.bookmarks) {
            if (controller.lifecycle.value != observation) return
            try {
                peerIdentities.saveRoom(identity.accountId.value, bookmark.roomJid, true)
                bookmark.name?.let { name ->
                    peerIdentities.saveDisplayName(identity.accountId.value, bookmark.roomJid, name)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            if (!bookmark.autojoin) continue
            try {
                joinMuc(bookmark.roomJid, bookmark.nick, bookmark.password)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
        }
    }

    private fun blockingLease(identity: SessionIdentity, key: DirectConversationKey): DispatchLease {
        val lease = controller.lifecycle.value.dispatchLease() ?: throw SendNotAttemptedException()
        if (lease.identity != identity || identity.accountId.value != key.accountId) {
            throw SendNotAttemptedException()
        }
        return lease
    }

    internal fun claimAutomaticConnectionStart(): Boolean =
        automaticConnectionClaimed.compareAndSet(false, true)

    internal fun beginPendingActivation(): PendingActivationAuthority.Token = pendingActivation.begin()

    internal fun invalidatePendingActivation() = pendingActivation.invalidate()

    internal suspend fun prepareActivation(
        token: PendingActivationAuthority.Token,
        configuration: AccountConfiguration,
        credential: CharArray,
        emitActivation: (AccountId) -> Unit,
    ): Boolean = try {
        accountCommands.withLock {
            if (!pendingActivation.isCurrent(token)) return@withLock false
            val durableConfiguration = accounts.accountByBareJid(configuration.bareJid)
                ?.let { configuration.copy(id = it.id) }
                ?: configuration
            org.thanosapollo.nema.service.prepareActivation(
                authority = pendingActivation,
                token = token,
                accountId = durableConfiguration.id,
                credential = credential,
                saveAccount = { accounts.save(durableConfiguration) },
                saveCredential = { value ->
                    withContext(Dispatchers.IO) { credentials.store(durableConfiguration.id, value) }
                },
                deleteCredential = { withContext(Dispatchers.IO) { credentials.delete(it) } },
                emitActivation = { emitActivation(durableConfiguration.id) },
            )
        }
    } finally {
        credential.fill('\u0000')
    }

    suspend fun enqueueDirect(account: AccountConfiguration, snapshot: DraftSnapshot): Boolean {
        if (snapshot.key.accountId != account.id.value) return false
        messages.composeDirectDraft(
            accountId = account.id.value,
            operationId = UUID.randomUUID().toString(),
            localMessageId = UUID.randomUUID().toString(),
            originId = UUID.randomUUID().toString(),
            peerJid = snapshot.key.canonicalBarePeer,
            senderJid = account.bareJid.value,
            body = snapshot.body,
            thread = snapshot.outboundThread ?: snapshot.key.thread,
            draftThread = snapshot.key.thread,
            messageKind = if (snapshot.groupChat) MessageKind.GROUPCHAT else MessageKind.CHAT,
            attachmentUrl = snapshot.attachmentUrl,
            attachmentName = snapshot.attachmentName,
            attachmentMime = snapshot.attachmentMime,
            attachmentSize = snapshot.attachmentSize,
            replyToId = snapshot.reply?.id,
            replyToJid = snapshot.reply?.to,
            replyFallbackBody = snapshot.reply?.body,
            replyFallbackSender = snapshot.reply?.senderLabel,
            replaceId = snapshot.correction?.referenceId,
            correctionTargetMessageId = snapshot.correction?.localMessageId,
        ) ?: return false
        val observation = controller.lifecycle.value
        val lease = observation.dispatchLease()
        if (lease?.identity?.accountId == account.id) {
            launchDispatch(lease, observation)
        }
        return true
    }

    suspend fun retryUncertain(account: AccountConfiguration, key: RetryUncertainKey) {
        if (key.accountId != account.id.value || messages.retryUncertain(key) == null) return
        val observation = controller.lifecycle.value
        val lease = observation.dispatchLease()
        if (lease?.identity?.accountId == account.id) {
            launchDispatch(lease, observation)
        }
    }

    private suspend fun launchDispatch(
        lease: DispatchLease,
        observation: SessionLifecycleObservation,
    ) {
        try {
            outbox.launchAuthoritativeDispatch(
                lease = lease,
                isAuthoritative = { controller.lifecycle.value == observation },
                scope = scope,
                onFailure = { failure -> reportOutboxStorageFailure(failure, observation) },
            )
        } catch (failure: OutboxStorageFailure) {
            reportOutboxStorageFailure(failure, observation)
        }
    }

    private suspend fun reportOutboxStorageFailure(
        failure: OutboxStorageFailure,
        observation: SessionLifecycleObservation,
    ) {
        withContext(NonCancellable) {
            controller.outboxStorageFailed(
                AccountId.require(failure.accountId),
                failure.generation,
                observation,
            )
        }
    }

    private suspend fun reportArchiveStorageFailure(
        failure: ArchiveStorageFailure,
        observation: SessionLifecycleObservation,
    ) {
        withContext(NonCancellable) {
            controller.outboxStorageFailed(
                failure.identity.accountId,
                failure.identity.generation,
                observation,
            )
        }
    }

    suspend fun backfillArchive(): Boolean {
        val observation = controller.lifecycle.value
        val identity = observation.dispatchLease()?.identity ?: return false
        val authority = lookupArchiveAuthority(
            identity = identity,
            lookup = { messages.accountBareJid(identity.accountId.value) },
            failed = { reportArchiveStorageFailure(it, observation) },
        ) ?: return false
        return try {
            archive.backfillOnePage(
                identity = identity,
                archiveAuthority = authority,
                isAuthoritative = { controller.lifecycle.value == observation },
            )
        } catch (failure: ArchiveStorageFailure) {
            reportArchiveStorageFailure(failure, observation)
            false
        }
    }

    suspend fun activate(
        accountId: AccountId,
        isCurrent: () -> Boolean = { true },
    ): ConnectionCommandOutcome = accountCommands.withLock {
        activateAccount(accountId, isCurrent)
    }

    private suspend fun activateAccount(
        accountId: AccountId,
        isCurrent: () -> Boolean = { true },
    ): ConnectionCommandOutcome {
        val switchingFrom = accounts.activeAccount.first()?.id ?: accountId
        val configuration = accounts.account(accountId) ?: run {
            if (isCurrent()) controller.requireCredentials()
            return ConnectionCommandOutcome.NEEDS_CREDENTIALS
        }
        if (!isCurrent()) return ConnectionCommandOutcome.STALE
        return withContext(Dispatchers.IO) {
            val access = credentials.load(accountId)
            try {
                if (!isCurrent()) return@withContext ConnectionCommandOutcome.STALE
                if (access !is CredentialAccess.Available) {
                    promoteMissingCredentialActivation(
                        stopSession = controller::stop,
                        promoteActive = { accounts.switchActive(accountId) },
                        requireCredentials = { controller.requireCredentials(accountId) },
                    )
                    return@withContext ConnectionCommandOutcome.NEEDS_CREDENTIALS
                }
                controller.switchTo(
                    configuration = configuration,
                    credential = access.value,
                    switchingFrom = switchingFrom,
                    persistActive = accounts::switchActive,
                )
                outcome()
            } finally {
                if (access is CredentialAccess.Available) access.value.fill('\u0000')
            }
        }
    }

    suspend fun connectActive(
        isCurrent: () -> Boolean = { true },
    ): ConnectionCommandOutcome = accountCommands.withLock {
        connectStoredActive(isCurrent)
    }

    private suspend fun connectStoredActive(
        isCurrent: () -> Boolean = { true },
    ): ConnectionCommandOutcome {
        val active = accounts.activeAccount.first() ?: run {
            if (isCurrent()) controller.requireCredentials()
            return ConnectionCommandOutcome.NEEDS_CREDENTIALS
        }
        if (!isCurrent()) return ConnectionCommandOutcome.STALE
        return withContext(Dispatchers.IO) {
            val access = credentials.load(active.id)
            try {
                if (!isCurrent()) return@withContext ConnectionCommandOutcome.STALE
                if (access !is CredentialAccess.Available) {
                    controller.requireCredentials(active.id)
                    return@withContext ConnectionCommandOutcome.NEEDS_CREDENTIALS
                }
                controller.start(active, access.value)
                outcome()
            } finally {
                if (access is CredentialAccess.Available) access.value.fill('\u0000')
            }
        }
    }

    suspend fun signOut() {
        accountCommands.withLock {
            val active = accounts.activeAccount.first()
            removeActiveAccount(
                accountId = active?.id,
                stopSession = controller::requireCredentials,
                deleteCredential = { withContext(Dispatchers.IO) { credentials.delete(it) } },
                deleteAccount = { accounts.remove(it) },
            )
        }
    }

    suspend fun stop() = accountCommands.withLock { controller.stop() }

    suspend fun markDisplayed(accountId: String, peerJid: String, targetId: String): Boolean {
        if (accountId.isEmpty() || peerJid.isEmpty() || targetId.isEmpty()) return false
        val connected = state.value as? ConnectionState.Connected ?: return false
        if (connected.accountId.value != accountId) return false
        return try {
            controller.sendSignal(
                org.thanosapollo.nema.xmpp.transport.OutgoingMessageSignal(
                    accountId = connected.accountId,
                    generation = connected.generation,
                    recipient = peerJid,
                    targetId = targetId,
                    stage = org.thanosapollo.nema.xmpp.transport.MessageReceiptStage.DISPLAYED,
                    protocol = org.thanosapollo.nema.xmpp.transport.MessageSignalProtocol.CHAT_MARKER,
                ),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun serviceDestroyed() = accountCommands.withLock { controller.serviceDestroyed() }

    private suspend fun acknowledgeReceiptRequest(
        message: org.thanosapollo.nema.xmpp.transport.IncomingMessageEnvelope,
    ) {
        val targetId = message.messageId?.takeIf(String::isNotEmpty) ?: return
        if (message.outbound || message.kind != MessageKind.CHAT || !message.receiptRequested) return
        try {
            controller.sendSignal(
                org.thanosapollo.nema.xmpp.transport.OutgoingMessageSignal(
                    accountId = message.accountId,
                    generation = message.generation,
                    recipient = message.peer,
                    targetId = targetId,
                    stage = org.thanosapollo.nema.xmpp.transport.MessageReceiptStage.RECEIVED,
                    protocol = org.thanosapollo.nema.xmpp.transport.MessageSignalProtocol.DELIVERY_RECEIPT,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The message is durable even when this best-effort protocol acknowledgement cannot be sent.
        }
    }

    private fun outcome(): ConnectionCommandOutcome = when (state.value) {
        is ConnectionState.NeedsCredentials -> ConnectionCommandOutcome.NEEDS_CREDENTIALS
        is ConnectionState.Failed -> ConnectionCommandOutcome.TERMINAL
        else -> ConnectionCommandOutcome.RUNNING
    }
}

internal suspend fun lookupArchiveAuthority(
    identity: SessionIdentity,
    lookup: suspend () -> String?,
    failed: suspend (ArchiveStorageFailure) -> Unit,
): String? = try {
    lookup()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    failed(ArchiveStorageFailure(identity, failure))
    null
}

internal suspend fun runOutboxLifecycleStep(
    observation: SessionLifecycleObservation,
    action: suspend () -> Unit,
    failed: suspend (OutboxStorageFailure, SessionLifecycleObservation) -> Unit,
) {
    try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: OutboxStorageFailure) {
        withContext(NonCancellable) { failed(failure, observation) }
    } catch (failure: Exception) {
        val owner = observation.dispatchLease()?.identity ?: throw failure
        withContext(NonCancellable) {
            failed(
                OutboxStorageFailure(owner.accountId.value, owner.generation, failure),
                observation,
            )
        }
    }
}

internal suspend fun removeActiveAccount(
    accountId: AccountId?,
    stopSession: suspend () -> Unit,
    deleteCredential: suspend (AccountId) -> Unit,
    deleteAccount: suspend (AccountId) -> Unit,
) {
    stopSession()
    withContext(NonCancellable) {
        accountId?.let {
            deleteAccount(it)
            deleteCredential(it)
        }
    }
}

internal suspend fun promoteMissingCredentialActivation(
    stopSession: suspend () -> Unit,
    promoteActive: suspend () -> Unit,
    requireCredentials: suspend () -> Unit,
) {
    stopSession()
    withContext(NonCancellable) {
        promoteActive()
    }
    currentCoroutineContext().ensureActive()
    requireCredentials()
}

internal class SerializedServiceCommandRunner(private val scope: CoroutineScope) {
    @Volatile
    private var latestId = 0
    private var active: Job? = null

    @Synchronized
    fun submit(startId: Int, block: suspend (() -> Boolean) -> Unit) {
        latestId = startId
        val predecessor = active
        val next = scope.launch(start = CoroutineStart.LAZY) {
            predecessor?.cancelAndJoin()
            if (latestId == startId) block { latestId == startId }
        }
        active = next
        next.start()
    }

    @Synchronized
    fun cancelCurrent() {
        latestId += 1
        active?.cancel()
    }
}

internal suspend fun runCurrentServiceCommand(
    current: () -> Boolean,
    command: suspend () -> Unit,
    failCurrent: suspend () -> Unit,
) {
    try {
        command()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (current()) failCurrent()
    }
}

internal suspend fun failCurrentServiceCommand(
    current: () -> Boolean,
    stopRuntime: suspend () -> Unit,
    removeForeground: () -> Unit,
    stopService: () -> Unit,
) {
    if (!current()) return
    try {
        stopRuntime()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Unit
    }
    if (!current()) return
    runCatching { removeForeground() }
    if (current()) runCatching { stopService() }
}

internal object NotificationVisibilityPolicy {
    fun allowed(permissionGranted: Boolean, notificationsEnabled: Boolean, channelImportance: Int): Boolean =
        permissionGranted && notificationsEnabled && channelImportance != NotificationManager.IMPORTANCE_NONE
}

internal fun commandRequiresForegroundVisibility(action: String): Boolean =
    action != XmppConnectionService.ACTION_STOP && action != XmppConnectionService.ACTION_SIGN_OUT

internal class ForegroundSessionOwner {
    private var startId: Int? = null

    fun invalidate() {
        startId = null
    }

    fun activate(startId: Int, current: () -> Boolean) {
        if (current()) this.startId = startId
    }

    fun claimTerminal(observed: ConnectionState, current: ConnectionState): Int? {
        if (observed !is ConnectionState.Failed || observed != current) return null
        return startId.also { startId = null }
    }
}

internal class VisibilityShutdownAuthority {
    private var generation = 0L
    private var active: Token? = null

    fun supersede() {
        generation++
        active = null
    }

    fun begin(ownerStartId: Int): Token? {
        if (active != null) return null
        return Token(++generation, ownerStartId).also { active = it }
    }

    fun isCurrent(token: Token): Boolean = active == token && token.generation == generation

    internal data class Token(val generation: Long, val ownerStartId: Int)
}

internal suspend fun failClosedForeground(
    stopRuntime: suspend () -> Unit,
    removeForeground: () -> Unit,
    stopService: () -> Unit,
) {
    runCatching { stopRuntime() }
    runCatching { removeForeground() }
    runCatching { stopService() }
}

internal suspend fun stopServiceRuntime(
    cancelCommands: () -> Unit,
    stopRuntime: suspend () -> Unit,
) {
    cancelCommands()
    stopRuntime()
}

class XmppConnectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionOwner = ForegroundSessionOwner()
    private val visibilityShutdown = VisibilityShutdownAuthority()
    private lateinit var runtime: SessionRuntime
    private lateinit var notifications: NotificationManager
    private lateinit var commands: SerializedServiceCommandRunner
    private var notificationChannelReady = false
    private var foregroundRequired = false
    private var latestStartId = 0
    private var stateJob: Job? = null
    private var visibilityJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        runtime = (application as NemaApplication).sessionRuntime
        notifications = getSystemService(NotificationManager::class.java)
        commands = SerializedServiceCommandRunner(serviceScope)
        try {
            notifications.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.connection_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.connection_channel_description)
                    setShowBadge(false)
                },
            )
            notificationChannelReady = true
        } catch (_: RuntimeException) {
            notificationChannelReady = false
        }
        stateJob = serviceScope.launch {
            runtime.state.collect { state ->
                sessionOwner.claimTerminal(state, runtime.state.value)?.let { startId ->
                    stopAfterTerminal(startId)
                    return@collect
                }
                if (!foregroundRequired) return@collect
                if (!canShowNotifications()) {
                    shutdownForVisibility()
                    return@collect
                }
                try {
                    notifications.notify(NOTIFICATION_ID, notification(privacySafeStatus(state)))
                } catch (_: RuntimeException) {
                    shutdownForVisibility()
                }
            }
        }
        visibilityJob = serviceScope.launch {
            while (isActive) {
                delay(VISIBILITY_CHECK_MILLIS)
                if (foregroundRequired && !canShowNotifications()) shutdownForVisibility()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        visibilityShutdown.supersede()
        latestStartId = startId
        val action = intent?.action ?: ACTION_CONNECT
        val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID)
        foregroundRequired = commandRequiresForegroundVisibility(action)
        if (!foregroundRequired) {
            runtime.invalidatePendingActivation()
        }
        sessionOwner.invalidate()
        if (foregroundRequired) {
            if (!notificationChannelReady || !canShowNotifications()) {
                shutdownForVisibility()
                return START_NOT_STICKY
            }
            try {
                startForeground(NOTIFICATION_ID, notification(getString(R.string.connection_starting)))
            } catch (_: RuntimeException) {
                shutdownForVisibility()
                return START_NOT_STICKY
            }
        }
        commands.submit(startId) { current ->
            runCurrentServiceCommand(
                current = current,
                command = {
                    when (action) {
                        ACTION_ACTIVATE -> {
                            val id = accountId?.let { runCatching { AccountId.require(it) }.getOrNull() }
                            val outcome = if (id == null) {
                                runtime.stop()
                                ConnectionCommandOutcome.TERMINAL
                            } else {
                                runtime.activate(id, current)
                            }
                            finishOutcome(outcome, startId, current)
                        }
                        ACTION_CONNECT -> finishOutcome(runtime.connectActive(current), startId, current)
                        ACTION_STOP -> {
                            runtime.stop()
                            stopAfterCommand(startId, current)
                        }
                        ACTION_SIGN_OUT -> {
                            runtime.signOut()
                            stopAfterCommand(startId, current)
                        }
                        else -> {
                            runtime.stop()
                            stopAfterCommand(startId, current)
                        }
                    }
                },
                failCurrent = { failCurrentCommand(startId, current) },
            )
        }
        return if (action == ACTION_STOP || action == ACTION_SIGN_OUT) START_NOT_STICKY else START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        visibilityJob?.cancel()
        visibilityShutdown.supersede()
        sessionOwner.invalidate()
        runBlocking(Dispatchers.IO) {
            stopServiceRuntime(commands::cancelCurrent, runtime::serviceDestroyed)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun finishOutcome(
        outcome: ConnectionCommandOutcome,
        startId: Int,
        current: () -> Boolean,
    ) {
        when (outcome) {
            ConnectionCommandOutcome.RUNNING -> sessionOwner.activate(startId, current)
            ConnectionCommandOutcome.NEEDS_CREDENTIALS,
            ConnectionCommandOutcome.TERMINAL,
            -> stopAfterCommand(startId, current)
            ConnectionCommandOutcome.STALE -> Unit
        }
    }

    private suspend fun stopAfterCommand(startId: Int, current: () -> Boolean) {
        if (!current()) return
        foregroundRequired = false
        sessionOwner.invalidate()
        failClosedForeground(
            stopRuntime = {},
            removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
            stopService = { stopSelf(startId) },
        )
    }

    private suspend fun stopAfterTerminal(startId: Int) {
        foregroundRequired = false
        failClosedForeground(
            stopRuntime = {},
            removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
            stopService = { stopSelf(startId) },
        )
    }

    private suspend fun failCurrentCommand(startId: Int, current: () -> Boolean) {
        if (!current()) return
        foregroundRequired = false
        sessionOwner.invalidate()
        failCurrentServiceCommand(
            current = current,
            stopRuntime = runtime::stop,
            removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
            stopService = { stopSelf(startId) },
        )
    }

    private fun shutdownForVisibility() {
        val token = visibilityShutdown.begin(latestStartId) ?: return
        foregroundRequired = false
        sessionOwner.invalidate()
        commands.cancelCurrent()
        serviceScope.launch {
            failCurrentServiceCommand(
                current = { visibilityShutdown.isCurrent(token) },
                stopRuntime = runtime::stop,
                removeForeground = { stopForeground(STOP_FOREGROUND_REMOVE) },
                stopService = { stopSelf(token.ownerStartId) },
            )
        }
    }

    private fun canShowNotifications(): Boolean = try {
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val importance = notifications.getNotificationChannel(CHANNEL_ID)?.importance
            ?: NotificationManager.IMPORTANCE_NONE
        NotificationVisibilityPolicy.allowed(permissionGranted, notifications.areNotificationsEnabled(), importance)
    } catch (_: RuntimeException) {
        false
    }

    private fun notification(status: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, XmppConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nema_mark)
            .setContentTitle(getString(R.string.connection_notification_title))
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_connection), stopIntent).build())
            .build()
    }

    companion object {
        const val ACTION_ACTIVATE = "org.thanosapollo.nema.action.ACTIVATE_ACCOUNT"
        const val ACTION_CONNECT = "org.thanosapollo.nema.action.CONNECT"
        const val ACTION_STOP = "org.thanosapollo.nema.action.STOP"
        const val ACTION_SIGN_OUT = "org.thanosapollo.nema.action.SIGN_OUT"
        const val EXTRA_ACCOUNT_ID = "account_id"
        private const val CHANNEL_ID = "xmpp_connection"
        private const val NOTIFICATION_ID = 1001
        private const val VISIBILITY_CHECK_MILLIS = 1_000L

        fun activateIntent(context: Context, accountId: AccountId) =
            Intent(context, XmppConnectionService::class.java)
                .setAction(ACTION_ACTIVATE)
                .putExtra(EXTRA_ACCOUNT_ID, accountId.value)

        fun actionIntent(context: Context, action: String) =
            Intent(context, XmppConnectionService::class.java).setAction(action)
    }
}
