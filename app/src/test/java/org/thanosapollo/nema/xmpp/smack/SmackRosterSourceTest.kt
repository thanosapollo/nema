package org.thanosapollo.nema.xmpp.smack

import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.roster.RosterListener
import org.junit.Assert.*
import org.junit.Test
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate

class SmackRosterSourceTest {
    @Test
    fun `reload result requires Smack to report loaded`() {
        val api = FakeRoster(loaded = false)
        val source = SmackRosterSource(api)

        assertFalse(source.reloadAndWait())
        assertEquals(1, api.reloads)
        api.loaded = true
        assertTrue(source.reloadAndWait())
        var captured: Collection<RosterSource.Entry>? = null
        source.getEntriesAndAddListener(RecordingListener()) { captured = it }
        assertTrue(captured!!.isEmpty())
    }

    @Test
    fun `atomic capture and current entries keep only entity bare JIDs and names`() {
        val api = FakeRoster(
            raw("alice@example.org", " Alice "),
            raw("example.org", "domain"),
            raw("bob@example.org/phone", "full"),
        )
        val source = SmackRosterSource(api)
        val listener = RecordingListener()
        var captured: Collection<RosterSource.Entry>? = null

        source.getEntriesAndAddListener(listener) { captured = it }

        assertEquals(listOf(RosterSource.Entry("alice@example.org", " Alice ")), captured)
        assertEquals(1, api.atomicCaptures)
        api.entries = listOf(raw("carol@example.org", "Carol"), raw("example.net", null))
        assertEquals(listOf(RosterSource.Entry("carol@example.org", "Carol")), source.entries())
    }

    @Test
    fun `Smack add update and delete callbacks map while presence is inert`() {
        val api = FakeRoster()
        val source = SmackRosterSource(api)
        val listener = RecordingListener()
        source.getEntriesAndAddListener(listener) {}
        val smackListener = api.installed.single()

        smackListener.entriesAdded(emptyList())
        smackListener.entriesUpdated(emptyList())
        smackListener.entriesDeleted(emptyList())
        smackListener.presenceChanged(Presence(Presence.Type.available))

        assertEquals(listOf("add", "update", "delete"), listener.calls)
    }

    @Test
    fun `equal custom listeners remove their exact Smack listener by identity`() {
        val api = FakeRoster()
        val source = SmackRosterSource(api)
        val first = EqualListener()
        val second = EqualListener()
        source.getEntriesAndAddListener(first) {}
        source.getEntriesAndAddListener(second) {}
        val firstSmack = api.installed[0]
        val secondSmack = api.installed[1]

        source.removeRosterListener(first)
        source.removeRosterListener(second)

        assertSame(firstSmack, api.removed[0])
        assertSame(secondSmack, api.removed[1])
        assertNotSame(firstSmack, secondSmack)
    }

    private open class RecordingListener : RosterSource.Listener {
        val calls = mutableListOf<String>()
        override fun entriesAdded() { calls += "add" }
        override fun entriesUpdated() { calls += "update" }
        override fun entriesDeleted() { calls += "delete" }
        override fun presenceChanged() { calls += "presence" }
    }

    private class EqualListener : RecordingListener() {
        override fun equals(other: Any?) = other is EqualListener
        override fun hashCode() = 1
    }

    private class FakeRoster(vararg initial: SmackRosterEntry, var loaded: Boolean = true) : SmackRosterApi {
        var entries = initial.toList()
        var reloads = 0
        var atomicCaptures = 0
        val installed = mutableListOf<RosterListener>()
        val removed = mutableListOf<RosterListener>()
        override fun reloadAndWait() { reloads++ }
        override fun isLoaded() = loaded
        override fun getEntriesAndAddListener(listener: RosterListener, capture: (Collection<SmackRosterEntry>) -> Unit) {
            atomicCaptures++
            installed += listener
            capture(entries)
        }
        override fun entries(): Collection<SmackRosterEntry> = entries
        override fun removeRosterListener(listener: RosterListener) { removed += listener }
    }

    companion object {
        private fun raw(jid: String, name: String?) = SmackRosterEntry(JidCreate.from(jid), name)
    }
}
