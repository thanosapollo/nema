package org.thanosapollo.nema.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

class HistoryIndexTest {
    @Test
    fun indexedReplyLookupMatchesScanIncludingAmbiguityAndAuthorRules() {
        val history = listOf(
            message("a", author = "alice@example.org/phone", reference = "shared"),
            message("b", author = "bob@example.org/phone", reference = "shared"),
            message("c", author = "alice@example.org/laptop", reference = "ambiguous"),
            message("d", author = "alice@example.org/phone", reference = "ambiguous"),
            message("muc-a", author = "room@example.org/Alice", reference = "muc", muc = true),
            message("muc-b", author = "room@example.org/Bob", reference = "muc", muc = true),
            message("both-direct", author = "mixed@example.org/device", reference = "both"),
            message("both-muc", author = "mixed@example.org/device", reference = "both", muc = true),
            // Repeated aliases on ONE message must not manufacture ambiguity.
            message("aliases", reference = "primary").copy(replyReferenceIds = setOf("primary", "secondary")),
        )
        val index = ReplyIndex(history)
        val authors = listOf(null, "alice@example.org", "alice@example.org/other", "bob@example.org",
            "room@example.org", "room@example.org/Alice", "room@example.org/Bob",
            "mixed@example.org/device", "mixed@example.org/other")
        for (reference in listOf("shared", "ambiguous", "muc", "both", "primary", "secondary", "missing")) {
            for (author in authors) {
                val reply = message("reply").copy(replyToId = reference, replyToJid = author)
                val expected = history.filter { target ->
                    (target.replyReferenceId == reference || reference in target.replyReferenceIds) &&
                        (author == null || if (target.groupChat) target.senderJid == author else
                            target.senderJid.substringBefore('/') == author.substringBefore('/'))
                }.singleOrNull()
                assertEquals("reference=$reference author=$author", expected, index.resolve(reply))
            }
        }
        assertNull(index.resolve(message("no-reference")))
    }

    @Test
    fun replyLookupsDoNotRevisitHistoryEvenWithSharedWireReferences() {
        val history = CountedList((0 until 500).map { i ->
            message("id-$i", author = "sender-$i@example.org/phone", reference = "shared")
        })
        val index = ReplyIndex(history)
        assertEquals(history.size, history.reads)
        history.reads = 0
        repeat(500) { i ->
            val reply = message("reply").copy(replyToId = "shared", replyToJid = "sender-$i@example.org/other")
            assertEquals("id-$i", index.resolve(reply)?.id)
        }
        assertEquals("lookup must not scan history", 0, history.reads)
    }

    @Test
    fun manyChildThreadsUseBoundedHistoryPassesForMainSelectedAndRecentProjection() {
        val parent = thread("parent")
        val count = 250
        val rows = (0 until count).flatMap { i ->
            val root = message("root-$i", reference = "wire-$i").copy(thread = parent)
            val reply = message("reply-$i").copy(thread = thread("child-$i", "parent"), replyToId = "wire-$i")
            listOf(root, reply)
        }
        val history = CountedList(rows)
        val index = ThreadIndex(history)
        assertTrue("thread indexing must have bounded full-history passes", history.reads <= history.size * 3)
        history.reads = 0
        assertEquals(count, index.resolved.size)
        val main = history.projectThreads(null, index)
        assertEquals((0 until count).map { "root-$it" }, main.map { it.id })
        assertTrue(main.all { it.threadSummaries.single().replyCount == 1 })
        val selected = history.projectThreads(thread("child-17", "parent"), index)
        assertEquals(listOf("root-17", "reply-17"), selected.map { it.id })
        val recent = history.recentThreads(emptyList(), index)
        assertEquals(10, recent.size)
        assertEquals(thread("child-249", "parent"), recent.first().thread)
        assertEquals("body-root-249", recent.first().title)
        assertEquals(1, recent.first().replyCount)
        assertTrue("projections must not scan history per child", history.reads <= history.size * 5)
    }

    @Test
    fun threadIndexKeepsFullLineageFallbackRootsAndMucSeparation() {
        val child = thread("same-id", "parent-a")
        val otherLineage = thread("same-id", "parent-b")
        val rows = listOf(
            message("root-a", reference = "a").copy(thread = thread("parent-a")),
            message("root-b", reference = "b").copy(thread = thread("parent-b")),
            message("a1").copy(thread = child, replyToId = "a"),
            message("a2").copy(thread = child, replyToId = "a"),
            message("b1").copy(thread = otherLineage, replyToId = "b"),
            message("orphan1").copy(thread = thread("orphan", "missing"), replyToId = "unknown"),
            message("orphan2").copy(thread = thread("orphan", "missing")),
            message("muc", muc = true).copy(thread = child, replyToId = "a"),
        )
        val index = ThreadIndex(rows)
        assertEquals("root-a", index.resolved[child]?.root?.id)
        assertEquals(listOf("a1", "a2"), index.resolved[child]?.members?.map { it.id })
        assertEquals("root-b", index.resolved[otherLineage]?.root?.id)
        assertEquals(listOf("b1"), index.resolved[otherLineage]?.members?.map { it.id })
        assertEquals("orphan1", index.resolved[thread("orphan", "missing")]?.root?.id)
        assertEquals(listOf("orphan2"), index.resolved[thread("orphan", "missing")]?.members?.map { it.id })
    }

    private class CountedList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size get() = values.size
        override fun get(index: Int): T { reads++; return values[index] }
    }

    private fun thread(id: String, parent: String? = null) =
        ThreadRef(ThreadId.require(id), parent?.let(ThreadId::require))

    private fun message(
        id: String,
        author: String = "peer@example.org",
        reference: String? = null,
        muc: Boolean = false,
    ) = TimelineMessage(id, author, "body-$id", false, null, null, null,
        groupChat = muc, replyReferenceId = reference)
}
