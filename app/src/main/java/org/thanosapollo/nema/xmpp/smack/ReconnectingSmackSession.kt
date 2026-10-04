package org.thanosapollo.nema.xmpp.smack

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.xmpp.bookmarks.RoomBookmark
import org.thanosapollo.nema.xmpp.httpupload.LocalUploadRequest
import org.thanosapollo.nema.xmpp.threads.*
import org.thanosapollo.nema.xmpp.transport.*

/** Native error handlers and deferred disconnects own a connection, not a reconnect generation. */
internal class ReconnectingSmackSession(
    private val identity: SessionIdentity,
    private val event: (SessionEvent) -> Unit,
    private val credentialLoader: suspend (SessionAttemptIdentity) -> CharArray?,
    private val create: (SessionIdentity, (SessionEvent) -> Unit) -> SmackSessionConnection,
) : SessionConnection {
    private data class Physical(val token: Any, val connection: SmackSessionConnection, val attempt: SessionAttemptIdentity? = null)
    private val gate = Any()
    private val establishing = Mutex()
    private var revoked = false
    private var requested: SessionAttemptIdentity? = null
    private var claimed: SessionAttemptIdentity? = null
    private var started = false
    private var current: Physical? = null

    init { current = createPhysical(identity) }

    private fun createPhysical(owner: SessionIdentity): Physical {
        val token = Any()
        val connection = create(owner) { value ->
            val admitted = synchronized(gate) {
                !revoked && requested != null && current?.token === token && current?.attempt == requested
            }
            // The physical callback retains its original attempt; the controller also admits that identity.
            if (admitted) event(value)
        }
        return Physical(token, connection)
    }

    private fun activeLocked(): SmackSessionConnection? = current?.connection?.takeIf {
        !revoked && requested != null && current?.attempt == requested
    }
    private fun active(): SmackSessionConnection = synchronized(gate) {
        activeLocked() ?: throw SendNotAttemptedException()
    }
    override val isUsable: Boolean get() = synchronized(gate) { activeLocked()?.isUsable == true }
    override val onionWithoutTls: Boolean get() = synchronized(gate) { activeLocked()?.onionWithoutTls == true }

    override fun updateAttempt(attempt: SessionAttemptIdentity) {
        val old = synchronized(gate) {
            if (revoked) return
            require(attempt.accountId == identity.accountId && attempt.generation.value >= identity.generation.value)
            val previous = requested
            if (previous == attempt) return
            if (previous != null) require(attempt.epoch == previous.epoch &&
                attempt.attempt.value > previous.attempt.value && attempt.generation.value >= previous.generation.value)
            requested = attempt
            current?.takeIf { it.attempt != null }?.connection
        }
        old?.revoke()
    }

    override fun revoke() {
        val old = synchronized(gate) {
            revoked = true
            current?.connection
        }
        old?.revoke()
    }

    override suspend fun connect(credential: CharArray, attempt: SessionAttemptIdentity) {
        updateAttempt(attempt)
        synchronized(gate) {
            if (revoked) throw CancellationException("Session revoked")
            check(!started) { "Session already started" }
            started = true
        }
        establish(attempt, credential)
    }

    override suspend fun reconnect(attempt: SessionAttemptIdentity) {
        updateAttempt(attempt)
        establish(attempt)
    }

    private suspend fun establish(attempt: SessionAttemptIdentity, supplied: CharArray? = null) = establishing.withLock {
        val old = synchronized(gate) {
            if (revoked || requested != attempt) throw CancellationException("Session attempt replaced")
            check(claimed != attempt) { "Physical attempt already used" }
            check(started) { "Session not started" }
            claimed = attempt
            requireNotNull(current)
        }
        var candidate: Physical? = null
        var password: CharArray? = null
        try {
            candidate = if (old.attempt == null) old.copy(attempt = attempt) else {
                old.connection.revoke()
                old.connection.disconnect()
                synchronized(gate) {
                    if (revoked || requested != attempt) throw CancellationException("Session attempt replaced")
                }
                createPhysical(SessionIdentity(attempt.accountId, attempt.generation)).copy(attempt = attempt)
            }
            val selected = requireNotNull(candidate)
            synchronized(gate) {
                if (revoked || requested != attempt) throw CancellationException("Session attempt replaced")
                current = selected
            }
            // Assign before dispatcher return so prompt cancellation cannot discard the owned buffer.
            withContext(Dispatchers.IO) {
                synchronized(gate) {
                    if (revoked || requested != attempt) throw CancellationException("Session attempt replaced")
                }
                password = supplied?.copyOf() ?: try {
                    credentialLoader(attempt)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    throw SessionFailure(SessionFailureReason.AUTHENTICATION)
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(gate) {
                if (revoked || requested != attempt) throw CancellationException("Session attempt replaced")
            }
            selected.connection.connect(password ?: throw SessionFailure(SessionFailureReason.AUTHENTICATION), attempt)
            synchronized(gate) {
                if (revoked || requested != attempt) throw CancellationException("Session attempt replaced")
            }
        } catch (failure: Throwable) {
            candidate?.connection?.revoke()
            withContext(NonCancellable) { candidate?.connection?.disconnect() }
            throw failure
        } finally {
            password?.fill('\u0000')
        }
    }

    override suspend fun disconnect() {
        revoke()
        val old = synchronized(gate) { current }
        try { old?.connection?.disconnect() } finally {
            synchronized(gate) { if (current?.token === old?.token) current = null }
        }
    }

    override suspend fun send(message: OutgoingMessageEnvelope, entered: () -> Unit) = active().send(message, entered)
    override suspend fun sendSignal(signal: OutgoingMessageSignal) = active().sendSignal(signal)
    override suspend fun sendReaction(reaction: OutgoingReactionEnvelope) = active().sendReaction(reaction)
    override suspend fun sendChatState(state: OutgoingChatState) = active().sendChatState(state)
    override suspend fun discoverCapabilities(accountId: AccountId, generation: ConnectionGeneration) = active().discoverCapabilities(accountId, generation)
    override suspend fun queryArchive(request: ArchivePageRequest) = active().queryArchive(request)
    override suspend fun queryArchive(request: ArchivePageRequest, authorization: RoomArchiveAuthorization) = active().queryArchive(request, authorization)
    override suspend fun loadVCard(accountId: AccountId, generation: ConnectionGeneration, bareJid: String) = active().loadVCard(accountId, generation, bareJid)
    override suspend fun peerBlockingState(accountId: AccountId, generation: ConnectionGeneration, bareJid: String) = active().peerBlockingState(accountId, generation, bareJid)
    override suspend fun setPeerBlocked(accountId: AccountId, generation: ConnectionGeneration, bareJid: String, blocked: Boolean, entered: () -> Unit) = active().setPeerBlocked(accountId, generation, bareJid, blocked, entered)
    override suspend fun fetchHttpFile(accountId: AccountId, generation: ConnectionGeneration, url: String) = active().fetchHttpFile(accountId, generation, url)
    override suspend fun uploadHttpFile(accountId: AccountId, generation: ConnectionGeneration, request: LocalUploadRequest) = active().uploadHttpFile(accountId, generation, request)
    override fun roomArchiveAuthorization(room: String) = synchronized(gate) { activeLocked() }?.roomArchiveAuthorization(room)
    override suspend fun joinMuc(accountId: AccountId, generation: ConnectionGeneration, roomJid: String, nick: String?, password: String?) = active().joinMuc(accountId, generation, roomJid, nick, password)
    override suspend fun bookmarkedRooms(accountId: AccountId, generation: ConnectionGeneration) = active().bookmarkedRooms(accountId, generation)
    override suspend fun bookmarkedRoomDetails(accountId: AccountId, generation: ConnectionGeneration) = active().bookmarkedRoomDetails(accountId, generation)
    override suspend fun publishRoomBookmark(accountId: AccountId, generation: ConnectionGeneration, bookmark: RoomBookmark) = active().publishRoomBookmark(accountId, generation, bookmark)
    override suspend fun listThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, directory: ThreadDirectoryScope) = active().listThreadDirectory(accountId, generation, directory)
    override suspend fun mutateThreadDirectory(accountId: AccountId, generation: ConnectionGeneration, action: DirectoryAction) = active().mutateThreadDirectory(accountId, generation, action)
}
