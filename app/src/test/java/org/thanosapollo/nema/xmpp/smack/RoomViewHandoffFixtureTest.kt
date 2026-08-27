package org.thanosapollo.nema.xmpp.smack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RoomViewHandoffFixtureTest {
    @Test
    fun `listeners are named and isolated by candidate`() {
        val fixture = RoomViewHandoffFixture()

        val first = fixture.listeners("first")
        val second = fixture.listeners("second")

        assertEquals(listOf("first:status", "first:participant", "first:subject"), first.map { it.name })
        assertNotEquals(first, second)
    }

    @Test
    fun `each operation and failure records its exact effect`() {
        RoomViewHandoffFixture.Operation.entries.forEach { operation ->
            val fixture = RoomViewHandoffFixture(failAt = operation)

            assertThrows(IllegalStateException::class.java) { fixture.effect(operation, "candidate:status") }
            assertEquals(listOf("${operation.label}:candidate:status", "fail:${operation.label}"), fixture.log)
        }
    }

    @Test
    fun `entry probe reports the actual lock state`() {
        val fixture = RoomViewHandoffFixture()

        fixture.entryProbe()
        synchronized(fixture.entryLock) { fixture.entryProbe() }

        assertEquals(listOf("entry:free", "entry:held"), fixture.log)
    }

    @Test
    fun `every barrier and race control blocks until released`() {
        RoomViewHandoffFixture.Gate.entries.forEach(::assertGate)
    }

    private fun assertGate(gate: RoomViewHandoffFixture.Gate) {
        val fixture = RoomViewHandoffFixture(blockAt = setOf(gate))
        val thread = Thread { fixture.reach(gate) }.also(Thread::start)

        try {
            assertTrue(fixture.entered.getValue(gate).await(1, TimeUnit.SECONDS))
            assertTrue(thread.isAlive)
            assertEquals(listOf("reach:${gate.label}"), fixture.log)
        } finally {
            fixture.release.getValue(gate).countDown()
        }
        thread.join(1_000)
        assertFalse(thread.isAlive)
        assertEquals(listOf("reach:${gate.label}", "pass:${gate.label}"), fixture.log)
    }
}
