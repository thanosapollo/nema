package org.thanosapollo.nema.xmpp.smack

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.xmpp.transport.*

class RosterConnectionLifecycleTest {
    @Test fun `load is admitted before connected and unavailable is soft`() {
        val lifecycle = RosterConnectionLifecycle(Any())
        val order = mutableListOf<String>()
        val loaded = FakeHandoff(loadAction = { order += "roster" })
        assertTrue(lifecycle.load(attempt(1), loaded) { true })
        order += "connected"
        assertEquals(listOf("roster", "connected"), order)
        val unavailable = FakeHandoff(loadAction = { order += "unavailable" })
        assertTrue(lifecycle.load(attempt(2), unavailable) { true })
        assertTrue(loaded.retired)
    }

    @Test fun `replacement and stale completion cannot damage successor`() {
        val lifecycle = RosterConnectionLifecycle(Any())
        val old = FakeHandoff()
        val successor = FakeHandoff()
        assertTrue(lifecycle.load(attempt(1), old) { true })
        assertTrue(lifecycle.load(attempt(2), successor) { true })
        lifecycle.retire(attempt(1), old)
        assertTrue(old.retired)
        assertFalse(successor.retired)
        lifecycle.retireCurrent()
        assertTrue(successor.retired)
    }

    @Test fun `stale load is rejected after attempt replacement`() {
        val lifecycle = RosterConnectionLifecycle(Any())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = FakeHandoff(loadAction = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        val admitted = AtomicBoolean(true)
        val result = AtomicBoolean(true)
        val worker = Thread { result.set(lifecycle.load(attempt(1), old) { admitted.get() }) }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        admitted.set(false)
        lifecycle.retireCurrent()
        release.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
        assertFalse(result.get())
        assertTrue(old.retired)
    }

    @Test fun `retirement before load cannot be erased`() {
        val lifecycle = RosterConnectionLifecycle(Any())
        val retiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = FakeHandoff(retireAction = { retiring.countDown(); release.await() })
        val candidate = FakeHandoff()
        assertTrue(lifecycle.load(attempt(1), old) { true })
        val worker = Thread { lifecycle.load(attempt(2), candidate) { true } }.apply { start() }
        assertTrue(retiring.await(5, TimeUnit.SECONDS))
        lifecycle.retireCurrent()
        release.countDown()
        worker.join(5_000)
        assertFalse(candidate.loaded)
    }

    @Test fun `retirement never holds connection gate while waiting for roster lock`() {
        repeat(20) {
            val entryGate = Any()
            val rosterLock = Any()
            val retireEntered = CountDownLatch(1)
            val lifecycle = RosterConnectionLifecycle(entryGate)
            assertTrue(lifecycle.load(attempt(1), FakeHandoff(retireAction = { retireEntered.countDown(); synchronized(rosterLock) {} })) { true })
            val callbackHasRoster = CountDownLatch(1)
            val releaseRoster = CountDownLatch(1)
            val callback = Thread {
                synchronized(rosterLock) {
                    callbackHasRoster.countDown()
                    synchronized(entryGate) {}
                    releaseRoster.countDown()
                }
            }.apply { isDaemon = true; start() }
            assertTrue(callbackHasRoster.await(5, TimeUnit.SECONDS))
            val replacement = Thread { lifecycle.retireCurrent() }.apply { isDaemon = true }
            synchronized(entryGate) { replacement.start(); assertTrue(retireEntered.await(5, TimeUnit.SECONDS)) }
            assertTrue(releaseRoster.await(5, TimeUnit.SECONDS))
            callback.join(5_000)
            replacement.join(5_000)
            assertFalse(callback.isAlive || replacement.isAlive)
        }
    }

    private class FakeHandoff(
        private val loadAction: () -> Unit = {},
        private val retireAction: () -> Unit = {},
    ) : RosterAttemptHandoff {
        @Volatile var retired = false
        @Volatile var loaded = false
        override fun load(admitted: () -> Boolean): RosterLoadResult {
            if (admitted()) { loaded = true; loadAction() }
            return RosterLoadResult.Unavailable
        }
        override fun retire() { retired = true; retireAction() }
    }

    private fun attempt(generation: Long) = SessionAttemptIdentity(
        AccountId.require("account"), ConnectionGeneration.require(generation),
        ConnectionAttempt.require(1), LifecycleEpoch.require(1),
    )
}
