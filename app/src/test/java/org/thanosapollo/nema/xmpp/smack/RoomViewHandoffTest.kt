package org.thanosapollo.nema.xmpp.smack

import org.junit.Assert.*
import org.junit.Test
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.xmpp.transport.*

class RoomViewHandoffTest {
    @Test fun `activation publishes after view and replacement cleanup is outside entry`() {
        val h = Harness()
        val first = h.newCandidate("first", stable = true)
        assertTrue(first.activate())
        val second = h.newCandidate("second", stable = false)
        assertTrue(second.activate())
        assertEquals(listOf("view:first", "event:first", "view:second", "remove:first:subject", "remove:first:participant", "remove:first:status", "event:second"), h.log.filter { it.startsWith("view") || it.startsWith("event") || it.startsWith("remove:first") })
        assertFalse(h.registry.snapshot(h.attempt, ROOM)!!.stableIds)
        assertTrue(h.fixture.log.filter { it.startsWith("entry") }.all { it == "entry:free" })
        assertTrue(h.mucHeldDuringDelivery)
    }
    @Test fun `failed activation revokes pending and cleans transaction`() {
        val h = Harness(fail = RoomViewHandoffFixture.Operation.ADD)
        assertThrows(IllegalStateException::class.java) { h.newCandidate("bad").activate() }
        assertNull(h.registry.snapshot(h.attempt, ROOM))
        assertFalse(h.registry.publish(h.lease, true, true))
        val viewFailure = Harness(viewFailure = true)
        assertThrows(IllegalStateException::class.java) { viewFailure.newCandidate("bad").activate() }
        assertFalse(viewFailure.registry.publish(viewFailure.lease, true, true))
        val eventFailure = Harness(fail = RoomViewHandoffFixture.Operation.EVENT)
        assertTrue(eventFailure.newCandidate("accepted").activate())
        assertNotNull(eventFailure.registry.snapshot(eventFailure.attempt, ROOM))
        eventFailure.handoff.retireAll(); assertTrue(eventFailure.live.isEmpty())
        val cancelled = Harness(cancelEvent = true); val candidate = cancelled.newCandidate("accepted")
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { candidate.activate() }
        assertTrue(candidate.isActive()); cancelled.handoff.retireAll()
    }
    @Test fun `retirement enters once and hides authority before cleanup`() {
        val h = Harness(); h.newCandidate("one").activate(); val old = h.tokens.toList()
        assertTrue(h.handoff.retireAllIf { assertTrue(Thread.holdsLock(h.fixture.entryLock)); true })
        assertNull(h.registry.snapshot(h.attempt, ROOM)); assertTrue(h.live.isEmpty())
        val events = h.log.count { it.startsWith("event") }; old.forEach { it.refresh() }
        assertEquals(events, h.log.count { it.startsWith("event") })
    }
    @Test fun `callbacks fence stale lease including non SID and revoke exact current`() {
        val h = Harness()
        h.newCandidate("old").activate(); val old = h.tokens.toList()
        h.newCandidate("new", stable = false).activate()
        old.forEach { it.refresh() }
        h.tokens.last().refresh()
        assertEquals(3, h.log.count { it.startsWith("event") })
        old.first().revoke()
        assertNotNull(h.registry.snapshot(h.attempt, ROOM))
        h.tokens.first { it.name == "new:status" }.revoke()
        assertNull(h.registry.snapshot(h.attempt, ROOM))
        assertTrue(h.live.isEmpty())
    }
    @Test fun `attempt reset detaches before cleanup without ordering`() {
        val h = Harness(); h.newCandidate("one").activate()
        h.handoff.beginAttempt(attempt(2))
        assertTrue(h.live.isEmpty()); assertNull(h.registry.snapshot(h.attempt, ROOM))
        val active = h.handoff.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(h.handoff) as Map<*, *>
        assertTrue(active.isEmpty())
        h.handoff.retireAll()
    }
    @Test fun `activation and callback retain MUC through event`() {
        val h = Harness(block = setOf(RoomViewHandoffFixture.Gate.ACTIVATION)); val candidate = h.newCandidate("one")
        val activation = Thread { candidate.activate() }.also(Thread::start)
        val callbackStarted = java.util.concurrent.CountDownLatch(1)
        lateinit var callback: Thread
        try {
            assertTrue(h.fixture.entered.getValue(RoomViewHandoffFixture.Gate.ACTIVATION).await(1, java.util.concurrent.TimeUnit.SECONDS))
            callback = Thread { callbackStarted.countDown(); h.tokens.first().refresh() }.also(Thread::start)
            assertTrue(callbackStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)); assertTrue(callback.isAlive)
        } finally { h.fixture.release.getValue(RoomViewHandoffFixture.Gate.ACTIVATION).countDown() }
        activation.join(1_000); callback.join(1_000)
        assertFalse(activation.isAlive); assertFalse(callback.isAlive)
        assertEquals(2, h.log.count { it.startsWith("event") })
    }
    private class Harness(fail: RoomViewHandoffFixture.Operation? = null, val viewFailure: Boolean = false, val cancelEvent: Boolean = false, block: Set<RoomViewHandoffFixture.Gate> = emptySet()) {
        val fixture = RoomViewHandoffFixture(failAt = fail, blockAt = block); val log = fixture.log
        val attempt = attempt(1); val registry = RoomStableIdAuthorityRegistry().apply { begin(attempt) }
        val handoff = RoomViewHandoff<Token, Token, Token, String>(fixture.entryLock, registry)
        val muc = Any(); val live = linkedSetOf<Token>(); val tokens = mutableListOf<Token>()
        var mucHeldDuringDelivery = false
        lateinit var lease: RoomStableIdLease
        fun newCandidate(name: String, stable: Boolean = true): RoomViewHandoff<Token, Token, Token, String>.Candidate {
            lease = requireNotNull(registry.beginJoin(attempt, ROOM))
            fun token(kind: String, refresh: () -> Unit, revoke: () -> Unit = {}) = Token("$name:$kind", refresh, revoke).also(tokens::add)
            val ls = RoomViewHandoff.Listeners(
                status = { refresh, revoke -> token("status", refresh, revoke) }, participant = { token("participant", it) }, subject = { token("subject", it) },
                addStatus = ::add, addParticipant = ::add, addSubject = ::add,
                removeStatus = ::remove, removeParticipant = ::remove, removeSubject = ::remove)
            return handoff.candidate(lease, muc, { true }, ls, { log += "view:$name"; if (viewFailure) error("view"); name }, { mucHeldDuringDelivery = mucHeldDuringDelivery || Thread.holdsLock(muc); fixture.entryProbe(); fixture.reach(RoomViewHandoffFixture.Gate.ACTIVATION); if (cancelEvent) throw kotlinx.coroutines.CancellationException("event"); fixture.effect(RoomViewHandoffFixture.Operation.EVENT, it) }, stable, true, name)
        }
        fun add(token: Token): Boolean { fixture.effect(RoomViewHandoffFixture.Operation.ADD, token.name); return live.add(token) }
        fun remove(token: Token) { fixture.entryProbe(); fixture.effect(RoomViewHandoffFixture.Operation.REMOVE, token.name); live.remove(token) }
    }
    private data class Token(val name: String, val refresh: () -> Unit, val revoke: () -> Unit)
    companion object {
        const val ROOM = "room@example.org"
        fun attempt(n: Long) = SessionAttemptIdentity(AccountId.require("a"), ConnectionGeneration.require(1), ConnectionAttempt.require(n), LifecycleEpoch.require(1))
    }
}
