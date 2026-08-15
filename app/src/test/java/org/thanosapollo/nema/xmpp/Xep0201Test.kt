package org.thanosapollo.nema.xmpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

class Xep0201Test {
    @Test
    fun `parses one core thread with parent lineage`() {
        val element = XmppElement(
            name = "thread",
            namespace = Xep0201.CORE_NAMESPACE,
            attributes = mapOf("parent" to "parent-1"),
            text = "child-1",
        )

        assertEquals(
            ThreadRef(ThreadId.require("child-1"), ThreadId.require("parent-1")),
            Xep0201.parse(listOf(element)),
        )
    }

    @Test
    fun `accepts inherited core namespace and ignores foreign thread`() {
        val foreign = XmppElement("thread", "urn:example:foreign", text = "foreign")
        val core = XmppElement("thread", null, text = "thread-1")

        assertEquals(ThreadId.require("thread-1"), Xep0201.parse(listOf(foreign, core))?.id)
    }

    @Test
    fun `rejects empty duplicate nested and self-parent thread elements`() {
        assertNull(Xep0201.parse(listOf(XmppElement("thread", null, text = ""))))
        assertNull(
            Xep0201.parse(
                listOf(
                    XmppElement("thread", null, text = "one"),
                    XmppElement("thread", null, text = "two"),
                ),
            ),
        )
        assertNull(
            Xep0201.parse(
                listOf(
                    XmppElement(
                        "thread",
                        null,
                        text = "one",
                        children = listOf(XmppElement("nested", "urn:example")),
                    ),
                ),
            ),
        )
        assertNull(
            Xep0201.parse(
                listOf(
                    XmppElement(
                        "thread",
                        null,
                        attributes = mapOf("parent" to "same"),
                        text = "same",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `serializes exact core thread metadata`() {
        val thread = ThreadRef(ThreadId.require("child"), ThreadId.require("parent"))

        assertEquals(
            XmppElement(
                name = "thread",
                namespace = Xep0201.CORE_NAMESPACE,
                attributes = mapOf("parent" to "parent"),
                text = "child",
            ),
            Xep0201.serialize(thread),
        )
    }
}
