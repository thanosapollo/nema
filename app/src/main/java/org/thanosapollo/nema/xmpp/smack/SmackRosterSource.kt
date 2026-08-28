package org.thanosapollo.nema.xmpp.smack

import java.util.IdentityHashMap
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterEntries
import org.jivesoftware.smack.roster.RosterListener
import org.jxmpp.jid.Jid

class SmackRosterSource internal constructor(private val roster: SmackRosterApi) : RosterSource {
    constructor(roster: Roster) : this(RealSmackRosterApi(roster))

    private val listeners = IdentityHashMap<RosterSource.Listener, RosterListener>()

    override fun reloadAndWait(): Boolean {
        roster.reloadAndWait()
        return roster.isLoaded()
    }

    override fun getEntriesAndAddListener(
        listener: RosterSource.Listener,
        capture: (Collection<RosterSource.Entry>) -> Unit,
    ) {
        val smackListener = listener.toSmackListener()
        synchronized(listeners) {
            check(listeners.put(listener, smackListener) == null) { "Roster listener already installed" }
        }
        roster.getEntriesAndAddListener(smackListener) { capture(it.toEntries()) }
    }

    override fun entries(): Collection<RosterSource.Entry> = roster.entries().toEntries()

    override fun removeRosterListener(listener: RosterSource.Listener) {
        val smackListener = synchronized(listeners) { listeners.remove(listener) } ?: return
        roster.removeRosterListener(smackListener)
    }

    private fun RosterSource.Listener.toSmackListener() = object : RosterListener {
        override fun entriesAdded(addresses: Collection<Jid>) = this@toSmackListener.entriesAdded()
        override fun entriesUpdated(addresses: Collection<Jid>) = this@toSmackListener.entriesUpdated()
        override fun entriesDeleted(addresses: Collection<Jid>) = this@toSmackListener.entriesDeleted()
        override fun presenceChanged(presence: Presence) = Unit
    }

    private fun Collection<SmackRosterEntry>.toEntries() = mapNotNull { entry ->
        entry.jid.takeIf(Jid::isEntityBareJid)?.let {
            RosterSource.Entry(it.asEntityBareJidOrThrow().toString(), entry.name)
        }
    }
}

internal data class SmackRosterEntry(val jid: Jid, val name: String?)

internal interface SmackRosterApi {
    fun reloadAndWait()
    fun isLoaded(): Boolean
    fun getEntriesAndAddListener(listener: RosterListener, capture: (Collection<SmackRosterEntry>) -> Unit)
    fun entries(): Collection<SmackRosterEntry>
    fun removeRosterListener(listener: RosterListener)
}

private class RealSmackRosterApi(private val roster: Roster) : SmackRosterApi {
    override fun reloadAndWait() = roster.reloadAndWait()
    override fun isLoaded() = roster.isLoaded
    override fun getEntriesAndAddListener(listener: RosterListener, capture: (Collection<SmackRosterEntry>) -> Unit) =
        roster.getEntriesAndAddListener(listener, RosterEntries { capture(it.map(::entry)) })
    override fun entries(): Collection<SmackRosterEntry> = roster.entries.map(::entry)
    override fun removeRosterListener(listener: RosterListener) { roster.removeRosterListener(listener) }
    private fun entry(entry: org.jivesoftware.smack.roster.RosterEntry) = SmackRosterEntry(entry.jid, entry.name)
}
