package org.thanosapollo.nema.xmpp.smack

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StanzaFactory
import org.jivesoftware.smack.packet.id.StandardStanzaIdSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

class SmackThreadMapperTest {
    @Test
    fun `maps one Smack child thread to Nema lineage`() {
        val message = messageBuilder()
            .setThread("child-thread", "parent-thread")
            .build()

        assertEquals(
            ThreadRef(ThreadId.require("child-thread"), ThreadId.require("parent-thread")),
            message.toThreadRef(),
        )
    }

    @Test
    fun `rejects duplicate and self-parent Smack threads`() {
        val duplicate = messageBuilder()
            .addExtension(Message.Thread("first-thread"))
            .addExtension(Message.Thread("second-thread"))
            .build()
        val selfParent = messageBuilder()
            .setThread("same-thread", "same-thread")
            .build()

        assertNull(duplicate.toThreadRef())
        assertNull(selfParent.toThreadRef())
    }

    @Test
    fun `writes exact Nema thread lineage through Smack builder`() {
        val thread = ThreadRef(
            ThreadId.require("child-thread"),
            ThreadId.require("parent-thread"),
        )

        val message = messageBuilder()
            .setThreadRef(thread)
            .build()
        val smackThread = message.getExtensions(Message.Thread::class.java).single()

        assertEquals("child-thread", smackThread.thread)
        assertEquals("parent-thread", smackThread.parent)
    }

    private fun messageBuilder() = StanzaFactory(StandardStanzaIdSource()).buildMessageStanza()
}
