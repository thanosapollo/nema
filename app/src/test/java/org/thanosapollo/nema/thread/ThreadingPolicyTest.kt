package org.thanosapollo.nema.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadingPolicyTest {
    private val ids = ArrayDeque(listOf("topic", "child"))
    private val policy = ThreadingPolicy { ThreadId.require(ids.removeFirst()) }

    @Test
    fun `new topic receives a fresh top-level id`() {
        val topic = policy.newTopic()

        assertEquals("topic", topic.id.value)
        assertNull(topic.parentId)
    }

    @Test
    fun `direct reply keeps current thread and lineage`() {
        val current = ThreadRef(ThreadId.require("child"), ThreadId.require("parent"))

        assertEquals(current, policy.replyTo(current))
    }

    @Test
    fun `child topic receives fresh id pointing to current thread id`() {
        val parent = ThreadRef(ThreadId.require("topic"))
        policy.newTopic()

        val child = policy.childOf(parent)

        assertEquals("child", child.id.value)
        assertEquals(parent.id, child.parentId)
        assertNotEquals(parent.id, child.id)
    }
}
