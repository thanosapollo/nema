package org.thanosapollo.nema.chat

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.storage.AccountEntity
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.xmpp.transport.AccountId

/** Returned draft promises keep their Boolean persistence contract across retirement. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DraftSaveSettlementTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private lateinit var repository: ChatRepository
    private lateinit var controlledScope: TestScope
    private lateinit var presenter: DirectChatPresenter

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "draft-settlement-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        database.accountDao().upsert(AccountEntity(ACCOUNT, SELF, ACCOUNT, null, "example.org", null, null))
        database.accountDao().upsert(AccountEntity(OTHER_ACCOUNT, "other@example.org", OTHER_ACCOUNT, null, "example.org", null, null))
        repository = ChatRepository(database)
        controlledScope = TestScope()
        presenter = newPresenter()
    }

    @After
    fun tearDown() {
        presenter.close()
        controlledScope.cancel()
        controlledScope.runCurrent()
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun closeBeforeDraftWorkerStartsSettlesAllCoalescedWaiters() = runBlocking {
        val before = presenter.updateDraft(draft("before", revision = 1))
        val latest = presenter.updateDraft(draft("latest", revision = 2))
        val other = presenter.updateDraft(draft("other", peer = OTHER_PEER))
        val waiters = listOf(before, latest, other)
        var completions = 0
        waiters.forEach { it.invokeOnCompletion { completions++ } }
        assertTrue(waiters.none { it.isCompleted })

        presenter.close()
        controlledScope.runCurrent()

        assertFailed(waiters)
        assertEquals(waiters.size, completions)
        presenter.close()
        controlledScope.runCurrent()
        assertEquals(waiters.size, completions)
        assertEmptyDrafts()
    }

    @Test
    fun parentCancellationBeforeDraftWorkerStartsSettlesWaiters() = runBlocking {
        val pending = presenter.updateDraft(draft("never started"))
        controlledScope.cancel()
        controlledScope.runCurrent()
        assertFailed(listOf(pending))
        assertEmptyDrafts()
    }

    @Test
    fun postRetirementDraftAdmissionFailsWithoutRunningScheduler() = runBlocking {
        presenter.close()
        val rejected = presenter.updateDraft(draft("rejected"))
        assertFailed(listOf(rejected))
        controlledScope.runCurrent()
        assertEmptyDrafts()
    }

    @Test
    fun closeBehindSendPredecessorSettlesQueuedDraftsWithoutFlush() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        presenter.close()
        presenter = newPresenter(enqueue = { _, _ -> entered.complete(Unit); release.await(); true })
        val send = presenter.sendDraft(draft("sending"))
        awaitPhase { entered.isCompleted }
        val first = presenter.updateDraft(draft("queued"))
        val second = presenter.updateDraft(draft("other queued", peer = OTHER_PEER))
        controlledScope.runCurrent()
        assertTrue(!first.isCompleted && !second.isCompleted)

        presenter.close()
        controlledScope.runCurrent()
        assertFailed(listOf(first, second))
        assertTrue(send.isCancelled)
        release.complete(Unit)
        controlledScope.runCurrent()
        assertEmptyDrafts()
    }

    @Test
    fun closeDuringDetachedBatchWaitingForRoomSettlesEveryKey() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // This is a real Room transaction owner, not a fake save or a thrown cancellation.
        val holder = launch(Dispatchers.IO) {
            database.withTransaction {
                entered.complete(Unit)
                release.await()
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            val first = presenter.updateDraft(draft("blocked first"))
            val coalesced = presenter.updateDraft(draft("latest first", revision = 2))
            val second = presenter.updateDraft(draft("blocked second", peer = OTHER_PEER))
            val third = presenter.updateDraft(draft("blocked thread").copy(
                key = DirectConversationKey(ACCOUNT, PEER, ThreadRef(ThreadId.require("topic"))),
            ))
            // The worker detaches all three keys, then the first save awaits Room's held owner.
            controlledScope.runCurrent()
            assertTrue(listOf(first, coalesced, second, third).none { it.isCompleted })
            assertTrue(!holder.isCompleted)
            assertDetachedBatch()

            presenter.close()
            controlledScope.runCurrent()
            release.complete(Unit)
            withTimeout(5_000) { holder.join() }
            // Cancellation/completion dispatched back from Room must be drained too.
            awaitPhase { first.isCompleted }
            assertFailed(listOf(first, coalesced, second, third))
            assertEmptyDrafts()
            assertEquals(StoredDraft(), repository.observeStoredDraft(
                DirectConversationKey(ACCOUNT, PEER, ThreadRef(ThreadId.require("topic"))),
            ).first())
        } finally {
            release.complete(Unit)
            presenter.close()
            withTimeout(5_000) { holder.join() }
        }
    }

    @Test
    fun retirementAfterFirstCommitKeepsSuccessAndSettlesRemainingDetachedKeys() = runBlocking {
        var notifications = 0
        presenter.close()
        presenter = newPresenter(notifyComposer = { _, _ -> notifications++; presenter.close() })
        val committed = presenter.updateDraft(draft("committed"))
        val second = presenter.updateDraft(draft("not committed", peer = OTHER_PEER))
        val third = presenter.updateDraft(draft("not committed topic").copy(
            key = DirectConversationKey(ACCOUNT, PEER, ThreadRef(ThreadId.require("topic"))),
        ))
        awaitPhase { committed.isCompleted && second.isCompleted }

        assertTrue(committed.await())
        assertFailed(listOf(second, third))
        assertEquals(1, notifications)
        assertEquals(StoredDraft("committed"), repository.observeStoredDraft(draft("").key).first())
        assertEquals(StoredDraft(), repository.observeStoredDraft(draft("", peer = OTHER_PEER).key).first())
        assertEquals(StoredDraft(), repository.observeStoredDraft(
            DirectConversationKey(ACCOUNT, PEER, ThreadRef(ThreadId.require("topic"))),
        ).first())
    }

    @Test
    fun notificationFailureCannotTurnCommittedDraftIntoSaveFailure() = runBlocking {
        var notifications = 0
        presenter.close()
        presenter = newPresenter(notifyComposer = { _, _ -> notifications++; error("typing notification failed") })
        val saved = presenter.updateDraft(draft("committed despite notification"))
        awaitPhase { saved.isCompleted }
        assertTrue(saved.await())
        assertEquals(1, notifications)
        assertEquals(StoredDraft("committed despite notification"), repository.observeStoredDraft(draft("").key).first())
    }

    @Test
    fun missingAccountSaveFailureSettlesEveryWaiterWithoutSuccess() = runBlocking {
        val absentAccount = "absent-owner"
        presenter.close()
        // Read-only startup avoids the unrelated navigation write for this absent owner.
        presenter = newPresenter(accountId = absentAccount, restoreRouteOnStart = true)
        val key = DirectConversationKey(absentAccount, PEER)
        val first = presenter.updateDraft(draft("first").copy(key = key))
        val latest = presenter.updateDraft(draft("latest", revision = 2).copy(key = key))
        val secondKey = DirectConversationKey(absentAccount, OTHER_PEER)
        val second = presenter.updateDraft(draft("second").copy(key = secondKey))
        awaitPhase { listOf(first, latest, second).all { it.isCompleted } }
        assertFailed(listOf(first, latest, second))
        assertEquals(StoredDraft(), repository.observeStoredDraft(key).first())
        assertEquals(StoredDraft(), repository.observeStoredDraft(secondKey).first())
    }

    @Test
    fun successfulCoalescingPreservesLatestReplyAttachmentsAndAccountThreadScope() = runBlocking {
        val reply = DraftReply("reply-id", PEER, "quoted", "Peer")
        val old = presenter.updateDraft(draft("superseded", revision = 1))
        val latestSnapshot = draft("latest", revision = 2).copy(
            reply = reply, attachmentUrl = "https://example.org/file", attachmentName = "file.png",
            attachmentMime = "image/png", attachmentSize = 42L,
        )
        val latest = presenter.updateDraft(latestSnapshot)
        val other = presenter.updateDraft(draft("other peer", peer = OTHER_PEER))
        val threadKey = DirectConversationKey(ACCOUNT, PEER, ThreadRef(ThreadId.require("topic")))
        val topic = presenter.updateDraft(draft("topic").copy(key = threadKey))
        val foreignKey = DirectConversationKey(OTHER_ACCOUNT, PEER)
        val foreign = presenter.updateDraft(draft("wrong account").copy(key = foreignKey))
        awaitPhase { listOf(old, latest, other, topic, foreign).all { it.isCompleted } }
        listOf(old, latest, other, topic).forEach { assertTrue(it.await()) }
        assertFailed(listOf(foreign))
        assertEquals(StoredDraft("latest", reply, latestSnapshot.attachmentUrl, latestSnapshot.attachmentName,
            latestSnapshot.attachmentMime, latestSnapshot.attachmentSize), repository.observeStoredDraft(latestSnapshot.key).first())
        assertEquals(StoredDraft("other peer"), repository.observeStoredDraft(draft("", peer = OTHER_PEER).key).first())
        assertEquals(StoredDraft("topic"), repository.observeStoredDraft(threadKey).first())
        assertEquals(StoredDraft(), repository.observeStoredDraft(foreignKey).first())
    }

    private fun newPresenter(
        enqueue: suspend (AccountConfiguration, DraftSnapshot) -> Boolean = { _, _ -> true },
        notifyComposer: (String, Boolean) -> Unit = { _, _ -> },
        accountId: String = ACCOUNT,
        restoreRouteOnStart: Boolean = false,
    ) = DirectChatPresenter(
        account = AccountConfiguration.create(
            id = AccountId.require(accountId), bareJid = SELF, authenticationId = accountId,
            authorizationId = null, serviceDomain = "example.org", networkEndpoint = null,
        ),
        repository = repository,
        scope = controlledScope,
        enqueue = enqueue,
        notifyComposer = notifyComposer,
        restoreRouteOnStart = restoreRouteOnStart,
    )

    private fun assertDetachedBatch() {
        // Test-side observation only: runCurrent does not drain Room's executor.
        // Verify the exact ownership transfer rather than assuming the batch was captured.
        val lock = DirectChatPresenter::class.java.getDeclaredField("actionLock").apply { isAccessible = true }.get(presenter)
        synchronized(lock) {
            val queued = DirectChatPresenter::class.java.getDeclaredField("coalescedDrafts")
                .apply { isAccessible = true }.get(presenter) as Map<*, *>
            val scheduled = DirectChatPresenter::class.java.getDeclaredField("draftFlushScheduled")
                .apply { isAccessible = true }.getBoolean(presenter)
            assertTrue("all accepted keys must have left the shared queue", queued.isEmpty())
            assertTrue("the detached flush must still own the unresolved waiters", scheduled)
        }
    }

    private suspend fun assertFailed(waiters: List<Deferred<Boolean>>) {
        waiters.forEachIndexed { index, waiter ->
            // Do not await an orphan promise: baseline must fail at settlement, not a timeout.
            assertTrue("draft waiter $index must be complete", waiter.isCompleted)
            assertEquals(false, waiter.await())
        }
    }

    private suspend fun assertEmptyDrafts() {
        assertEquals(StoredDraft(), repository.observeStoredDraft(DirectConversationKey(ACCOUNT, PEER)).first())
        assertEquals(StoredDraft(), repository.observeStoredDraft(DirectConversationKey(ACCOUNT, OTHER_PEER)).first())
    }

    private suspend fun awaitPhase(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) {
                controlledScope.runCurrent()
                yield()
            }
        }
        controlledScope.runCurrent()
    }

    private fun draft(body: String, peer: String = PEER, revision: Long = 1) =
        DraftSnapshot(DirectConversationKey(ACCOUNT, peer), body, revision)

    private companion object {
        const val ACCOUNT = "draft-owner"
        const val OTHER_ACCOUNT = "other-owner"
        const val SELF = "self@example.org"
        const val PEER = "peer@example.org"
        const val OTHER_PEER = "second@example.org"
    }
}
