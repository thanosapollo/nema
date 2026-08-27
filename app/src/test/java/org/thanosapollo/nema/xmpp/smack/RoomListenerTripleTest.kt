package org.thanosapollo.nema.xmpp.smack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RoomListenerTripleTest {
    @Test
    fun `install adds every listener in order and cleanup removes in reverse`() {
        val fixture = Fixture()

        val installed = fixture.install()

        assertNotNull(installed)
        assertEquals(listOf("add:status", "add:participant", "add:subject"), fixture.events)
        assertEquals(fixture.listeners, fixture.active)
        installed!!.cleanup()
        assertEquals(
            listOf(
                "add:status", "add:participant", "add:subject",
                "remove:subject", "remove:participant", "remove:status",
            ),
            fixture.events,
        )
        assertEquals(emptySet<Listener>(), fixture.active)
    }

    @Test
    fun `false from each add rolls back every successful prefix`() {
        Listener.entries.forEach { failed ->
            val fixture = Fixture(falseAdd = failed)

            assertNull(fixture.install())
            assertEquals(emptySet<Listener>(), fixture.active)
            assertEquals(expectedFailureEvents(failed), fixture.events)
        }
    }

    @Test
    fun `exception from each add rolls back every successful prefix`() {
        Listener.entries.forEach { failed ->
            val fixture = Fixture(throwingAdd = failed)
            val failure = IllegalStateException(failed.name)
            fixture.addFailure = failure

            try {
                fixture.install()
                fail("Expected add failure for $failed")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
            assertEquals(emptySet<Listener>(), fixture.active)
            assertEquals(expectedFailureEvents(failed), fixture.events)
        }
    }

    @Test
    fun `each remove failure still attempts every remaining removal`() {
        Listener.entries.forEach { failed ->
            val fixture = Fixture(throwingRemove = failed)
            val installed = fixture.install()!!

            installed.cleanup()

            assertEquals(
                listOf(
                    "add:status", "add:participant", "add:subject",
                    "remove:subject", "remove:participant", "remove:status",
                ),
                fixture.events,
            )
            assertEquals(1, fixture.active.size)
            assertEquals(failed, fixture.active.single().kind)
        }
    }

    @Test
    fun `rollback continues after a remove failure`() {
        val fixture = Fixture(falseAdd = Listener.SUBJECT, throwingRemove = Listener.PARTICIPANT)

        assertNull(fixture.install())

        assertEquals(setOf(fixture.participant), fixture.active)
        assertEquals(expectedFailureEvents(Listener.SUBJECT), fixture.events)
    }

    @Test
    fun `cleanup is idempotent`() {
        val fixture = Fixture()
        val installed = fixture.install()!!

        installed.cleanup()
        installed.cleanup()

        assertEquals(6, fixture.events.size)
        assertEquals(emptySet<Listener>(), fixture.active)
    }

    @Test
    fun `cleanup removes only its exact candidate`() {
        val first = Fixture("first")
        val second = Fixture("second")
        val firstInstalled = first.install()!!
        val secondInstalled = second.install()!!

        firstInstalled.cleanup()

        assertEquals(emptySet<Listener>(), first.active)
        assertEquals(second.listeners, second.active)
        assertEquals(
            listOf("add:status", "add:participant", "add:subject"),
            second.events,
        )
        secondInstalled.cleanup()
    }

    private fun expectedFailureEvents(failed: Listener): List<String> {
        val prefix = Listener.entries.take(failed.ordinal)
        return Listener.entries.take(failed.ordinal + 1).map { "add:${it.label}" } +
            prefix.reversed().map { "remove:${it.label}" }
    }

    private enum class Listener(val label: String) {
        STATUS("status"),
        PARTICIPANT("participant"),
        SUBJECT("subject"),
    }

    private class Fixture(
        candidate: String = "candidate",
        private val falseAdd: Listener? = null,
        private val throwingAdd: Listener? = null,
        private val throwingRemove: Listener? = null,
    ) {
        val status = Token(candidate, Listener.STATUS)
        val participant = Token(candidate, Listener.PARTICIPANT)
        val subject = Token(candidate, Listener.SUBJECT)
        val listeners = setOf(status, participant, subject)
        val active = mutableSetOf<Token>()
        val events = mutableListOf<String>()
        var addFailure = IllegalStateException("add")

        fun install(): RoomListenerTriple<Token, Token, Token>? = RoomListenerTriple.install(
            status = status,
            participant = participant,
            subject = subject,
            addStatus = ::add,
            addParticipant = ::add,
            addSubject = ::add,
            removeStatus = ::remove,
            removeParticipant = ::remove,
            removeSubject = ::remove,
        )

        private fun add(token: Token): Boolean {
            events += "add:${token.kind.label}"
            if (throwingAdd == token.kind) throw addFailure
            if (falseAdd == token.kind) return false
            return active.add(token)
        }

        private fun remove(token: Token) {
            events += "remove:${token.kind.label}"
            if (throwingRemove == token.kind) throw IllegalStateException("remove")
            active.remove(token)
        }
    }

    private data class Token(val candidate: String, val kind: Listener)
}
