package org.thanosapollo.nema.chat

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.thanosapollo.nema.session.DispatchRevocationResult
import org.thanosapollo.nema.session.DispatchLease
import org.thanosapollo.nema.session.RevokedDispatch
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.OutboxClaim
import org.thanosapollo.nema.storage.PendingOutbound
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.ConnectionGeneration
import org.thanosapollo.nema.xmpp.transport.MessageReplyEnvelope
import org.thanosapollo.nema.xmpp.transport.OutgoingMessageEnvelope

internal class OutboxStorageFailure(
    val accountId: String,
    val generation: ConnectionGeneration,
    cause: Exception,
) : Exception(cause)

internal class OutboxDispatcher(
    private val store: MessageStore,
    private val pendingOutbound: suspend (String) -> List<PendingOutbound> = store::pendingOutbound,
    private val accountBareJid: suspend (String) -> String? = store::accountBareJid,
    private val claimOutbound: suspend (String, String, Long) -> OutboxClaim? = store::claim,
    private val recordPending: suspend (OutboxClaim) -> Unit = {
        store.recordDefinitePreHandoffFailure(it)
        Unit
    },
    private val recordUncertain: suspend (OutboxClaim) -> Unit = {
        store.recordPotentialDelivery(it)
        Unit
    },
    private val send: suspend (OutgoingMessageEnvelope, entered: () -> Unit) -> Unit,
) {
    private data class GenerationKey(val accountId: String, val generation: ConnectionGeneration)
    private data class ClaimKey(
        val lease: DispatchLease,
        val accountId: String,
        val operationId: String,
        val generation: Long,
        val attempt: Int,
    )
    private enum class Settlement { PENDING, UNCERTAIN }
    private data class ActiveClaim(
        val lease: DispatchLease,
        val claim: OutboxClaim,
        val entered: AtomicBoolean = AtomicBoolean(false),
        val settlement: AtomicReference<Settlement?> = AtomicReference(null),
        val settlementMutex: Mutex = Mutex(),
    )
    private data class LeaseState(
        val lease: DispatchLease,
        val dispatchMutex: Mutex = Mutex(),
        val jobs: MutableSet<Job> = mutableSetOf(),
    )
    private val stateLock = Any()
    private val activeClaims = mutableMapOf<ClaimKey, ActiveClaim>()
    private val revokedDispatches = mutableMapOf<DispatchLease, RetainedDispatch>()
    private val poisonedGenerations = mutableMapOf<GenerationKey, OutboxStorageFailure>()
    private var ready: LeaseState? = null

    suspend fun lifecycleChanged(
        nextLease: DispatchLease?,
        isAuthoritative: () -> Boolean,
    ): DispatchLease? {
        if (nextLease == null) return null
        return synchronized(stateLock) {
            if (!isAuthoritative()) return null
            val current = ready
            if (current != null && current.lease == nextLease) return nextLease
            if (current != null && nextLease.isOlderThan(current.lease)) return null
            check(current == null) { "Controller must revoke the current dispatch lease before replacement" }
            nextLease.also { ready = LeaseState(it) }
        }
    }

    fun revoke(lease: DispatchLease): RevokedDispatch? = synchronized(stateLock) {
        revokedDispatches[lease]?.let { return it }
        val current = ready?.takeIf { it.lease == lease } ?: return null
        ready = null
        val retained = RetainedDispatch(lease, current.jobs.toList())
        activeClaims.entries.removeAll { (key, active) ->
            if (active.lease == lease) {
                retained.claims[key] = active
                true
            } else {
                false
            }
        }
        revokedDispatches[lease] = retained
        retained.jobs.forEach(Job::cancel)
        retained
    }

    suspend fun launchDispatch(
        lease: DispatchLease,
        scope: CoroutineScope,
        onFailure: suspend (OutboxStorageFailure) -> Unit,
    ): Job? {
        lateinit var job: Job
        synchronized(stateLock) {
            val current = ready?.takeIf { it.lease == lease } ?: return null
            poisonedGenerations[lease.generationKey()]?.let { throw it }
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    dispatch(current)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: OutboxStorageFailure) {
                    withContext(NonCancellable) { onFailure(failure) }
                } finally {
                    withContext(NonCancellable) {
                        synchronized(stateLock) { current.jobs.remove(job) }
                    }
                }
            }
            current.also { it.jobs += job }
        }
        job.start()
        return job
    }

    suspend fun launchAuthoritativeDispatch(
        lease: DispatchLease,
        isAuthoritative: () -> Boolean,
        scope: CoroutineScope,
        onFailure: suspend (OutboxStorageFailure) -> Unit,
    ): Job? {
        val activeLease = lifecycleChanged(lease, isAuthoritative) ?: return null
        return launchDispatch(activeLease, scope, onFailure)
    }

    private suspend fun dispatch(state: LeaseState) {
        state.dispatchMutex.withLock {
            val lease = state.lease
            if (!isCurrent(lease)) return
            poisoned(lease)?.let { throw it }
            val pendingRows = storage(lease) { pendingOutbound(lease.identity.accountId.value) }
            if (!isCurrent(lease)) return

            for (pending in pendingRows) {
                if (!isCurrent(lease)) return
                poisoned(lease)?.let { throw it }
                storage(lease) {
                    check(accountBareJid(lease.identity.accountId.value) == pending.senderJid) {
                        "Outbound sender does not match account binding"
                    }
                }
                val active = withContext(NonCancellable) {
                    if (!isCurrent(lease)) return@withContext null
                    val claim = storage(lease) {
                        claimOutbound(
                            lease.identity.accountId.value,
                            pending.operationId,
                            lease.identity.generation.value,
                        )
                    } ?: return@withContext null
                    ActiveClaim(lease, claim).also { claimed ->
                        val key = claimed.key()
                        synchronized(stateLock) {
                            val retained = revokedDispatches[lease]
                            if (retained == null) {
                                check(activeClaims.put(key, claimed) == null) { "Duplicate exact outbox claim" }
                            } else {
                                check(retained.claims.put(key, claimed) == null) { "Duplicate retained outbox claim" }
                            }
                        }
                    }
                } ?: continue
                val key = active.key()
                if (!isCurrent(lease)) {
                    complete(key, active, entered = false)
                    return
                }

                try {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(lease)) {
                        complete(key, active, entered = false)
                        return
                    }
                    val envelope = OutgoingMessageEnvelope(
                        accountId = AccountId.require(active.claim.accountId),
                        generation = ConnectionGeneration.require(active.claim.generation),
                        attempt = active.claim.attempt,
                        operationId = pending.operationId,
                        originId = pending.originId,
                        recipient = pending.peerJid,
                        body = pending.body,
                        thread = pending.threadId?.let {
                            ThreadRef(
                                id = ThreadId.require(it),
                                parentId = pending.parentThreadId?.let(ThreadId::require),
                            )
                        },
                        kind = pending.messageKind,
                        attachmentUrl = pending.attachmentUrl,
                        attachmentName = pending.attachmentName,
                        attachmentMime = pending.attachmentMime,
                        attachmentSize = pending.attachmentSize,
                        reply = pending.replyToId?.let { id ->
                            MessageReplyEnvelope(
                                id = id,
                                to = pending.replyToJid,
                                fallbackBody = pending.replyFallbackBody,
                                fallbackSender = pending.replyToJid?.replySenderLabel(),
                            )
                        },
                    )
                    send(envelope) { active.entered.set(true) }
                } catch (cancelled: CancellationException) {
                    complete(key, active, active.entered.get())
                    throw cancelled
                } catch (_: Exception) {
                    complete(key, active, active.entered.get())
                    continue
                }
                complete(key, active, active.entered.get())
            }
        }
    }

    private suspend fun complete(key: ClaimKey, active: ActiveClaim, entered: Boolean) {
        active.settlement.compareAndSet(
            null,
            if (entered) Settlement.UNCERTAIN else Settlement.PENDING,
        )
        if (synchronized(stateLock) { revokedDispatches[active.lease]?.claims?.get(key) === active }) return
        settle(key, active, retained = false)
    }

    private suspend fun settle(key: ClaimKey, active: ActiveClaim, retained: Boolean) {
        withContext(NonCancellable) {
            active.settlementMutex.withLock {
                if (key != active.key()) return@withLock
                val isRetained = synchronized(stateLock) {
                    when {
                        activeClaims[key] === active -> false
                        revokedDispatches[active.lease]?.claims?.get(key) === active -> true
                        else -> return@withLock
                    }
                }
                if (isRetained && !retained) return@withLock
                if (!isRetained) poisoned(active.lease)?.let { throw it }
                val settlement = requireNotNull(active.settlement.get())
                storage(active.lease) {
                    when (settlement) {
                        Settlement.PENDING -> recordPending(active.claim)
                        Settlement.UNCERTAIN -> recordUncertain(active.claim)
                    }
                }
                synchronized(stateLock) {
                    activeClaims.remove(key, active)
                    revokedDispatches[active.lease]?.claims?.remove(key, active)
                }
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private inner class RetainedDispatch(
        val lease: DispatchLease,
        val jobs: List<Job>,
        val claims: MutableMap<ClaimKey, ActiveClaim> = linkedMapOf(),
    ) : RevokedDispatch {
        override fun freezeUnknownEntry() {
            synchronized(stateLock) {
                claims.values.forEach { it.settlement.compareAndSet(null, Settlement.UNCERTAIN) }
            }
        }

        override suspend fun finish(): DispatchRevocationResult {
            jobs.joinAll()
            val retained = synchronized(stateLock) { claims.toList() }
            for ((key, active) in retained) {
                active.settlement.compareAndSet(
                    null,
                    if (active.entered.get()) Settlement.UNCERTAIN else Settlement.PENDING,
                )
                try {
                    settle(key, active, retained = true)
                } catch (_: OutboxStorageFailure) {
                    return DispatchRevocationResult.LOCAL_STORAGE_FAILED
                }
            }
            synchronized(stateLock) {
                if (claims.isEmpty()) revokedDispatches.remove(lease, this)
            }
            return DispatchRevocationResult.COMPLETE
        }
    }

    private suspend fun <T> storage(lease: DispatchLease, block: suspend () -> T): T {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val generation = lease.generationKey()
            val typed = OutboxStorageFailure(generation.accountId, generation.generation, failure)
            throw synchronized(stateLock) { poisonedGenerations.getOrPut(generation) { typed } }
        }
    }

    private fun poisoned(lease: DispatchLease): OutboxStorageFailure? =
        synchronized(stateLock) { poisonedGenerations[lease.generationKey()] }

    private fun isCurrent(lease: DispatchLease): Boolean =
        synchronized(stateLock) { ready?.lease == lease }

    private fun DispatchLease.generationKey() = GenerationKey(identity.accountId.value, identity.generation)

    private fun ActiveClaim.key() = ClaimKey(
        lease = lease,
        accountId = claim.accountId,
        operationId = claim.operationId,
        generation = claim.generation,
        attempt = claim.attempt,
    )

    private fun DispatchLease.isOlderThan(other: DispatchLease): Boolean =
        epoch.value < other.epoch.value ||
            (epoch == other.epoch &&
                identity.accountId == other.identity.accountId &&
                identity.generation.value < other.identity.generation.value)
}

private fun String.replySenderLabel(): String =
    substringAfterLast('/').takeUnless { it == this } ?: substringBefore('@')