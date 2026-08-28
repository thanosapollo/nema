package org.thanosapollo.nema.xmpp.smack

import kotlinx.coroutines.CancellationException

internal class RoomViewHandoff<S, P, U, V>(
    private val entryGate: Any,
    private val registry: RoomStableIdAuthorityRegistry,
) {
    private val ordering = mutableMapOf<String, Any>()
    private val active = mutableMapOf<String, Active<S, P, U>>()

    inner class Candidate internal constructor(
        private val lease: RoomStableIdLease,
        private val mucMonitor: Any,
        private val joined: () -> Boolean,
        private val listeners: Listeners<S, P, U>,
        private val buildView: () -> V,
        private val deliver: (V) -> Unit,
        private val stableIds: Boolean,
        private val occupantIds: Boolean,
        private val ownNick: String?,
    ) {
        private val roomOrder = synchronized(ordering) {
            ordering.getOrPut(lease.authority) { Any() }
        }

        fun activate(): Boolean = locked {
            val view = try {
                buildView()
            } catch (failure: Throwable) {
                revokePending()
                throw failure
            }
            val triple = try {
                install()
            } catch (failure: Throwable) {
                revokePending()
                throw failure
            } ?: return@locked revokePending()
            val previous = try {
                synchronized(entryGate) {
                    if (!joined() || !registry.publish(lease, stableIds, occupantIds, ownNick)) null
                    else active.put(lease.authority, Active(lease, triple))
                }
            } catch (failure: Throwable) {
                revokePending()
                triple.cleanup()
                throw failure
            }
            if (!isActive(triple)) {
                synchronized(entryGate) { registry.revoke(lease) }
                triple.cleanup()
                return@locked false
            }
            previous?.triple?.cleanup()
            try {
                deliver(view)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {}
            true
        }

        fun isActive(): Boolean = synchronized(entryGate) { current() }

        private fun install(): RoomListenerTriple<S, P, U>? = RoomListenerTriple.install(
            status = listeners.status({ refresh() }, { revokeCurrent() }),
            participant = listeners.participant { refresh() },
            subject = listeners.subject { refresh() },
            addStatus = listeners.addStatus,
            addParticipant = listeners.addParticipant,
            addSubject = listeners.addSubject,
            removeStatus = listeners.removeStatus,
            removeParticipant = listeners.removeParticipant,
            removeSubject = listeners.removeSubject,
        )

        private fun refresh() = locked {
            if (synchronized(entryGate) { current() }) deliver(buildView())
        }

        private fun revokeCurrent() = locked {
            val removed = synchronized(entryGate) {
                if (!current() || !registry.revoke(lease)) null
                else active.remove(lease.authority)
            }
            removed?.triple?.cleanup()
        }

        private fun current(): Boolean =
            active[lease.authority]?.lease == lease &&
                registry.snapshot(lease.attempt, lease.authority)?.lease == lease

        private fun isActive(triple: RoomListenerTriple<S, P, U>): Boolean =
            synchronized(entryGate) { active[lease.authority]?.triple === triple }

        private fun revokePending(): Boolean {
            synchronized(entryGate) { registry.revoke(lease) }
            return false
        }

        private inline fun <T> locked(block: () -> T): T =
            synchronized(mucMonitor) { synchronized(roomOrder) { block() } }
    }

    fun candidate(
        lease: RoomStableIdLease,
        mucMonitor: Any,
        joined: () -> Boolean,
        listeners: Listeners<S, P, U>,
        buildView: () -> V,
        deliver: (V) -> Unit,
        stableIds: Boolean,
        occupantIds: Boolean,
        ownNick: String?,
    ) = Candidate(lease, mucMonitor, joined, listeners, buildView, deliver, stableIds, occupantIds, ownNick)

    fun beginAttempt(attempt: org.thanosapollo.nema.session.SessionAttemptIdentity) =
        beginAttemptIf(attempt) { true }

    fun beginAttemptIf(
        attempt: org.thanosapollo.nema.session.SessionAttemptIdentity,
        entered: () -> Boolean,
    ): Boolean {
        val retired = synchronized(entryGate) {
            if (!entered()) return@synchronized null
            registry.begin(attempt)
            active.values.toList().also { active.clear() }
        } ?: return false
        retired.forEach { it.triple.cleanup() }
        return true
    }

    fun retireAll() = retireAllIf { true }

    fun retireAllIf(entered: () -> Boolean): Boolean {
        val retired = synchronized(entryGate) {
            if (!entered()) return@synchronized null
            registry.retireAll()
            active.values.toList().also { active.clear() }
        } ?: return false
        retired.forEach { it.triple.cleanup() }
        return true
    }

    private fun reset(resetRegistry: () -> Unit) {
        val retired = synchronized(entryGate) {
            resetRegistry()
            active.values.toList().also { active.clear() }
        }
        retired.forEach { it.triple.cleanup() }
    }

    data class Listeners<S, P, U>(
        val status: (() -> Unit, () -> Unit) -> S,
        val participant: (() -> Unit) -> P,
        val subject: (() -> Unit) -> U,
        val addStatus: (S) -> Boolean,
        val addParticipant: (P) -> Boolean,
        val addSubject: (U) -> Boolean,
        val removeStatus: (S) -> Unit,
        val removeParticipant: (P) -> Unit,
        val removeSubject: (U) -> Unit,
    )

    private data class Active<S, P, U>(val lease: RoomStableIdLease, val triple: RoomListenerTriple<S, P, U>)
}
