package org.thanosapollo.nema.xmpp.smack
import org.junit.Assert.*
import org.junit.Test
import org.thanosapollo.nema.session.*
import org.thanosapollo.nema.storage.RosterMember
import org.thanosapollo.nema.xmpp.transport.*
class RosterHandoffTest {
    @Test
    fun `reload failure and incomplete load emit nothing while loaded empty is authoritative`() {
        val failed = Fixture(reloadFailure = IllegalStateException("offline"))
        assertSame(RosterLoadResult.Unavailable, failed.load())
        assertTrue(failed.events.isEmpty())
        val incomplete = Fixture(loaded = false)
        assertSame(RosterLoadResult.Unavailable, incomplete.load())
        assertTrue(incomplete.source.installed.isEmpty())
        val empty = Fixture()
        val loaded = empty.load() as RosterLoadResult.Loaded
        assertTrue(loaded.snapshot.members.isEmpty())
        assertEquals(listOf(loaded.snapshot), empty.snapshots())
    }
    @Test
    fun `atomic capture normalizes names then changes refresh complete snapshot`() {
        val fixture = Fixture(entries = listOf(entry("b@example.org", "  "), entry("a@example.org", " Alice ")))
        fixture.load()
        fixture.source.entries = listOf(entry("c@example.org", "Carol"))
        fixture.source.listener!!.entriesAdded()
        fixture.source.listener!!.entriesUpdated()
        fixture.source.listener!!.entriesDeleted()
        fixture.source.listener!!.presenceChanged()
        assertEquals(
            listOf(
                listOf(RosterMember("a@example.org", "Alice"), RosterMember("b@example.org", null)),
                listOf(RosterMember("c@example.org", "Carol")),
                listOf(RosterMember("c@example.org", "Carol")),
                listOf(RosterMember("c@example.org", "Carol")),
            ),
            fixture.snapshots().map { it.members },
        )
    }
    @Test
    fun `change during atomic install follows initial capture`() {
        val fixture = Fixture(entries = listOf(entry("before@example.org")))
        fixture.source.duringInstall = {
            fixture.source.entries = listOf(entry("after@example.org"))
            it.entriesAdded()
        }
        fixture.load()
        assertEquals(listOf("before@example.org", "after@example.org"), fixture.snapshots().map { it.members.single().bareJid })
    }
    @Test
    fun `install failure removes candidate and stale callbacks stay inert after retire or replacement`() {
        val failed = Fixture(installFailure = IllegalStateException("install"))
        assertSame(RosterLoadResult.Unavailable, failed.load())
        assertEquals(listOf(failed.source.installed.single()), failed.source.removed)
        val fixture = Fixture(entries = listOf(entry("old@example.org")))
        fixture.load()
        val stale = fixture.source.listener!!
        fixture.source.entries = listOf(entry("new@example.org"))
        fixture.load()
        assertEquals(stale, fixture.source.removed.single())
        val count = fixture.events.size
        stale.entriesDeleted()
        fixture.handoff.retire()
        fixture.source.listener!!.entriesAdded()
        assertEquals(count, fixture.events.size)
        assertEquals(2, fixture.source.removed.size)
    }
    @Test
    fun `interruption propagates without event or listener`() {
        val interrupted = InterruptedException("stop")
        val fixture = Fixture(reloadFailure = interrupted)
        assertSame(interrupted, assertThrows(InterruptedException::class.java) { fixture.load() })
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.source.installed.isEmpty())
        val retired = Fixture()
        retired.source.duringReload = retired.handoff::retire
        assertSame(RosterLoadResult.Unavailable, retired.load())
        assertTrue(retired.source.installed.isEmpty())
    }
    private class Fixture(
        entries: List<RosterSource.Entry> = emptyList(),
        reloadFailure: Exception? = null,
        installFailure: Exception? = null, loaded: Boolean = true,
    ) {
        val source = FakeSource(entries, reloadFailure, installFailure, loaded)
        val events = mutableListOf<SessionEvent>()
        val handoff = RosterHandoff(source, ATTEMPT, events::add)
        fun load() = handoff.load()
        fun snapshots() = events.filterIsInstance<SessionEvent.RosterSnapshot>().map { it.snapshot }
    }

    private class FakeSource(
        var entries: List<RosterSource.Entry>,
        private val reloadFailure: Exception?,
        private val installFailure: Exception?, private val loaded: Boolean,
    ) : RosterSource {
        var listener: RosterSource.Listener? = null
        var duringReload: (() -> Unit)? = null
        var duringInstall: ((RosterSource.Listener) -> Unit)? = null
        val installed = mutableListOf<RosterSource.Listener>()
        val removed = mutableListOf<RosterSource.Listener>()
        override fun reloadAndWait(): Boolean { duringReload?.invoke(); reloadFailure?.let { throw it }; return loaded }
        override fun getEntriesAndAddListener(listener: RosterSource.Listener, capture: (Collection<RosterSource.Entry>) -> Unit) {
            this.listener = listener
            installed += listener
            capture(entries)
            duringInstall?.invoke(listener)
            installFailure?.let { throw it }
        }
        override fun entries(): Collection<RosterSource.Entry> = entries
        override fun removeRosterListener(listener: RosterSource.Listener) { removed += listener }
    }

    companion object {
        private val ATTEMPT = SessionAttemptIdentity(AccountId.require("account"), ConnectionGeneration.require(2), ConnectionAttempt.require(3), LifecycleEpoch.require(4))
        private fun entry(jid: String, name: String? = null) = RosterSource.Entry(jid, name)
    }
}
