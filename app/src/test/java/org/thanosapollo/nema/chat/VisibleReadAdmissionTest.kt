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
    @Test
    fun exactOwnerMembershipAndOccurrenceAreRequiredBeforeDispatch() = runBlocking {
        RoutePresentationFixture().use { f ->
            val p = f.presenter()
            p.selectPeer(f.peer)
            val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() } }
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
            val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() } }
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
                val ready = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() } }
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
                val next = withTimeout(5_000) { p.state.first { it.messages.isNotEmpty() } }
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
    fun joiningWithoutLayoutDoesNotRead() = runBlocking {
        RoutePresentationFixture().use { f ->
            val p = f.presenter()
            assertTrue(p.joinRoom(f.peer))
            withTimeout(5_000) { p.state.first { it.selectedPeerGroupChat && it.contentStatus == ChatContentStatus.Ready } }
            assertEquals(false, f.database.messageDao().message(f.account, "message-0")?.locallyRead)
        }
    }
}
