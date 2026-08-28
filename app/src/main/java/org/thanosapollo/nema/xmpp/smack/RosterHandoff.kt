package org.thanosapollo.nema.xmpp.smack
import java.util.concurrent.atomic.AtomicReference
import org.thanosapollo.nema.session.SessionAttemptIdentity
import org.thanosapollo.nema.session.SessionEvent
import org.thanosapollo.nema.storage.CompleteRosterSnapshot
import org.thanosapollo.nema.storage.RosterMember
interface RosterSource {
    data class Entry(val bareJid: String, val name: String?)
    interface Listener {
        fun entriesAdded()
        fun entriesUpdated()
        fun entriesDeleted()
        fun presenceChanged()
    }

    @Throws(Exception::class)
    fun reloadAndWait(): Boolean
    fun getEntriesAndAddListener(listener: Listener, capture: (Collection<Entry>) -> Unit)
    fun entries(): Collection<Entry>
    fun removeRosterListener(listener: Listener)
}
sealed interface RosterLoadResult {
    data class Loaded(val snapshot: CompleteRosterSnapshot) : RosterLoadResult
    data object Unavailable : RosterLoadResult
}
internal interface RosterAttemptHandoff {
    fun load(admitted: () -> Boolean = { true }): RosterLoadResult
    fun retire()
}
class RosterHandoff(
    private val source: RosterSource,
    private val attempt: SessionAttemptIdentity,
    private val emit: (SessionEvent) -> Unit,
) : RosterAttemptHandoff {
    private val lock = Any()
    private var active: Candidate? = null
    private var lifecycle = 0L
    @Throws(InterruptedException::class)
    override fun load(admitted: () -> Boolean): RosterLoadResult {
        retire()
        val loadId = synchronized(lock) { lifecycle }
        if (!admitted()) return RosterLoadResult.Unavailable
        try {
            if (!source.reloadAndWait()) return RosterLoadResult.Unavailable
        } catch (failure: InterruptedException) {
            throw failure
        } catch (_: Exception) {
            return RosterLoadResult.Unavailable
        }
        val candidate = Candidate()
        var captured: List<RosterSource.Entry>? = null
        try {
            synchronized(lock) {
                if (lifecycle != loadId) return RosterLoadResult.Unavailable
                active = candidate
                source.getEntriesAndAddListener(candidate) { entries ->
                    check(captured == null) { "Roster entries captured more than once" }
                    captured = entries.toList()
                }
            }
            val initial = requireNotNull(captured) { "Roster entries were not captured" }
            return synchronized(lock) {
                if (active !== candidate || !candidate.live) return RosterLoadResult.Unavailable
                val snapshot = snapshot(initial)
                candidate.installing = false
                emit(SessionEvent.RosterSnapshot(attempt, snapshot))
                if (candidate.pending && candidate.live) emitCurrent()
                RosterLoadResult.Loaded(snapshot)
            }
        } catch (failure: InterruptedException) {
            discard(candidate)
            throw failure
        } catch (_: Exception) {
            discard(candidate)
            return RosterLoadResult.Unavailable
        }
    }

    override fun retire() {
        val retired = synchronized(lock) {
            lifecycle++
            active?.also { it.live = false }
                .also { active = null }
        }
        retired?.let(source::removeRosterListener)
    }
    private fun discard(candidate: Candidate) {
        synchronized(lock) {
            candidate.live = false
            if (active === candidate) active = null
        }
        source.removeRosterListener(candidate)
    }
    private fun emitCurrent() {
        emit(SessionEvent.RosterSnapshot(attempt, snapshot(source.entries())))
    }
    private fun snapshot(entries: Collection<RosterSource.Entry>) = CompleteRosterSnapshot(
        accountId = attempt.accountId.value,
        members = entries.map { RosterMember(it.bareJid, it.name?.trim()?.takeIf(String::isNotEmpty)) }
            .sortedBy(RosterMember::bareJid),
    )

    private inner class Candidate : RosterSource.Listener {
        var live = true
        var installing = true
        var pending = false
        override fun entriesAdded() = changed()
        override fun entriesUpdated() = changed()
        override fun entriesDeleted() = changed()
        override fun presenceChanged() = Unit

        private fun changed() = synchronized(lock) {
            if (!live || active !== this) return@synchronized
            if (installing) pending = true else emitCurrent()
        }
    }
}

internal class RosterConnectionLifecycle(private val entryGate: Any) {
    private data class Active(
        val attempt: SessionAttemptIdentity,
        val handoff: RosterAttemptHandoff,
    )

    private val active = AtomicReference<Active?>()

    fun load(
        attempt: SessionAttemptIdentity,
        handoff: RosterAttemptHandoff,
        admitted: () -> Boolean,
    ): Boolean {
        var installed = false
        val previous = synchronized(entryGate) {
            if (!admitted()) null else active.getAndSet(Active(attempt, handoff)).also { installed = true }
        }
        if (!installed) {
            handoff.retire()
            return false
        }
        previous?.handoff?.retire()
        try {
            handoff.load {
                active.get().let { it?.attempt == attempt && it.handoff === handoff } && admitted()
            }
        } catch (failure: Throwable) {
            retire(attempt, handoff)
            throw failure
        }
        val current = synchronized(entryGate) {
            active.get().let { it?.attempt == attempt && it.handoff === handoff } && admitted()
        }
        if (!current) retire(attempt, handoff)
        return current
    }

    fun retire(attempt: SessionAttemptIdentity, handoff: RosterAttemptHandoff? = null) {
        var retired = handoff
        while (true) {
            val current = active.get()
            if (current?.attempt != attempt || handoff != null && current.handoff !== handoff) break
            if (active.compareAndSet(current, null)) {
                retired = current.handoff
                break
            }
        }
        retired?.retire()
    }

    fun retireCurrent() {
        active.getAndSet(null)?.handoff?.retire()
    }
}
