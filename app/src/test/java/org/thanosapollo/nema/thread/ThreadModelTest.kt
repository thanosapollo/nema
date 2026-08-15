package org.thanosapollo.nema.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadModelTest {
    @Test
    fun `thread ids are non-empty opaque values`() {
        assertNull(ThreadId.parse(null))
        assertNull(ThreadId.parse(""))
        assertEquals(" agent/thread 7 ", ThreadId.require(" agent/thread 7 ").value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `thread parent must differ from child`() {
        val id = ThreadId.require("same")
        ThreadRef(id = id, parentId = id)
    }

    @Test
    fun `thread identity is scoped by account peer and message kind`() {
        val id = ThreadId.require("shared")
        val first = ThreadKey("account-a", "room@example.org", MessageKind.GROUPCHAT, id)
        val otherAccount = ThreadKey("account-b", "room@example.org", MessageKind.GROUPCHAT, id)
        val direct = ThreadKey("account-a", "room@example.org", MessageKind.CHAT, id)

        assertNotEquals(first, otherAccount)
        assertNotEquals(first, direct)
    }
}
