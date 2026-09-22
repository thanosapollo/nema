package org.thanosapollo.nema.chat

import android.app.Application
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VisibleReadAdmissionTest {
    @Test fun observationIdsAreOwnedOnceAndUntrustedListsAreCopied() {
        val source = mutableListOf(TimelineMessage("old", "peer@example.org", "body", false, null, null, null))
        val snapshot = TimelineReadSnapshot(source)
        source.clear()
        val occurrence = ChatRouteOccurrence(ChatRoute("peer@example.org"), 1)
        val request = VisibleReadRequest("account", occurrence, setOf("old"), snapshot)
        assertSame(snapshot, request.observedTimelineIds)
        assertEquals(listOf("old"), request.observedTimelineIds)
        val untrusted = mutableListOf("old")
        val copied = VisibleReadRequest("account", occurrence, setOf("old"), untrusted)
        untrusted.clear()
        assertEquals(listOf("old"), copied.observedTimelineIds)
    }

    /** Holds observed-ID traversal after actual preparation has captured rendered membership. */
    private class PreparationHold(request: VisibleReadRequest) : AutoCloseable {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val release = java.util.concurrent.CountDownLatch(1)
        private val claimed = java.util.concurrent.atomic.AtomicBoolean()
        private val original = request.observedTimelineIds
        private val field = VisibleReadRequest::class.java.getDeclaredField("observedTimelineIds").apply { isAccessible = true }
        private val heldRequest = request
        init {
            field.set(request, object : AbstractList<String>() {
                override val size get() = original.size
                override fun get(index: Int): String {
                    if (Thread.currentThread().stackTrace.any { it.methodName.startsWith("markVisibleConversationRead") ||
                            it.className.contains("markVisibleConversationRead") } && claimed.compareAndSet(false, true)) {
                        entered.complete(Unit)
                        check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "preparation barrier not released" }
                    }
                    return original[index]
                }
            })
        }
        fun release() { release.countDown() }
        override fun close() { release(); field.set(heldRequest, original) }
    }

    @Test fun sameOccurrenceReplacementExcludesLaterInsertionsAndRemovedRows() = runBlocking {
        RoutePresentationFixture().use { f ->
            val store = org.thanosapollo.nema.storage.MessageStore(f.database)
            suspend fun insert(id: String, thread: Boolean = false) {
                store.ingest(org.thanosapollo.nema.storage.IncomingMessage(
                    accountId = f.account, localMessageId = id, peerJid = f.peer, senderJid = f.peer,
                    direction = org.thanosapollo.nema.storage.MessageDirection.INBOUND,
                    messageKind = org.thanosapollo.nema.thread.MessageKind.CHAT,
                    threadId = if (thread) "child" else null, parentThreadId = if (thread) "parent" else null, body = id, archiveOrdinal = null, aliases = emptyList(),
                    sentAtEpochMs = 1L, sentTimeSource = org.thanosapollo.nema.xmpp.transport.MessageTimeSource.MAM,
                ))
            }
            insert("child-root", thread = true); insert("removed"); insert("reprojected")
            val p = f.presenter()
            p.selectPeer(f.peer)
            val ready = withTimeout(5_000) { p.state.first { it.messages.size == 4 && it.conversationsReady && it.draft == "stored A" } }
            val request = VisibleReadRequest(f.account, ready.routeOccurrence, setOf("message-0"), ready.messages.map { it.id })
            PreparationHold(request).use { hold ->
                val pending = async(start = CoroutineStart.UNDISPATCHED) { p.markVisibleConversationRead(request) }
                withTimeout(5_000) { hold.entered.await() }
                val dao = f.database.messageDao()
                dao.deleteMessage(requireNotNull(dao.message(f.account, "removed")))
                dao.updateMessage(requireNotNull(dao.message(f.account, "reprojected")).copy(
                    threadId = "child", parentThreadId = "parent"))
                insert("later-insertion")
                val replaced = withTimeout(5_000) { p.state.first {
                    it.messages.map { row -> row.id }.toSet() == setOf("message-0", "child-root", "later-insertion")
                } }
                assertEquals(ready.routeOccurrence, replaced.routeOccurrence)
                hold.release()
                assertTrue(withTimeout(5_000) { pending.await() })
                assertTrue(requireNotNull(dao.message(f.account, "message-0")).locallyRead)
                assertFalse("later insertions were not observed", requireNotNull(dao.message(f.account, "later-insertion")).locallyRead)
                assertFalse("removed current membership cannot be admitted", requireNotNull(dao.message(f.account, "reprojected")).locallyRead)
                assertFalse(requireNotNull(dao.message(f.account, "message-1")).locallyRead)
                assertNull(dao.message(f.account, "removed"))
            }
        }
    }

    @Test fun bodyReplacementDuringPreparationRetriesCurrentMembership() = runBlocking {
        RoutePresentationFixture().use { f ->
            val p = f.presenter(); p.selectPeer(f.peer)
            val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() && it.conversationsReady && it.draft == "stored A" } }
            val request = VisibleReadRequest(f.account, ready.routeOccurrence, setOf("message-0"))
            PreparationHold(request).use { hold ->
                val pending = async(start = CoroutineStart.UNDISPATCHED) { p.markVisibleConversationRead(request) }
                withTimeout(5_000) { hold.entered.await() }
                val dao = f.database.messageDao()
                dao.updateMessage(requireNotNull(dao.message(f.account, "message-0")).copy(body = "edited"))
                val replacement = withTimeout(5_000) { p.state.first { it.messages.singleOrNull()?.body == "edited" } }
                assertEquals(ready.routeOccurrence, replacement.routeOccurrence)
                hold.release()
                assertTrue(withTimeout(5_000) { pending.await() })
                assertTrue(requireNotNull(dao.message(f.account, "message-0")).locallyRead)
            }
        }
    }

    @Test fun cancellationBeforeAdmissionDoesNotWrite() = heldPreparationRetirement("caller")
    @Test fun closeReopenDuringPreparationCannotAdmitOldOccurrence() = heldPreparationRetirement("route")
    @Test fun accountRetirementDuringPreparationCannotAdmit() = heldPreparationRetirement("account")

    private fun heldPreparationRetirement(kind: String) = runBlocking {
        RoutePresentationFixture().use { f ->
            val p = f.presenter(); p.selectPeer(f.peer)
            val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() && it.conversationsReady && it.draft == "stored A" } }
            val request = VisibleReadRequest(f.account, ready.routeOccurrence, setOf("message-0"))
            PreparationHold(request).use { hold ->
                val pending = async(start = CoroutineStart.UNDISPATCHED) { p.markVisibleConversationRead(request) }
                withTimeout(5_000) { hold.entered.await() }
                when (kind) {
                    "caller" -> pending.cancel()
                    "route" -> {
                        p.closeConversation()
                        withTimeout(5_000) { p.state.first { it.selectedPeer == null } }
                        p.selectPeer(f.peer)
                        withTimeout(5_000) { p.state.first { it.contentStatus == ChatContentStatus.Ready &&
                            it.selectedPeer == f.peer && it.routeOccurrence != ready.routeOccurrence } }
                    }
                    "account" -> { p.close(); f.presenter("replacement") }
                }
                hold.release()
                withTimeout(5_000) { pending.join() }
                if (kind == "caller") assertTrue(pending.isCancelled) else assertFalse(pending.await())
                assertFalse(requireNotNull(f.database.messageDao().message(f.account, "message-0")).locallyRead)
                assertNull(f.database.messageDao().message("replacement", "message-0"))
                assertEquals("stored A", f.repository.observeStoredDraft(DirectConversationKey(f.account, f.peer)).first().body)
            }
        }
    }

    @Test
    fun exactOwnerMembershipAndOccurrenceAreRequiredBeforeDispatch() = runBlocking {
        RoutePresentationFixture().use { f ->
            val p = f.presenter()
            p.selectPeer(f.peer)
            val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() && it.conversationsReady && it.draft == "stored A" } }
            val request = VisibleReadRequest(f.account, ready.routeOccurrence, setOf("message-0"))
            assertFalse(p.markVisibleConversationRead(VisibleReadRequest("replacement", ready.routeOccurrence, request.messageIds)))
            assertFalse(p.markVisibleConversationRead(VisibleReadRequest(f.account, ready.routeOccurrence, setOf("message-1", "missing"))))
            val thread = ThreadRef(ThreadId.require("child"), ThreadId.require("parent"))
            assertFalse(p.markVisibleConversationRead(VisibleReadRequest(f.account,
                ready.routeOccurrence.copy(route = ChatRoute(f.peer, thread)), request.messageIds)))
            // No suspension after navigation: the public Ready projection can still be old.
            p.closeConversation()
            assertFalse(p.markVisibleConversationRead(request))
            p.selectPeer(f.peer)
            val reopened = withTimeout(5_000) { p.state.first {
                it.contentStatus == ChatContentStatus.Ready && it.routeOccurrence != ready.routeOccurrence && it.selectedPeer == f.peer
            } }
            assertFalse(p.markVisibleConversationRead(request))
            assertEquals(false, f.database.messageDao().message(f.account, "message-0")?.locallyRead)
            assertTrue(p.markVisibleConversationRead(VisibleReadRequest(f.account, reopened.routeOccurrence, setOf("message-0", "missing"))))
            assertEquals(true, f.database.messageDao().message(f.account, "message-0")?.locallyRead)
            assertEquals(false, f.database.messageDao().message(f.account, "message-1")?.locallyRead)
        }
    }

    @Test
    fun admittedWriteMayFinishAfterNavigationButLoadingAndFailedCannotAdmit() = runBlocking {
        RoutePresentationFixture().use { f ->
            val p = f.presenter()
            p.selectPeer(f.peer)
            val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() && it.conversationsReady && it.draft == "stored A" } }
            val entered = f.gate.hold()
            val write = async(start = CoroutineStart.UNDISPATCHED) {
                p.markVisibleConversationRead(VisibleReadRequest(f.account, ready.routeOccurrence, setOf("message-0")))
            }
            withTimeout(5_000) { entered.await() }
            p.selectPeer(f.other)
            val loading = withTimeout(5_000) { p.state.first { it.selectedPeer == f.other } }
            assertEquals(ChatContentStatus.Loading, loading.contentStatus)
            assertFalse(p.markVisibleConversationRead(VisibleReadRequest(f.account, loading.routeOccurrence, setOf("message-1"))))
            f.gate.release()
            assertTrue(withTimeout(5_000) { write.await() })
            withTimeout(5_000) { p.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == f.other } }
            f.failLive.complete(Unit)
            val failed = withTimeout(5_000) { p.state.first { it.contentStatus == ChatContentStatus.Failed } }
            assertFalse(p.markVisibleConversationRead(VisibleReadRequest(f.account, failed.routeOccurrence, setOf("message-1"))))
            assertEquals(true, f.database.messageDao().message(f.account, "message-0")?.locallyRead)
            assertEquals(false, f.database.messageDao().message(f.account, "message-1")?.locallyRead)
        }
    }

    @Test
    fun liveRouteRejectsOldReadyWhileProjectionDispatcherIsHeld() = runBlocking {
        RoutePresentationFixture().use { f ->
            val projection = RouteQueryGate()
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + projection)
            val p = DirectChatPresenter(
                org.thanosapollo.nema.account.AccountConfiguration.create(
                    org.thanosapollo.nema.xmpp.transport.AccountId.require(f.account),
                    "${f.account}@example.org", f.account, null, "example.org", null),
                f.repository, scope, enqueue = { _, _ -> true },
            )
            try {
                p.selectPeer(f.peer)
                val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() && it.conversationsReady && it.draft == "stored A" } }
                val entered = projection.hold()
                p.closeConversation() // Real synchronous route mutation, not a forged snapshot.
                withTimeout(5_000) { entered.await() }
                assertSame(ready, p.state.value)
                assertEquals(ChatContentStatus.Ready, p.state.value.contentStatus)
                assertFalse(p.markVisibleConversationRead(VisibleReadRequest(
                    f.account, ready.routeOccurrence, setOf("message-0"))))
                assertEquals(false, f.database.messageDao().message(f.account, "message-0")?.locallyRead)
                projection.release()
                withTimeout(5_000) { p.state.first { it.selectedPeer == null } }
                p.selectPeer(f.peer)
                val next = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() && it.conversationsReady && it.draft == "stored A" } }
                assertNotEquals(ready.routeOccurrence, next.routeOccurrence)
                assertTrue(p.markVisibleConversationRead(VisibleReadRequest(
                    f.account, next.routeOccurrence, setOf("message-0"))))
            } finally {
                projection.release()
                p.close()
                scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() }
            }
        }
    }

    @Test
    fun failedWriteReturnsFalseAndRemainsRetryable() = runBlocking {
        RoutePresentationFixture().use { f ->
            val p = f.presenter()
            p.selectPeer(f.peer)
            val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() && it.conversationsReady && it.draft == "stored A" } }
            val request = VisibleReadRequest(f.account, ready.routeOccurrence, setOf("message-0"))
            f.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_read BEFORE UPDATE OF locallyRead ON messages " +
                    "BEGIN SELECT RAISE(ABORT, 'controlled read failure'); END")
            assertFalse(p.markVisibleConversationRead(request))
            assertEquals(false, f.database.messageDao().message(f.account, "message-0")?.locallyRead)
            f.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_read")
            assertTrue(p.markVisibleConversationRead(request))
            assertEquals(true, f.database.messageDao().message(f.account, "message-0")?.locallyRead)
        }
    }

    @Test
    fun retiringPresenterCancelsHeldWriteWithoutTouchingReplacementAccount() = runBlocking {
        RoutePresentationFixture().use { f ->
            val p = f.presenter()
            p.selectPeer(f.peer)
            val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() && it.conversationsReady && it.draft == "stored A" } }
            val entered = f.gate.hold()
            val write = async(start = CoroutineStart.UNDISPATCHED) {
                p.markVisibleConversationRead(VisibleReadRequest(f.account, ready.routeOccurrence, setOf("message-0")))
            }
            withTimeout(5_000) { entered.await() }
            p.close()
            val replacement = f.presenter("replacement")
            f.gate.release()
            withTimeout(5_000) { write.join() }
            assertTrue(write.isCancelled)
            assertEquals(false, f.database.messageDao().message(f.account, "message-0")?.locallyRead)
            assertNull(f.database.messageDao().message("replacement", "message-0"))
            assertEquals("replacement", replacement.state.value.accountId)
        }
    }

    @Test
    fun joiningWithoutLayoutDoesNotRead() = runBlocking {
        RoutePresentationFixture().use { f ->
            val p = f.presenter()
            assertTrue(p.joinRoom(f.peer))
            withTimeout(5_000) { p.state.first { it.selectedPeerGroupChat && it.contentStatus == ChatContentStatus.Ready } }
            assertEquals(false, f.database.messageDao().message(f.account, "message-0")?.locallyRead)
        }
    }
}
