package org.thanosapollo.nema.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
import org.thanosapollo.nema.storage.IncomingMessage
import org.thanosapollo.nema.storage.IdentityAliasKind
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.OutboundIntent
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.storage.PeerEntity
import org.thanosapollo.nema.storage.PeerIdentityStore
import org.thanosapollo.nema.storage.TrustedIdentityAlias
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.ThreadingPolicy
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.transport.MessageTimeSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatRepositoryPresenterTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: NemaDatabase
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "chat-presenter-${UUID.randomUUID()}.db"
        database = NemaDatabase.create(context, databaseName)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        addAccount(ACCOUNT, SELF)
        addAccount(OTHER_ACCOUNT, OTHER_SELF)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun localContentDoesNotWaitForLiveRoomTypingOrRtt() = runBlocking {
        val repository = ChatRepository(database)
        MessageStore(database).ingest(incoming(ACCOUNT, "local", "already on disk"))
        repository.saveDraft(DirectConversationKey(ACCOUNT, PEER), "saved draft")
        val release = CompletableDeferred<Unit>()
        val presenter = DirectChatPresenter(
            accountConfiguration(ACCOUNT, SELF), repository, scope, { _, _ -> true },
            observeRoom = { kotlinx.coroutines.flow.flow { release.await(); emit(null) } },
            observeTyping = { kotlinx.coroutines.flow.flow { release.await(); emit(listOf("typing")) } },
            observeRtt = { kotlinx.coroutines.flow.flow { release.await(); emit("live text") } },
        )
        try {
            presenter.selectPeer(PEER)
            val beforeLive = kotlinx.coroutines.withTimeoutOrNull(3_000) {
                presenter.state.first { it.selectedPeer == PEER && it.contentStatus == ChatContentStatus.Ready }
            }
            release.complete(Unit)
            val ready = withTimeout(3_000) { presenter.state.first { it.typingLabel != null } }
            assertEquals("local", ready.messages.single().id)
            assertEquals("saved draft", ready.draft)
            assertTrue("local content must be ready before live dependencies emit", beforeLive != null)
            assertEquals(null, beforeLive?.typingLabel)
        } finally {
            release.complete(Unit)
            presenter.close()
        }
    }

    @Test
    fun reopeningKeepsLivePeerQueriesButReadsFreshDraft() = runBlocking {
        MessageStore(database).ingest(incoming(ACCOUNT, "local", "already on disk"))
        database.close()
        val aliasQueries = java.util.concurrent.atomic.AtomicInteger()
        database = Room.databaseBuilder(context, NemaDatabase::class.java, databaseName)
            .setQueryCallback({ sql, _ ->
                if (sql.contains("FROM trusted_identity_aliases AS alias")) aliasQueries.incrementAndGet()
            }, java.util.concurrent.Executor { it.run() }).build()
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), repository, scope, { _, _ -> true })
        try {
            presenter.selectPeer(PEER)
            val first = withTimeout(5_000) { presenter.state.first { it.messages.isNotEmpty() } }
            val queries = aliasQueries.get()
            assertTrue(queries > 0)
            presenter.closeConversation()
            withTimeout(5_000) { presenter.state.first { it.selectedPeer == null } }
            repository.saveDraft(DirectConversationKey(ACCOUNT, PEER), "changed while closed")
            presenter.selectPeer(PEER)
            val reopened = withTimeout(5_000) { presenter.state.first {
                it.contentStatus == ChatContentStatus.Ready && it.routeOccurrence != first.routeOccurrence && it.selectedPeer == PEER
            } }
            assertEquals("changed while closed", reopened.draft)
            assertEquals("local", reopened.messages.single().id)
            assertEquals("reopening must not restart peer timeline/alias queries", queries, aliasQueries.get())
            MessageStore(database).ingest(incoming(ACCOUNT, "new", "invalidation"))
            withTimeout(5_000) { presenter.state.first { it.messages.size == 2 } }
            assertTrue(aliasQueries.get() > queries)
        } finally {
            presenter.close()
        }
    }

    @Test
    fun retiredPeerQueriesCannotBecomeReadyDuringCancellation() = retiredQueriesCannotBecomeReady(false)

    @Test
    fun retiredFullThreadLineageCannotBecomeReadyDuringCancellation() = retiredQueriesCannotBecomeReady(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun retiredQueriesCannotBecomeReady(threadDetour: Boolean) = runTest {
        val message = incoming(ACCOUNT, "retired", "actionable content").let {
            if (threadDetour) it.copy(threadId = "child", parentThreadId = "original-parent") else it
        }
        MessageStore(database).ingest(message)
        database.close()
        val gate = RouteQueryGate(StandardTestDispatcher(testScheduler), localQueryOnly = true)
        var draftReads = 0
        database = Room.databaseBuilder(context, NemaDatabase::class.java, databaseName)
            .setQueryCoroutineContext(gate).allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> if (sql.contains("FROM message_drafts")) draftReads++ },
                java.util.concurrent.Executor { it.run() }).build()
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), repository, backgroundScope, { _, _ -> true })
        try {
            presenter.selectPeer(PEER)
            presenter.state.first { it.messages.isNotEmpty() }
            val original = ThreadRef(ThreadId.require("child"), ThreadId.require("original-parent"))
            if (threadDetour) {
                assertTrue(presenter.continueThread(original))
                presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread == original }
            }
            val retired = presenter.state.value
            val entered = gate.hold()
            // Start a real local invalidation query before cancelling its subscription.
            MessageStore(database).ingest(message.copy(localMessageId = "fresh", aliases = emptyList()))
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (!entered.isCompleted) { testScheduler.runCurrent(); yield() }
                }
            }
            assertTrue("old local query must be parked", entered.isCompleted)
            if (threadDetour) presenter.continueThread(original.copy(parentId = ThreadId.require("other-parent")))
            else presenter.selectPeer(OTHER_PEER)
            runCurrent()
            val draftsBeforeReturn = draftReads
            if (threadDetour) presenter.continueThread(original) else presenter.selectPeer(PEER)
            runCurrent()
            assertTrue("fresh draft query must finish independently", draftReads > draftsBeforeReturn)
            val pending = presenter.state.value
            assertTrue(pending.routeOccurrence != retired.routeOccurrence)
            assertEquals(retired.routeOccurrence.route, pending.routeOccurrence.route)
            assertEquals("retired subscription must not be relabelled Ready", ChatContentStatus.Loading, pending.contentStatus)
            assertTrue(pending.messages.isEmpty())
            assertTrue(!presenter.markVisibleConversationRead(VisibleReadRequest(
                ACCOUNT, pending.routeOccurrence, setOf("retired"), listOf("retired"),
            )))
            gate.release()
            val fresh = presenter.state.first {
                it.routeOccurrence == pending.routeOccurrence && it.contentStatus == ChatContentStatus.Ready
            }
            assertTrue(fresh.messages.any { it.id == "fresh" })
        } finally {
            gate.release()
            presenter.close()
            runCurrent()
        }
    }

    @Test
    fun retainedLocalFailureRequiresReopenAndCancellationStopsRetry() = retainedLocalFailure(false)

    @Test
    fun retainedLocalFailureWhileHomeAndCloseStopsLiveQueries() = retainedLocalFailure(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun retainedLocalFailure(whileHome: Boolean) = runTest {
        MessageStore(database).ingest(incoming(ACCOUNT, "local", "actionable content"))
        database.close()
        var fail = false
        var reads = 0
        val failures = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        database = Room.databaseBuilder(context, NemaDatabase::class.java, databaseName)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).allowMainThreadQueries()
            .openHelperFactory { configuration ->
                val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(configuration)
                fun wrap(db: androidx.sqlite.db.SupportSQLiteDatabase) = object : androidx.sqlite.db.SupportSQLiteDatabase by db {
                    override fun query(query: androidx.sqlite.db.SupportSQLiteQuery): android.database.Cursor = query(query, null)
                    override fun query(query: androidx.sqlite.db.SupportSQLiteQuery, signal: android.os.CancellationSignal?): android.database.Cursor {
                        if (query.sql.contains("FROM direct_thread_sessions")) {
                            reads++
                            if (fail) {
                                failures.trySend(Unit)
                                return db.query("SELECT * FROM deliberately_missing_local_source")
                            }
                        }
                        return if (signal == null) db.query(query) else db.query(query, signal)
                    }
                }
                object : androidx.sqlite.db.SupportSQLiteOpenHelper by helper {
                    override val writableDatabase get() = wrap(helper.writableDatabase)
                    override val readableDatabase get() = wrap(helper.readableDatabase)
                }
            }.build()
        val owner = SupervisorJob(backgroundScope.coroutineContext[Job])
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), ChatRepository(database),
            CoroutineScope(backgroundScope.coroutineContext + owner), { _, _ -> true })
        suspend fun invalidate(peer: String, thread: String) {
            database.messageDao().insertPeer(org.thanosapollo.nema.storage.PeerEntity(ACCOUNT, peer))
            database.messageDao().insertThread(
                org.thanosapollo.nema.storage.MessageThreadEntity(ACCOUNT, peer, MessageKind.CHAT, thread, null),
            )
            database.messageDao().saveDirectThreadSession(
                org.thanosapollo.nema.storage.DirectThreadSessionEntity(ACCOUNT, peer, threadId = thread),
            )
        }
        suspend fun ready(peer: String) = presenter.state.first {
            it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == peer
        }
        try {
            presenter.selectPeer(PEER)
            assertEquals("local", ready(PEER).messages.single().id)
            if (whileHome) {
                presenter.closeConversation()
                presenter.state.first { it.selectedPeer == null }
            }
            fail = true
            invalidate(PEER, "failed-session")
            failures.receive()
            if (whileHome) {
                runCurrent()
                assertEquals(null, presenter.state.value.selectedPeer)
                assertEquals(ChatContentStatus.Ready, presenter.state.value.contentStatus)
                assertTrue(presenter.state.value.messages.isEmpty())
                presenter.selectPeer(PEER)
            }
            val failed = presenter.state.first { it.contentStatus == ChatContentStatus.Failed }
            assertTrue(failed.messages.isEmpty())
            assertEquals("", failed.draft)
            assertEquals(null, failed.currentSession)
            var attempts = reads
            testScheduler.advanceTimeBy(60_000); runCurrent()
            assertEquals("failure must not timer-retry", attempts, reads)
            presenter.closeConversation()
            runCurrent()
            testScheduler.advanceTimeBy(60_000); runCurrent()
            assertEquals("Home is not a retry request", attempts, reads)
            fail = false
            presenter.selectPeer(PEER)
            assertEquals("local", ready(PEER).messages.single().id)
            fail = true
            invalidate(PEER, "fail-again")
            presenter.state.first { it.contentStatus == ChatContentStatus.Failed }
            attempts = reads
            fail = false
            presenter.selectPeer(OTHER_PEER)
            ready(OTHER_PEER)
            assertEquals("replacement must cancel A's waiting retry", attempts + 1, reads)
            if (whileHome) {
                presenter.closeConversation()
                presenter.state.first { it.selectedPeer == null }
            } else {
                fail = true
                invalidate(OTHER_PEER, "close-failed")
                presenter.state.first { it.contentStatus == ChatContentStatus.Failed }
            }
            presenter.close()
            owner.children.toList().forEach { it.join() }
            attempts = reads
            invalidate(OTHER_PEER, "after-close")
            testScheduler.advanceTimeBy(60_000); runCurrent()
            assertEquals("close stops retained observers and retry waits", attempts, reads)
        } finally {
            presenter.close()
            owner.cancel()
            owner.join()
            failures.close()
        }
    }

    @Test
    fun conversationsUseLatestMessagePerPeerAndIncludeGroupchats() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "old", "old body"))
        store.ingest(incoming(ACCOUNT, "new", "new body"))
        store.ingest(
            IncomingMessage(
                accountId = ACCOUNT,
                localMessageId = "room-msg",
                peerJid = "room@conference.example.org",
                senderJid = "room@conference.example.org/alice",
                direction = MessageDirection.INBOUND,
                messageKind = MessageKind.GROUPCHAT,
                threadId = null,
                parentThreadId = null,
                body = "room hello",
                archiveOrdinal = null,
                aliases = emptyList(),
            ),
        )
        val conversations = ChatRepository(database).observeConversations(ACCOUNT).first()
        assertEquals(
            listOf("room@conference.example.org", PEER),
            conversations.map(ConversationSummary::peerJid),
        )
        assertEquals(listOf("room hello", "new body"), conversations.map(ConversationSummary::preview))
        assertEquals(listOf(true, false), conversations.map(ConversationSummary::groupChat))
    }

    @Test
    fun inboundAfterLastReadCountsAsUnreadUntilOpened() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "first", "first body"))
        val repository = ChatRepository(database)
        assertEquals(1, repository.observeConversations(ACCOUNT).first().single().unreadCount)

        repository.markMessagesRead(ACCOUNT, PEER, listOf("first"))
        assertEquals(0, repository.observeConversations(ACCOUNT).first().single().unreadCount)

        store.ingest(incoming(ACCOUNT, "second", "second body"))
        assertEquals(1, repository.observeConversations(ACCOUNT).first().single().unreadCount)
    }

    @Test
    fun historicalMamRowsStayReadInCachedAndObservedConversations() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "history", "history").copy(unreadEligible = false))
        store.ingest(incoming(ACCOUNT, "live", "live"))
        val repository = ChatRepository(database)

        assertEquals(1, repository.cachedConversations(ACCOUNT).single().unreadCount)
        assertEquals(1, repository.observeConversations(ACCOUNT).first().single().unreadCount)
    }

    @Test
    fun selectionLeavesUnreadUntilVisibleBoundaryReadsThroughEarlierHistory() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "first", "first body"))
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
        )
        presenter.state.first { it.conversations.singleOrNull()?.unreadCount == 1 }
        assertTrue(presenter.selectPeer(PEER))
        presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == PEER }
        assertEquals(1, repository.observeConversations(ACCOUNT).first().single().unreadCount)

        store.ingest(incoming(ACCOUNT, "later", "later body"))
        val ready = presenter.state.first { it.messages.size == 2 && it.conversations.single().unreadCount == 2 }
        assertTrue(presenter.markVisibleConversationRead(VisibleReadRequest(ACCOUNT, ready.routeOccurrence, setOf("later"), ready.messages.map { it.id })))
        withTimeout(5_000) { presenter.state.first { it.conversations.single().unreadCount == 0 } }
        assertEquals(true, database.messageDao().message(ACCOUNT, "first")?.locallyRead)
        assertEquals(true, database.messageDao().message(ACCOUNT, "later")?.locallyRead)
        presenter.close()
    }

    @Test
    fun readThroughUsesObservedChronologyNotInsertionSequenceOrLaterProjection() = runBlocking {
        val store = MessageStore(database)
        val repository = ChatRepository(database)
        repository.markRoom(ACCOUNT, PEER)
        // Insert the newest first, exactly as a reordered archive can arrive.
        for ((id, time) in listOf("newest" to 3000L, "boundary" to 2000L, "oldest" to 1000L)) {
            store.ingest(incoming(ACCOUNT, id, id).copy(
                messageKind = MessageKind.GROUPCHAT, sentAtEpochMs = time,
                sentTimeSource = MessageTimeSource.MAM,
            ))
        }
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), repository, scope,
            enqueue = { _, _ -> true })
        try {
            presenter.selectPeer(PEER)
            val observed = withTimeout(5_000) { presenter.state.first { it.messages.size == 3 } }
            assertEquals(listOf("oldest", "boundary", "newest"), observed.messages.map { it.id })
            val request = VisibleReadRequest(ACCOUNT, observed.routeOccurrence, setOf("boundary"),
                observed.messages.map { it.id })
            // A late backdated arrival is now in the presenter's prefix but was not observed.
            store.ingest(incoming(ACCOUNT, "late-history", "late").copy(
                messageKind = MessageKind.GROUPCHAT, sentAtEpochMs = 500L,
                sentTimeSource = MessageTimeSource.MAM,
            ))
            withTimeout(5_000) { presenter.state.first { it.messages.size == 4 } }
            assertTrue(presenter.markVisibleConversationRead(request))
            for (id in listOf("oldest", "boundary"))
                assertEquals(id, true, database.messageDao().message(ACCOUNT, id)?.locallyRead)
            for (id in listOf("newest", "late-history"))
                assertEquals(id, false, database.messageDao().message(ACCOUNT, id)?.locallyRead)
            assertEquals(2, repository.cachedConversations(ACCOUNT).single().unreadCount)
        } finally { presenter.close() }
    }

    @Test
    fun parentReadThroughPreservesIsolatedChildUntilThatContextIsRead() = runBlocking {
        val store = MessageStore(database)
        val repository = ChatRepository(database)
        store.ingest(incoming(ACCOUNT, "main-old", "old"))
        store.ingest(incoming(ACCOUNT, "child-root", "root", "child", "parent"))
        store.ingest(incoming(ACCOUNT, "child-reply", "reply", "child", "parent"))
        store.ingest(incoming(ACCOUNT, "main-latest", "latest"))
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), repository, scope,
            enqueue = { _, _ -> true })
        try {
            presenter.selectPeer(PEER)
            val main = withTimeout(5_000) { presenter.state.first { it.messages.size == 3 } }
            assertEquals(listOf("main-old", "child-root", "main-latest"), main.messages.map { it.id })
            assertTrue(presenter.markVisibleConversationRead(VisibleReadRequest(
                ACCOUNT, main.routeOccurrence, setOf("main-latest"), main.messages.map { it.id })))
            assertEquals(1, repository.cachedConversations(ACCOUNT).single().unreadCount)
            assertEquals(false, database.messageDao().message(ACCOUNT, "child-reply")?.locallyRead)
            presenter.continueThread(ThreadRef(ThreadId.require("child"), ThreadId.require("parent")))
            val child = withTimeout(5_000) { presenter.state.first {
                it.selectedThread != null && it.contentStatus == ChatContentStatus.Ready
            } }
            assertEquals(listOf("child-root", "child-reply"), child.messages.map { it.id })
            store.ingest(incoming(ACCOUNT, "main-arrival", "new"))
            assertTrue(presenter.markVisibleConversationRead(VisibleReadRequest(
                ACCOUNT, child.routeOccurrence, setOf("child-reply"), child.messages.map { it.id })))
            assertEquals(true, database.messageDao().message(ACCOUNT, "child-reply")?.locallyRead)
            assertEquals(false, database.messageDao().message(ACCOUNT, "main-arrival")?.locallyRead)
            assertEquals(1, repository.cachedConversations(ACCOUNT).single().unreadCount)
        } finally { presenter.close() }
    }

    @Test
    fun cachedConversationsReadSqliteWithoutArchiveRanking() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "hello", "cached body"))
        database.messageDao().insertPeer(
            PeerEntity(ACCOUNT, "room@conference.example.org", room = true),
        )

        val cached = ChatRepository(database).cachedConversations(ACCOUNT)
        assertEquals(listOf(PEER, "room@conference.example.org"), cached.map(ConversationSummary::peerJid))
        assertEquals(listOf("cached body", ""), cached.map(ConversationSummary::preview))
        assertEquals(listOf(false, true), cached.map(ConversationSummary::groupChat))
    }

    @Test
    fun firstConversationPaintMatchesLiveLatestPreview() = runBlocking {
        val store = MessageStore(database)
        val repository = ChatRepository(database)
        store.ingest(
            incoming(ACCOUNT, "latest", "latest body").copy(
                archiveOrdinal = 2,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 2_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "old", "old body").copy(
                archiveOrdinal = 1,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 1_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )

        assertEquals("latest body", repository.cachedConversations(ACCOUNT).single().preview)
        assertEquals("latest body", repository.observeConversations(ACCOUNT).first().single().preview)
    }

    @Test
    fun firstReadyKeepsEntireChronologicalTimelineAmongLaterIngestedHistory() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "latest-live", "latest body").copy(
                sentAtEpochMs = 5_000L,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        repeat(90) { index ->
            store.ingest(
                incoming(ACCOUNT, "old-$index", "old $index").copy(
                    sentAtEpochMs = 1_000L + index,
                    sentTimeSource = MessageTimeSource.MAM,
                ),
            )
        }

        val presenter = DirectChatPresenter(
            accountConfiguration(ACCOUNT, SELF), ChatRepository(database), scope,
            enqueue = { _, _ -> true },
        )
        val firstReady = async(start = CoroutineStart.UNDISPATCHED) {
            presenter.state.first { it.selectedPeer == PEER && it.contentStatus == ChatContentStatus.Ready }
        }
        presenter.selectPeer(PEER)
        val messages = withTimeout(5_000) { firstReady.await() }.messages
        assertEquals((0..89).map { "old-$it" } + "latest-live", messages.map { it.id })
        presenter.close()
    }

    @Test
    fun firstReadySettlesEmptyConversationAndEmptySelectedThread() = runBlocking {
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), ChatRepository(database), scope,
            enqueue = { _, _ -> true })
        presenter.selectPeer(PEER)
        withTimeout(5_000) {
            assertTrue(presenter.state.first { it.selectedPeer == PEER && it.contentStatus == ChatContentStatus.Ready }.messages.isEmpty())
        }
        MessageStore(database).ingest(incoming(ACCOUNT, "outside-thread", "outside"))
        val thread = ThreadRef(ThreadId.require("empty-thread"))
        presenter.continueThread(thread)
        withTimeout(5_000) {
            assertTrue(presenter.state.first { it.selectedThread == thread && it.contentStatus == ChatContentStatus.Ready }.messages.isEmpty())
        }
        presenter.close()
    }

    @Test
    fun firstReadyIncludesCorrectionTrustedReplyReactionAndThreadSummary() = runBlocking {
        val store = MessageStore(database)
        val repository = ChatRepository(database)
        store.ingest(incoming(ACCOUNT, "root", "original").copy(
            aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "root-wire")),
        ))
        val session = requireNotNull(repository.observeCurrentSession(ACCOUNT, PEER).first())
        store.ingest(incoming(ACCOUNT, "edit", "corrected").copy(replaceId = "root-wire"))
        store.ingest(incoming(ACCOUNT, "reply", "reply body", threadId = session.id.value).copy(
            replyToId = "root-wire", replyToJid = PEER,
        ))
        val child = ThreadRef(ThreadId.require("child"), session.id)
        store.ingest(incoming(ACCOUNT, "child-reply", "child body", threadId = child.id.value,
            parentThreadId = session.id.value).copy(replyToId = "root-wire", replyToJid = PEER))
        // Install one durable reaction fact before any projection subscribes.
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO message_reactions (accountId, peerJid, senderBareJid, targetKey, localMessageId, wireTargetId, emojis, updatedAtMs, revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(ACCOUNT, PEER, PEER, "local:root", "root", "root-wire", "👍", 1L, 1L),
        )
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), repository, scope,
            enqueue = { _, _ -> true })
        val firstReady = async(start = CoroutineStart.UNDISPATCHED) {
            presenter.state.first { it.selectedPeer == PEER && it.contentStatus == ChatContentStatus.Ready }
        }
        presenter.selectPeer(PEER)
        val ready = withTimeout(5_000) { firstReady.await() }
        assertEquals(listOf("root", "reply"), ready.messages.map { it.id })
        val root = ready.messages.first()
        assertEquals("corrected", root.body)
        assertTrue(root.edited)
        assertTrue("root-wire" in root.replyReferenceIds)
        assertEquals("corrected", ready.messages.last().reply?.body)
        assertEquals(1, root.reactions.size)
        assertEquals(child, root.threadSummaries.single().thread)
        assertEquals(1, root.threadSummaries.single().replyCount)
        assertTrue(ready.recentThreads.any { it.thread == child })
        presenter.close()
    }

    @Test
    fun correctionProjectsOneStableEditedMessageAndUpdatedPreview() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "original", "original body").copy(
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "wire-original")),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "correction", "corrected body").copy(
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "wire-correction")),
                replaceId = "wire-original",
            ),
        )

        val timeline = ChatRepository(database).observeTimeline(ACCOUNT, PEER).first()
        val summary = ChatRepository(database).observeConversations(ACCOUNT).first().single()

        assertEquals(1, timeline.size)
        assertEquals("original", timeline.single().id)
        assertEquals("corrected body", timeline.single().body)
        assertTrue(timeline.single().edited)
        assertEquals("corrected body", summary.preview)
    }

    @Test
    fun archiveBackfillDoesNotReplaceLatestConversationPreview() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "latest", "latest body").copy(
                archiveOrdinal = 2,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 2_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "old", "old body").copy(
                archiveOrdinal = 1,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 1_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )

        val conversation = ChatRepository(database).observeConversations(ACCOUNT).first().single()

        assertEquals("latest body", conversation.preview)
        assertEquals(2_000L, conversation.sentAtEpochMs)
    }

    @Test
    fun roomArchiveBackfillDoesNotReplaceLatestConversationPreview() = runBlocking {
        val store = MessageStore(database)
        val room = "jabber@conference.example.org"
        store.ingest(
            incoming(ACCOUNT, "latest", "latest room body").copy(
                peerJid = room,
                senderJid = "$room/alice",
                messageKind = MessageKind.GROUPCHAT,
                archiveOrdinal = 2,
                archiveAuthority = room,
                archiveScope = room,
                sentAtEpochMs = 2_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "old", "Group Christian: that's great").copy(
                peerJid = room,
                senderJid = "$room/christian",
                messageKind = MessageKind.GROUPCHAT,
                archiveOrdinal = 1,
                archiveAuthority = room,
                archiveScope = room,
                sentAtEpochMs = 1_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )

        val conversation = ChatRepository(database).observeConversations(ACCOUNT).first().single()

        assertEquals("latest room body", conversation.preview)
        assertEquals("alice", conversation.previewSender)
        assertEquals(2_000L, conversation.sentAtEpochMs)
    }

    @Test
    fun delayedUnarchivedMessageIsPlacedBySentTimeWithoutReorderingArchiveSpine() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "old", "old").copy(
                archiveOrdinal = 1,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 20_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "current", "current").copy(
                archiveOrdinal = 2,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 12_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "delayed", "delayed").copy(
                sentAtEpochMs = 10_000,
                sentTimeSource = MessageTimeSource.DELAYED,
            ),
        )

        val timeline = ChatRepository(database).observeTimeline(ACCOUNT, PEER).first()
        val summary = ChatRepository(database).observeConversations(ACCOUNT).first().single()

        assertEquals(listOf("delayed", "old", "current"), timeline.map(TimelineMessage::id))
        assertEquals(listOf(10_000L, 20_000L, 12_000L), timeline.map(TimelineMessage::sentAtEpochMs))
        assertEquals("old", summary.preview)
        assertEquals(
            "old",
            ChatRepository(database).cachedConversations(ACCOUNT).single().preview,
        )
    }

    @Test
    fun conversationSummaryKeepsIndependentArchiveSpinesSeparate() = runBlocking {
        val store = MessageStore(database)
        val unequalPeer = "mixed-unequal@example.org"
        store.ingest(
            incoming(ACCOUNT, "direct-large-ordinal", "direct lower time").copy(
                peerJid = unequalPeer,
                senderJid = unequalPeer,
                archiveOrdinal = 100,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 1_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "room-small-ordinal", "room higher time").copy(
                peerJid = unequalPeer,
                senderJid = "$unequalPeer/alice",
                messageKind = MessageKind.GROUPCHAT,
                archiveOrdinal = 1,
                archiveAuthority = unequalPeer,
                archiveScope = unequalPeer,
                sentAtEpochMs = 2_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        val nullPeer = "mixed-null@example.org"
        store.ingest(
            incoming(ACCOUNT, "direct-null-time", "direct null").copy(
                peerJid = nullPeer,
                senderJid = nullPeer,
                archiveOrdinal = 7,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "room-null-time", "room null").copy(
                peerJid = nullPeer,
                senderJid = "$nullPeer/alice",
                messageKind = MessageKind.GROUPCHAT,
                archiveOrdinal = 7,
                archiveAuthority = nullPeer,
                archiveScope = nullPeer,
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE messages SET sentAtEpochMs = NULL, sentTimeSource = NULL WHERE peerJid = ?",
            arrayOf(nullPeer),
        )

        val rows = database.messageDao().observeConversationSummaries(ACCOUNT).first()
            .associateBy { it.peerJid }
        val summaries = ChatRepository(database).observeConversations(ACCOUNT).first()
            .associateBy { it.peerJid }

        assertEquals(2, rows.size)
        assertEquals("room higher time", rows.getValue(unequalPeer).preview)
        assertEquals("room null", rows.getValue(nullPeer).preview)
        assertEquals(rows.keys, summaries.keys)
        assertEquals(
            summaries.getValue(unequalPeer).preview,
            ChatRepository(database).observeTimeline(ACCOUNT, unequalPeer).first().last().body,
        )
        assertEquals(
            summaries.getValue(nullPeer).preview,
            ChatRepository(database).observeTimeline(ACCOUNT, nullPeer).first().last().body,
        )
    }

    @Test
    fun homePreviewUsesLatestSentTimeThenSequence() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "equal-archive", "archive tie winner").copy(
                archiveOrdinal = 1,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 5_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "equal-loose", "loose tie").copy(
                sentAtEpochMs = 5_000,
                sentTimeSource = MessageTimeSource.DELAYED,
            ),
        )

        val repository = ChatRepository(database)
        val summary = repository.observeConversations(ACCOUNT).first().single()
        val cached = repository.cachedConversations(ACCOUNT).single()

        assertEquals("loose tie", summary.preview)
        assertEquals("loose tie", cached.preview)
    }

    @Test
    fun conversationSummaryQueryReturnsOneLatestRowAndAvatarPerPeer() = runBlocking {
        val store = MessageStore(database)
        repeat(40) { index ->
            store.ingest(
                incoming(ACCOUNT, "peer-history-$index", "peer history $index").copy(
                    sentAtEpochMs = index.toLong(),
                    sentTimeSource = MessageTimeSource.LOCAL,
                ),
            )
            store.ingest(
                incoming(ACCOUNT, "other-history-$index", "other history $index").copy(
                    peerJid = OTHER_PEER,
                    senderJid = OTHER_PEER,
                    sentAtEpochMs = index.toLong(),
                    sentTimeSource = MessageTimeSource.LOCAL,
                ),
            )
        }
        store.ingest(
            incoming(ACCOUNT, "archive-skew-old", "archive old").copy(
                archiveOrdinal = 1,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 10_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "archive-latest", "archive latest").copy(
                archiveOrdinal = 2,
                archiveAuthority = SELF,
                archiveScope = "ACCOUNT",
                sentAtEpochMs = 5_000,
                sentTimeSource = MessageTimeSource.MAM,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "loose-latest", "loose latest").copy(
                sentAtEpochMs = 6_000,
                sentTimeSource = MessageTimeSource.DELAYED,
            ),
        )
        val firstAvatar = byteArrayOf(1, 2, 3)
        val secondAvatar = byteArrayOf(4, 5, 6)
        database.messageDao().upsertPeer(PeerEntity(ACCOUNT, PEER, photoBytes = firstAvatar))
        database.messageDao().upsertPeer(PeerEntity(ACCOUNT, OTHER_PEER, photoBytes = secondAvatar))

        val rows = database.messageDao().observeConversationSummaries(ACCOUNT).first()
        val timeline = ChatRepository(database).observeTimeline(ACCOUNT, PEER).first()

        assertEquals(listOf(OTHER_PEER, PEER), rows.map { it.peerJid }.sorted())
        assertEquals("loose latest", rows.single { it.peerJid == PEER }.preview)
        assertEquals(rows.single { it.peerJid == PEER }.preview, timeline.last().body)
        assertTrue(rows.single { it.peerJid == PEER }.photoBytes.contentEquals(firstAvatar))
        assertTrue(rows.single { it.peerJid == OTHER_PEER }.photoBytes.contentEquals(secondAvatar))
    }

    @Test
    fun joinedRoomWithoutMessagesStillAppearsOnHome() = runBlocking {
        ChatRepository(database).markRoom(ACCOUNT, "room@conference.example.org")
        val conversations = ChatRepository(database).observeConversations(ACCOUNT).first()
        assertEquals(listOf("room@conference.example.org"), conversations.map(ConversationSummary::peerJid))
        assertEquals(listOf(true), conversations.map(ConversationSummary::groupChat))
    }

    @Test
    fun markConversationReadDoesNotClobberRoomOrNickname() = runBlocking {
        val repository = ChatRepository(database)
        repository.markRoom(ACCOUNT, PEER)
        PeerIdentityStore(database.messageDao()).saveLocalNickname(ACCOUNT, PEER, "Ada")

        repository.markMessagesRead(ACCOUNT, PEER, listOf("first"))

        val peer = database.messageDao().peer(ACCOUNT, PEER)
        assertEquals(true, peer?.room)
        assertEquals("Ada", peer?.localNickname)
    }

    @Test
    fun venueFollowsPeerRoomNotMessageKind() = runBlocking {
        val store = MessageStore(database)
        val room = "room@conference.example.org"
        store.ingest(
            incoming(ACCOUNT, "room-msg", "room hello").copy(
                peerJid = room,
                senderJid = "$room/alice",
                messageKind = MessageKind.GROUPCHAT,
            ),
        )
        PeerIdentityStore(database.messageDao()).saveRoom(ACCOUNT, room, false)
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
        )
        assertTrue(presenter.selectPeer(room))
        presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == room && it.messages.isNotEmpty() }
        assertEquals(false, repository.observeConversations(ACCOUNT).first().single().groupChat)
        assertEquals(false, presenter.state.value.selectedPeerGroupChat)
        presenter.close()
    }

    @Test
    fun conversationsAndTimelineStayAccountScoped() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "account-message", "account body"))
        store.ingest(incoming(OTHER_ACCOUNT, "other-message", "other body"))
        val repository = ChatRepository(database)

        val conversations = repository.observeConversations(ACCOUNT).first()
        val timeline = repository.observeTimeline(ACCOUNT, PEER).first()

        assertEquals(listOf(PEER), conversations.map(ConversationSummary::peerJid))
        assertEquals(listOf("account body"), timeline.map(TimelineMessage::body))
    }

    @Test
    fun semanticReplyProjectionResolvesTrustedTargetAndFallback() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "target", "original body").copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "target-wire-id"),
                ),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "reply", "answer").copy(
                replyToId = "target-wire-id",
                replyToJid = PEER,
                replyFallbackBody = "> peer wrote:\n> fallback body\n",
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "fallback-reply", "later answer").copy(
                replyToId = "not-synchronized",
                replyToJid = "alice@example.org",
                replyFallbackBody = "> Alice wrote:\n> fallback body\n",
            ),
        )

        val timeline = ChatRepository(database).observeTimeline(ACCOUNT, PEER).first()

        assertEquals("target-wire-id", timeline.first().replyReferenceId)
        assertEquals(MessageReplyPresentation("peer", "original body"), timeline[1].reply)
        assertEquals(MessageReplyPresentation("Alice", "fallback body"), timeline[2].reply)
    }

    @Test
    fun peerIdentityRequestsCarryPresenterAccount() = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<AccountId, Set<String>>>()
        fun presenter(accountId: String, bareJid: String) = DirectChatPresenter(
            account = accountConfiguration(accountId, bareJid),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ -> true },
            ensurePeerIdentities = { owner, peers -> requests += owner to peers.toSet() },
        )
        val first = presenter(ACCOUNT, SELF)
        val second = presenter(OTHER_ACCOUNT, OTHER_SELF)

        assertTrue(first.selectPeer(PEER))
        assertTrue(second.selectPeer(PEER))
        while (requests.none { it == AccountId.require(ACCOUNT) to setOf(PEER) } ||
            requests.none { it == AccountId.require(OTHER_ACCOUNT) to setOf(PEER) }
        ) {
            yield()
        }

        assertTrue(requests.any { it == AccountId.require(ACCOUNT) to setOf(PEER) })
        assertTrue(requests.any { it == AccountId.require(OTHER_ACCOUNT) to setOf(PEER) })
        first.close()
        second.close()
    }

    @Test
    fun childTopicUsesFirstMemberAsRootWhenReplyMetadataMissing() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "parent", "main session", threadId = "session"))
        store.ingest(
            incoming(
                ACCOUNT,
                "child-root",
                "A dedicated child topic",
                threadId = "child",
                parentThreadId = "session",
            ),
        )
        val repository = ChatRepository(database)
        val child = ThreadRef(ThreadId.require("child"), ThreadId.require("session"))
        val rootOnlyOverview = repository.observeTimeline(ACCOUNT, PEER).first()
        assertEquals(listOf("parent", "child-root"), rootOnlyOverview.map(TimelineMessage::id))
        assertEquals(child, rootOnlyOverview.single { it.id == "child-root" }.threadSummaries.single().thread)
        assertEquals(0, rootOnlyOverview.single { it.id == "child-root" }.threadSummaries.single().replyCount)
        store.ingest(
            incoming(
                ACCOUNT,
                "child-reply",
                "reply",
                threadId = "child",
                parentThreadId = "session",
            ),
        )

        val overview = repository.observeTimeline(ACCOUNT, PEER).first()
        val dedicated = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, child)).first()

        assertEquals(listOf("parent", "child-root"), overview.map(TimelineMessage::id))
        assertEquals(child, overview.single { it.id == "child-root" }.threadSummaries.single().thread)
        assertEquals(1, overview.single { it.id == "child-root" }.threadSummaries.single().replyCount)
        assertEquals(listOf("child-root", "child-reply"), dedicated.map(TimelineMessage::id))
    }

    @Test
    fun presenterParentsNewTopicsToObservedDirectSession() = runBlocking {
        val repository = ChatRepository(database)
        MessageStore(database).ingest(incoming(ACCOUNT, "session-message", "main", threadId = "session"))
        val sent = mutableListOf<DraftSnapshot>()
        val ids = ArrayDeque(listOf("opened-child", "quick-child"))
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, snapshot -> sent += snapshot; true },
            threadingPolicy = ThreadingPolicy { ThreadId.require(ids.removeFirst()) },
        )
        assertTrue(presenter.selectPeer(PEER))
        presenter.state.first { it.currentSession?.id?.value == "session" }

        assertTrue(presenter.startNewThread())
        val opened = presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "opened-child" }
        assertEquals("session", opened.selectedThread?.parentId?.value)
        presenter.closeThread()
        presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread == null }
        assertTrue(presenter.sendDraftAsNewThread(snapshot(ACCOUNT, PEER, "quick topic")).await())
        assertEquals("session", sent.single().outboundThread?.parentId?.value)
        presenter.close()
    }

    @Test
    fun presenterReservesDirectSessionBeforeOpeningFirstChildThread() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
            threadingPolicy = ThreadingPolicy { ThreadId.require("first-child") },
        )
        assertTrue(presenter.selectPeer(PEER))

        assertTrue(presenter.startNewThread())

        val opened = presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "first-child" }
        val session = requireNotNull(opened.currentSession)
        assertEquals(session.id, opened.selectedThread?.parentId)
        val outbox = store.composeDirectDraft(
            accountId = ACCOUNT,
            operationId = "first-child-operation",
            localMessageId = "first-child-message",
            originId = "first-child-origin",
            peerJid = PEER,
            senderJid = SELF,
            body = "first child body",
            thread = opened.selectedThread,
        )
        assertTrue(outbox != null)
        assertEquals(opened.selectedThread, store.messages(ACCOUNT).single().let {
            ThreadRef(
                ThreadId.require(requireNotNull(it.threadId)),
                ThreadId.require(requireNotNull(it.parentThreadId)),
            )
        })
        presenter.close()
    }

    @Test
    fun roomTopicsIgnoreDirectSessionAndExposeOnlyRoomThreads() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "direct-session", "direct", threadId = "shared-session"))
        store.ingest(
            incoming(ACCOUNT, "room-thread", "room topic", threadId = "room-session").copy(
                senderJid = "$PEER/alice",
                messageKind = MessageKind.GROUPCHAT,
            ),
        )
        repository.markRoom(ACCOUNT, PEER)
        val sent = mutableListOf<DraftSnapshot>()
        val ids = ArrayDeque(listOf("opened-room-topic", "quick-room-topic"))
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, snapshot -> sent += snapshot; true },
            threadingPolicy = ThreadingPolicy { ThreadId.require(ids.removeFirst()) },
        )

        assertTrue(presenter.selectPeer(PEER))
        val room = presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeerGroupChat && it.recentThreads.isNotEmpty() }
        assertEquals(null, room.currentSession)
        assertEquals(listOf(MessageKind.GROUPCHAT), room.recentThreads.map(RecentThread::messageKind))

        assertTrue(presenter.startNewThread())
        val opened = presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "opened-room-topic" }
        assertEquals(null, opened.selectedThread?.parentId)
        presenter.closeThread()
        presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread == null }
        assertTrue(
            presenter.sendDraftAsNewThread(
                snapshot(ACCOUNT, PEER, "quick room topic").copy(groupChat = true),
            ).await(),
        )
        assertEquals(null, sent.single().outboundThread?.parentId)
        presenter.close()
    }

    @Test
    fun namedCreationAndRenamePreserveIdentityDraftsAndScopeAfterReopen() = runBlocking {
        var repository = ChatRepository(database)
        val direct = requireNotNull(repository.createNamedThread(ACCOUNT, PEER, MessageKind.CHAT, "  Project  "))
        val duplicate = requireNotNull(repository.createNamedThread(ACCOUNT, PEER, MessageKind.CHAT, "Project"))
        val room = requireNotNull(repository.createNamedThread(ACCOUNT, OTHER_PEER, MessageKind.GROUPCHAT, "Project"))
        val otherAccount = requireNotNull(repository.createNamedThread(OTHER_ACCOUNT, PEER, MessageKind.CHAT, "Project"))
        assertTrue(direct != duplicate && direct != room && direct != otherAccount)
        assertEquals(null, direct.parentId)
        val key = DirectConversationKey(ACCOUNT, PEER, direct)
        repository.saveDraft(key, "  exact draft\n", attachmentUrl = "https://example.org/file", attachmentName = "notes.txt")
        assertTrue(repository.renameThread(ACCOUNT, PEER, MessageKind.CHAT, direct, "Renamed"))
        assertTrue(!repository.renameThread(OTHER_ACCOUNT, PEER, MessageKind.CHAT, direct, "Wrong account"))
        assertTrue(!repository.renameThread(ACCOUNT, OTHER_PEER, MessageKind.CHAT, direct, "Wrong peer"))
        assertTrue(!repository.renameThread(ACCOUNT, PEER, MessageKind.GROUPCHAT, direct, "Wrong kind"))
        for (invalid in listOf("", "  ", "line\nbreak", "name\u0000", "x".repeat(81))) {
            assertEquals(null, repository.createNamedThread(ACCOUNT, PEER, MessageKind.CHAT, invalid))
            assertTrue(!repository.renameThread(ACCOUNT, PEER, MessageKind.CHAT, direct, invalid))
        }
        database.close()
        database = NemaDatabase.create(context, databaseName)
        repository = ChatRepository(database)
        val directRows = repository.observeRecentThreads(ACCOUNT, PEER).first()
        assertEquals(setOf(direct, duplicate), directRows.map { it.thread }.toSet())
        assertTrue(directRows.all { it.locallyNamed && it.messageKind == MessageKind.CHAT })
        assertEquals("Renamed", directRows.single { it.thread == direct }.title)
        assertEquals("  exact draft\n", repository.observeStoredDraft(key).first().body)
        assertEquals("notes.txt", repository.observeStoredDraft(key).first().attachmentName)
        assertEquals(room, repository.observeRecentThreads(ACCOUNT, OTHER_PEER).first().single().thread)
        assertEquals(otherAccount, repository.observeRecentThreads(OTHER_ACCOUNT, PEER).first().single().thread)
    }

    @Test
    fun namedDirectSendAndInboundNeverReplaceMainSession() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        val session = store.ensureDirectThreadSession(ACCOUNT, PEER)
        suspend fun send(suffix: String, thread: ThreadRef? = null, body: String = "outbound body") {
            requireNotNull(store.composeDirectDraft(
                ACCOUNT, "operation-$suffix", "local-$suffix", "origin-$suffix", PEER, SELF, body, thread,
            ))
        }
        val named = requireNotNull(repository.createNamedThread(ACCOUNT, PEER, MessageKind.CHAT, "Project"))
        send("named", named, "  exact project body\n")
        store.ingest(incoming(ACCOUNT, "named-inbound", "reply body", threadId = named.id.value))
        assertEquals(session, repository.observeCurrentSession(ACCOUNT, PEER).first())
        send("main")
        assertEquals(session.id.value, database.messageDao().message(ACCOUNT, "local-main")?.threadId)
        assertEquals(named.id.value, database.messageDao().message(ACCOUNT, "local-named")?.threadId)
        assertEquals(listOf("outbound body"), repository.observeTimeline(ACCOUNT, PEER).first().map { it.body })
        assertEquals(listOf("  exact project body\n", "reply body"),
            repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, named)).first().map { it.body })
        assertTrue(repository.observeRecentThreads(ACCOUNT, PEER).first().filter { it.locallyNamed }.single().thread == named)
        // Naming a previously implicit session must also stop it from owning future Main sends.
        assertTrue(repository.renameThread(ACCOUNT, PEER, MessageKind.CHAT, session, "Former session"))
        assertEquals(null, repository.observeCurrentSession(ACCOUNT, PEER).first())
        send("new-main")
        val rotated = requireNotNull(database.messageDao().message(ACCOUNT, "local-new-main")?.threadId)
        assertTrue(rotated != session.id.value && rotated != named.id.value)
        assertTrue(!database.messageDao().isNamedThread(ACCOUNT, PEER, MessageKind.CHAT, rotated))
        assertEquals(rotated, repository.observeCurrentSession(ACCOUNT, PEER).first()?.id?.value)
    }

    @Test
    fun emptyNamedRootRejectsIncomingReparentingWithoutLosingBody() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        for (kind in listOf(MessageKind.CHAT, MessageKind.GROUPCHAT)) {
            val peer = if (kind == MessageKind.CHAT) PEER else OTHER_PEER
            val root = requireNotNull(repository.createNamedThread(ACCOUNT, peer, kind, "Project"))
            database.messageDao().insertThread(org.thanosapollo.nema.storage.MessageThreadEntity(
                ACCOUNT, peer, kind, "existing-parent", null,
            ))
            // The lowest writer must not treat a named empty root as an unresolved placeholder.
            assertEquals(0, database.messageDao().resolveThreadParent(ACCOUNT, peer, kind, root.id.value, "existing-parent"))
            val result = store.ingest(incoming(ACCOUNT, "$kind-conflict", "  valid body\n", root.id.value, "claimed-parent").copy(
                peerJid = peer, senderJid = if (kind == MessageKind.CHAT) peer else "$peer/alice", messageKind = kind,
            ))
            assertTrue(result.inserted)
            assertEquals(root, result.storedThread)
            assertEquals(null, database.messageDao().thread(ACCOUNT, peer, kind, root.id.value)?.parentThreadId)
            assertEquals(null, database.messageDao().thread(ACCOUNT, peer, kind, "claimed-parent"))
            assertEquals(null, database.messageDao().message(ACCOUNT, result.messageId)?.parentThreadId)
            assertEquals(listOf("  valid body\n"), repository.observeTimeline(DirectConversationKey(ACCOUNT, peer, root)).first().map { it.body })
            assertTrue(repository.observeTimeline(ACCOUNT, peer).first().isEmpty())
        }
    }

    @Test
    fun namedRoomPartitionAndReadSynchronizationLeaveOtherDestinationsUnread() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        for (kind in listOf(MessageKind.CHAT, MessageKind.GROUPCHAT)) {
            val peer = if (kind == MessageKind.CHAT) PEER else OTHER_PEER
            val named = requireNotNull(repository.createNamedThread(ACCOUNT, peer, kind, "Project"))
            val other = requireNotNull(repository.createNamedThread(ACCOUNT, peer, kind, "Other"))
            suspend fun ingest(id: String, thread: String?) = store.ingest(
                incoming(ACCOUNT, "$kind-$id", "body-$id", threadId = thread).copy(
                    peerJid = peer, senderJid = if (kind == MessageKind.CHAT) peer else "$peer/alice",
                    messageKind = kind,
                ),
            )
            ingest("named", named.id.value)
            ingest("other", other.id.value)
            ingest("implicit", "legacy-$kind")
            ingest("main", null)
            val dao = database.messageDao()
            dao.markMessagesReadThrough(ACCOUNT, peer, requireNotNull(dao.message(ACCOUNT, "$kind-main")).localSequence)
            assertTrue(requireNotNull(dao.message(ACCOUNT, "$kind-main")).locallyRead)
            assertTrue(requireNotNull(dao.message(ACCOUNT, "$kind-implicit")).locallyRead)
            assertTrue(!requireNotNull(dao.message(ACCOUNT, "$kind-named")).locallyRead)
            assertTrue(!requireNotNull(dao.message(ACCOUNT, "$kind-other")).locallyRead)
            ingest("named-later", named.id.value)
            dao.markMessagesReadThrough(ACCOUNT, peer, requireNotNull(dao.message(ACCOUNT, "$kind-named-later")).localSequence)
            assertTrue(requireNotNull(dao.message(ACCOUNT, "$kind-named")).locallyRead)
            assertTrue(!requireNotNull(dao.message(ACCOUNT, "$kind-other")).locallyRead)
            assertEquals(listOf("body-implicit", "body-main"), repository.observeTimeline(ACCOUNT, peer).first().map { it.body })
            assertEquals(listOf("body-named", "body-named-later"),
                repository.observeTimeline(DirectConversationKey(ACCOUNT, peer, named)).first().map { it.body })
            assertEquals(1, repository.observeRecentThreads(ACCOUNT, peer).first().single { it.thread == other }.unreadCount)
        }
    }

    @Test
    fun presenterCreatesBothKindsAndRejectsStaleDirectoryCommands() = runBlocking {
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), repository, scope, { _, _ -> true })
        try {
            for (room in listOf(false, true)) {
                val peer = if (room) OTHER_PEER else PEER
                if (room) repository.markRoom(ACCOUNT, peer)
                presenter.selectPeer(peer)
                val main = withTimeout(5_000) { presenter.state.first {
                    it.selectedPeer == peer && it.contentStatus == ChatContentStatus.Ready && it.selectedThread == null
                } }
                assertTrue(presenter.createNamedThread(main.routeOccurrence, "Project"))
                val selected = withTimeout(5_000) { presenter.state.first {
                    it.selectedPeer == peer && it.selectedThread != null && it.contentStatus == ChatContentStatus.Ready
                } }
                val named = selected.recentThreads.single { it.locallyNamed }
                assertEquals(if (room) MessageKind.GROUPCHAT else MessageKind.CHAT, named.messageKind)
                assertEquals(null, named.thread.parentId)
                assertTrue(selected.messages.isEmpty())
                presenter.selectThreadDestination(selected.routeOccurrence, null)
                val back = withTimeout(5_000) { presenter.state.first {
                    it.selectedPeer == peer && it.selectedThread == null && it.contentStatus == ChatContentStatus.Ready
                } }
                assertTrue(!presenter.createNamedThread(main.routeOccurrence, "Stale"))
                assertTrue(!presenter.renameNamedThread(selected.routeOccurrence, named, "Stale", null))
                presenter.selectThreadDestination(selected.routeOccurrence, named.thread)
                assertEquals(back.routeOccurrence, presenter.state.value.routeOccurrence)
                presenter.selectThreadDestination(back.routeOccurrence, named.thread)
                withTimeout(5_000) { presenter.state.first { it.selectedThread == named.thread } }
            }
        } finally { presenter.close() }
    }

    @Test
    fun emptyNamedThreadsAreDurableAndNotLimitedByRecentMessages() = runBlocking {
        val dao = database.messageDao()
        dao.insertPeer(PeerEntity(ACCOUNT, PEER))
        repeat(12) { index ->
            dao.insertThread(org.thanosapollo.nema.storage.MessageThreadEntity(
                ACCOUNT, PEER, MessageKind.CHAT, "empty-$index", null,
            ))
            dao.saveThreadTitle(org.thanosapollo.nema.storage.MessageThreadTitleEntity(
                ACCOUNT, PEER, MessageKind.CHAT, "empty-$index", "Project $index",
            ))
        }
        database.close()
        database = NemaDatabase.create(context, databaseName)
        val directory = ChatRepository(database).observeRecentThreads(ACCOUNT, PEER).first()
        assertEquals((0 until 12).map { "empty-$it" }.toSet(), directory.map { it.thread.id.value }.toSet())
    }

    @Test
    fun namedTopLevelMessagesStayOutOfMainWithoutHidingUnknownBodies() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "main", "ordinary body", threadId = "implicit-session"))
        store.ingest(incoming(ACCOUNT, "project", "project body", threadId = "project"))
        val repository = ChatRepository(database)
        assertTrue(repository.renameThread(ACCOUNT, PEER, MessageKind.CHAT,
            ThreadRef(ThreadId.require("project")), "Project"))
        assertEquals(listOf("ordinary body"), repository.observeTimeline(ACCOUNT, PEER).first().map { it.body })
    }

    @Test
    fun recentThreadsKeepLatestTenAndPersistCustomLocalTitle() = runBlocking {
        val store = MessageStore(database)
        repeat(12) { index ->
            store.ingest(
                incoming(
                    ACCOUNT,
                    "thread-$index-root",
                    "Thread $index has a deliberately long title",
                    threadId = "thread-$index",
                    parentThreadId = "session-$index",
                ),
            )
        }
        var repository = ChatRepository(database)

        var recent = repository.observeRecentThreads(ACCOUNT, PEER).first()
        assertEquals(10, recent.size)
        assertEquals("thread-11", recent.first().thread.id.value)
        assertEquals("Thread 11 has a deli", recent.first().title)
        assertTrue(
            repository.renameThread(
                ACCOUNT,
                PEER,
                recent.first().messageKind,
                recent.first().thread,
                "Hermes planning",
            ),
        )

        database.close()
        database = NemaDatabase.create(context, databaseName)
        repository = ChatRepository(database)
        recent = repository.observeRecentThreads(ACCOUNT, PEER).first()
        assertEquals("Hermes planning", recent.first().title)
        assertEquals("thread-2", recent.last().thread.id.value)
    }

    @Test
    fun recentThreadsIncludeTopLevelSessionsAndIsolateKindTitles() = runBlocking {
        val store = MessageStore(database)
        val emojiTitle = "😀".repeat(21) + " tail"
        store.ingest(incoming(ACCOUNT, "chat-root", emojiTitle, threadId = "shared"))
        store.ingest(incoming(ACCOUNT, "chat-reply", "chat reply", threadId = "shared"))
        store.ingest(
            incoming(ACCOUNT, "room-root", "Room planning", threadId = "shared").copy(
                senderJid = "$PEER/alice",
                messageKind = MessageKind.GROUPCHAT,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "room-reply", "room reply", threadId = "shared").copy(
                senderJid = "$PEER/bob",
                messageKind = MessageKind.GROUPCHAT,
            ),
        )
        val repository = ChatRepository(database)

        var recent = repository.observeRecentThreads(ACCOUNT, PEER).first()
        val direct = recent.single { it.messageKind == MessageKind.CHAT }
        val room = recent.single { it.messageKind == MessageKind.GROUPCHAT }
        assertEquals("😀".repeat(20), direct.title)
        assertEquals("Room planning", room.title)
        assertEquals(1, direct.replyCount)
        assertEquals(1, room.replyCount)
        assertTrue(repository.renameThread(ACCOUNT, PEER, MessageKind.CHAT, direct.thread, "Direct session"))

        recent = repository.observeRecentThreads(ACCOUNT, PEER).first()
        assertEquals("Direct session", recent.single { it.messageKind == MessageKind.CHAT }.title)
        assertEquals("Room planning", recent.single { it.messageKind == MessageKind.GROUPCHAT }.title)
    }

    @Test
    fun recentThreadsApplyTenItemLimitIndependentlyPerMessageKind() = runBlocking {
        val store = MessageStore(database)
        repeat(12) { index ->
            store.ingest(incoming(ACCOUNT, "chat-$index", "chat $index", threadId = "chat-thread-$index"))
            store.ingest(
                incoming(ACCOUNT, "room-$index", "room $index", threadId = "room-thread-$index").copy(
                    senderJid = "$PEER/alice",
                    messageKind = MessageKind.GROUPCHAT,
                ),
            )
        }

        val recent = ChatRepository(database).observeRecentThreads(ACCOUNT, PEER).first()

        assertEquals(10, recent.count { it.messageKind == MessageKind.CHAT })
        assertEquals(10, recent.count { it.messageKind == MessageKind.GROUPCHAT })
        assertEquals("chat-thread-11", recent.first { it.messageKind == MessageKind.CHAT }.thread.id.value)
        assertEquals("room-thread-11", recent.first { it.messageKind == MessageKind.GROUPCHAT }.thread.id.value)
    }

    @Test
    fun directSessionStaysInOverviewWhileChildFromLegacyRootGetsSummary() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "legacy-root", "legacy root").copy(
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "legacy-wire")),
            ),
        )
        val repository = ChatRepository(database)
        val session = requireNotNull(repository.observeCurrentSession(ACCOUNT, PEER).first())
        store.ingest(
            incoming(ACCOUNT, "session-member", "ordinary session", threadId = session.id.value).copy(
                replyToId = "legacy-wire",
                replyToJid = PEER,
            ),
        )
        val child = ThreadRef(ThreadId.require("legacy-child"), session.id)
        store.ingest(
            incoming(
                ACCOUNT,
                "child-member",
                "child answer",
                threadId = child.id.value,
                parentThreadId = session.id.value,
            ).copy(
                replyToId = "legacy-wire",
                replyToJid = PEER,
            ),
        )

        val overview = repository.observeTimeline(ACCOUNT, PEER).first()
        val focused = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, child)).first()

        assertEquals(listOf("legacy-root", "session-member"), overview.map(TimelineMessage::id))
        assertEquals(child, overview.first().threadSummaries.single().thread)
        assertEquals(listOf("legacy-root", "child-member"), focused.map(TimelineMessage::id))
        assertEquals(1, overview.first().threadSummaries.single().replyCount)
    }

    @Test
    fun outgoingRootKeepsThreadSummaryWhenPeerRepliesInChild() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "own-root", "own send").copy(
                senderJid = SELF,
                direction = MessageDirection.OUTBOUND,
                aliases = listOf(
                    TrustedIdentityAlias(
                        IdentityAliasKind.ORIGIN_ID,
                        MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                        "own-origin",
                    ),
                ),
            ),
        )
        val session = requireNotNull(ChatRepository(database).observeCurrentSession(ACCOUNT, PEER).first())
        val child = ThreadRef(ThreadId.require("own-child"), session.id)
        store.ingest(
            incoming(
                ACCOUNT,
                "peer-reply",
                "peer answer",
                threadId = child.id.value,
                parentThreadId = session.id.value,
            ).copy(
                replyToId = "own-origin",
                replyToJid = SELF,
            ),
        )

        val overview = ChatRepository(database).observeTimeline(ACCOUNT, PEER).first()
        val root = overview.single { it.id == "own-root" }
        assertTrue(root.outgoing)
        assertEquals(child, root.threadSummaries.single().thread)
        assertEquals(1, root.threadSummaries.single().replyCount)
        assertEquals(listOf("own-root"), overview.map(TimelineMessage::id))
    }

    @Test
    fun outgoingChildCollapsesToRootThreadSummary() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "peer-root", "peer root").copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "peer-origin"),
                ),
            ),
        )
        val session = requireNotNull(ChatRepository(database).observeCurrentSession(ACCOUNT, PEER).first())
        val child = ThreadRef(ThreadId.require("own-reply-thread"), session.id)
        store.ingest(
            incoming(ACCOUNT, "own-reply", "own thread send").copy(
                senderJid = SELF,
                direction = MessageDirection.OUTBOUND,
                threadId = child.id.value,
                parentThreadId = session.id.value,
                replyToId = "peer-origin",
                replyToJid = PEER,
                aliases = listOf(
                    TrustedIdentityAlias(
                        IdentityAliasKind.ORIGIN_ID,
                        MessageStore.OUTBOUND_ORIGIN_AUTHORITY,
                        "own-reply-origin",
                    ),
                ),
            ),
        )

        val overview = ChatRepository(database).observeTimeline(ACCOUNT, PEER).first()
        assertEquals(listOf("peer-root"), overview.map(TimelineMessage::id))
        assertEquals(child, overview.single().threadSummaries.single().thread)
        assertTrue(overview.single().threadSummaries.single().replyCount >= 1)
        val dedicated = ChatRepository(database).observeTimeline(DirectConversationKey(ACCOUNT, PEER, child)).first()
        assertEquals(listOf("peer-root", "own-reply"), dedicated.map(TimelineMessage::id))
    }

    @Test
    fun laterMemberReplyResolvesHoldSendWithoutOwnReplyMetadata() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "session-root", "session root", threadId = "session").copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "session-origin"),
                ),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "own-hold-send", "hold send").copy(
                senderJid = SELF,
                direction = MessageDirection.OUTBOUND,
                threadId = "hold-child",
                parentThreadId = "session",
            ),
        )
        store.ingest(
            incoming(
                ACCOUNT,
                "peer-in-thread",
                "peer thread reply",
                threadId = "hold-child",
                parentThreadId = "session",
            ).copy(
                replyToId = "session-origin",
                replyToJid = PEER,
            ),
        )
        val child = ThreadRef(ThreadId.require("hold-child"), ThreadId.require("session"))
        val overview = ChatRepository(database).observeTimeline(ACCOUNT, PEER).first()
        assertEquals(listOf("session-root"), overview.map(TimelineMessage::id))
        assertEquals(child, overview.single().threadSummaries.single().thread)
        assertEquals(2, overview.single().threadSummaries.single().replyCount)
    }

    @Test
    fun recentChildUsesExternalRootTitleAndCountsEveryReply() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "recent-root", "External root title").copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "recent-root-wire"),
                ),
            ),
        )
        val repository = ChatRepository(database)
        val session = requireNotNull(repository.observeCurrentSession(ACCOUNT, PEER).first())
        val child = ThreadRef(ThreadId.require("recent-child"), session.id)
        store.ingest(
            incoming(
                ACCOUNT,
                "recent-child-first",
                "first answer",
                threadId = child.id.value,
                parentThreadId = session.id.value,
            ).copy(
                replyToId = "recent-root-wire",
                replyToJid = PEER,
            ),
        )
        store.ingest(
            incoming(
                ACCOUNT,
                "recent-child-second",
                "second answer",
                threadId = child.id.value,
                parentThreadId = session.id.value,
            ),
        )
        store.ingest(incoming(ACCOUNT, "new-session-root", "new session root"))
        val rotatedSession = requireNotNull(repository.observeCurrentSession(ACCOUNT, PEER).first())
        assertTrue(rotatedSession != session)

        val recent = repository.observeRecentThreads(ACCOUNT, PEER).first().single { it.thread == child }
        val summary = repository.observeTimeline(ACCOUNT, PEER).first()
            .single { it.id == "recent-root" }
            .threadSummaries
            .single { it.thread == child }

        assertEquals("External root title", recent.title)
        assertEquals(2, recent.replyCount)
        assertEquals(2, summary.replyCount)
    }

    @Test
    fun renameUsesStableThreadIdentityWhenReplyCountChanges() = runBlocking {
        val store = MessageStore(database)
        store.ingest(incoming(ACCOUNT, "rename-root", "rename root", threadId = "rename-thread"))
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
        )
        assertTrue(presenter.selectPeer(PEER))
        val captured = presenter.state.first { it.recentThreads.isNotEmpty() }.recentThreads.single()
        store.ingest(incoming(ACCOUNT, "rename-reply", "new reply", threadId = "rename-thread"))
        presenter.state.first { it.recentThreads.single().replyCount == 1 }

        assertTrue(presenter.renameThread(captured, "Stable title"))
        presenter.state.first { it.recentThreads.single().title == "Stable title" }
        presenter.close()
    }

    @Test
    fun rootAwareProjectionCollapsesOverviewAndKeepsExactThreadMessages() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "root", "root body").copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "root-origin-id"),
                    TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "root-message-id"),
                ),
            ),
        )
        val session = requireNotNull(ChatRepository(database).observeCurrentSession(ACCOUNT, PEER).first())
        store.ingest(
            incoming(
                ACCOUNT,
                "thread-first",
                "first answer",
                threadId = "thread-a",
                parentThreadId = session.id.value,
            ).copy(
                replyToId = "root-message-id",
                replyToJid = PEER,
                replyFallbackBody = "> peer wrote:\n> root body\n",
            ),
        )
        store.ingest(
            incoming(
                ACCOUNT,
                "thread-later",
                "latest answer",
                threadId = "thread-a",
                parentThreadId = session.id.value,
            ),
        )
        store.ingest(incoming(OTHER_ACCOUNT, "other-account", "hidden"))
        val repository = ChatRepository(database)
        val thread = ThreadRef(ThreadId.require("thread-a"), session.id)

        val conversations = repository.observeConversations(ACCOUNT).first()
        val overview = repository.observeTimeline(ACCOUNT, PEER).first()
        val dedicated = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, thread)).first()

        assertEquals(listOf("latest answer"), conversations.map(ConversationSummary::preview))
        assertEquals(listOf("root"), overview.map(TimelineMessage::id))
        assertEquals(
            listOf(
                ThreadSummary(
                    thread,
                    replyCount = 2,
                    latestMessageId = "thread-later",
                    latestPreview = "latest answer",
                ),
            ),
            overview.single().threadSummaries,
        )
        assertEquals(listOf("root", "thread-first", "thread-later"), dedicated.map(TimelineMessage::id))

        database.close()
        database = NemaDatabase.create(context, databaseName)
        val reopened = ChatRepository(database)
        assertEquals(listOf("root"), reopened.observeTimeline(ACCOUNT, PEER).first().map(TimelineMessage::id))
        assertEquals(
            listOf("root", "thread-first", "thread-later"),
            reopened.observeTimeline(DirectConversationKey(ACCOUNT, PEER, thread)).first().map(TimelineMessage::id),
        )
    }

    @Test
    fun dedicatedProjectionKeepsOnlyChronologicalThreadMembersAfterBackfill() = runBlocking {
        val store = MessageStore(database)
        val session = store.ensureDirectThreadSession(ACCOUNT, PEER)
        store.ingest(
            incoming(
                ACCOUNT,
                "thread-first",
                "first answer",
                threadId = "backfilled-thread",
                parentThreadId = session.id.value,
            ).copy(
                replyToId = "backfilled-root-wire",
                replyToJid = PEER,
            ),
        )
        store.ingest(
            incoming(
                ACCOUNT,
                "thread-later",
                "later answer",
                threadId = "backfilled-thread",
                parentThreadId = session.id.value,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "backfilled-root", "root body").copy(
                sentAtEpochMs = 1,
                sentTimeSource = MessageTimeSource.MAM,
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.MESSAGE_ID, PEER, "backfilled-root-wire"),
                ),
            ),
        )
        val repository = ChatRepository(database)
        val thread = ThreadRef(ThreadId.require("backfilled-thread"), session.id)

        val overview = repository.observeTimeline(ACCOUNT, PEER).first()
        val dedicated = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, thread)).first()

        assertEquals(listOf("backfilled-root"), overview.map(TimelineMessage::id))
        assertEquals(
            listOf("backfilled-root", "thread-first", "thread-later"),
            dedicated.map(TimelineMessage::id),
        )
    }

    @Test
    fun laterReplyMetadataDoesNotRetargetThreadRoot() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "first-root", "first root").copy(
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "first-wire")),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "later-target", "later target").copy(
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "later-wire")),
            ),
        )
        val session = requireNotNull(ChatRepository(database).observeCurrentSession(ACCOUNT, PEER).first())
        store.ingest(
            incoming(
                ACCOUNT,
                "thread-first",
                "first answer",
                threadId = "stable-thread",
                parentThreadId = session.id.value,
            ).copy(
                replyToId = "first-wire",
                replyToJid = PEER,
            ),
        )
        store.ingest(
            incoming(
                ACCOUNT,
                "thread-later",
                "later answer",
                threadId = "stable-thread",
                parentThreadId = session.id.value,
            ).copy(
                replyToId = "later-wire",
                replyToJid = PEER,
            ),
        )
        val repository = ChatRepository(database)
        val thread = ThreadRef(ThreadId.require("stable-thread"), session.id)

        val overview = repository.observeTimeline(ACCOUNT, PEER).first()
        val dedicated = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, thread)).first()

        assertEquals(listOf("first-root", "later-target"), overview.map(TimelineMessage::id))
        assertEquals(thread, overview.first().threadSummaries.single().thread)
        assertTrue(overview.last().threadSummaries.isEmpty())
        assertEquals(listOf("first-root", "thread-first", "thread-later"), dedicated.map(TimelineMessage::id))
    }

    @Test
    fun roomConversationKeepsLegacyThreadProjectionForChatRows() = runBlocking {
        val room = "room@conference.example.org"
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "room-root", "room root").copy(
                peerJid = room,
                senderJid = room,
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, room, "room-wire")),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "room-thread", "room reply", threadId = "room-thread-id").copy(
                peerJid = room,
                senderJid = room,
                replyToId = "room-wire",
                replyToJid = room,
            ),
        )
        ChatRepository(database).markRoom(ACCOUNT, room)
        val repository = ChatRepository(database)
        val thread = ThreadRef(ThreadId.require("room-thread-id"))

        val overview = repository.observeTimeline(ACCOUNT, room).first()
        val dedicated = repository.observeTimeline(DirectConversationKey(ACCOUNT, room, thread)).first()

        assertEquals(listOf("room-root", "room-thread"), overview.map(TimelineMessage::id))
        assertTrue(overview.all { it.threadSummaries.isEmpty() })
        assertEquals(listOf("room-thread"), dedicated.map(TimelineMessage::id))
    }

    @Test
    fun childThreadSummaryAttachesInsideParentAndPreservesFullLineage() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "root", "root body").copy(
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "root-wire")),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "parent-reply", "parent answer", threadId = "parent-thread").copy(
                replyToId = "root-wire",
                replyToJid = PEER,
                aliases = listOf(TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "parent-wire")),
            ),
        )
        store.ingest(
            incoming(
                ACCOUNT,
                "child-reply",
                "child answer",
                threadId = "child-thread",
                parentThreadId = "parent-thread",
            ).copy(
                replyToId = "parent-wire",
                replyToJid = PEER,
            ),
        )
        val repository = ChatRepository(database)
        val parent = ThreadRef(ThreadId.require("parent-thread"))
        val child = ThreadRef(ThreadId.require("child-thread"), ThreadId.require("parent-thread"))

        val overview = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER)).first()
        val parentView = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, parent)).first()
        val childView = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, child)).first()

        assertEquals(listOf("root", "parent-reply"), overview.map(TimelineMessage::id))
        assertTrue(overview.first().threadSummaries.isEmpty())
        assertEquals(child, overview.last().threadSummaries.single().thread)
        assertEquals(listOf("parent-reply"), parentView.map(TimelineMessage::id))
        assertEquals(child, parentView.single().threadSummaries.single().thread)
        assertEquals(listOf("parent-reply", "child-reply"), childView.map(TimelineMessage::id))
        assertTrue(childView.all { it.threadSummaries.isEmpty() })
    }

    @Test
    fun rootAwareProjectionFailsClosedOnAmbiguousRootsAndExactLineage() = runBlocking {
        val store = MessageStore(database)
        fun root(localId: String, kind: IdentityAliasKind) = incoming(ACCOUNT, localId, "$localId body").copy(
            aliases = listOf(TrustedIdentityAlias(kind, PEER, "ambiguous-wire")),
        )
        store.ingest(root("root-a", IdentityAliasKind.ORIGIN_ID))
        store.ingest(root("root-b", IdentityAliasKind.MESSAGE_ID))
        store.ingest(
            incoming(ACCOUNT, "thread-a", "first", threadId = "shared-thread").copy(
                replyToId = "ambiguous-wire",
                replyToJid = PEER,
            ),
        )
        store.ingest(incoming(ACCOUNT, "thread-b", "later", threadId = "shared-thread"))
        val repository = ChatRepository(database)
        val exact = ThreadRef(ThreadId.require("shared-thread"))

        val overview = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER)).first()
        val dedicated = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER, exact)).first()
        val wrongLineage = repository.observeTimeline(
            DirectConversationKey(
                ACCOUNT,
                PEER,
                ThreadRef(ThreadId.require("shared-thread"), ThreadId.require("different-parent")),
            ),
        ).first()

        assertEquals(listOf("root-a", "root-b", "thread-a", "thread-b"), overview.map { it.id })
        assertTrue(overview.all { it.threadSummaries.isEmpty() })
        assertEquals(listOf("thread-a", "thread-b"), dedicated.map { it.id })
        assertTrue(wrongLineage.isEmpty())
    }

    @Test
    fun rootAwareProjectionRejectsWrongAuthorQuarantineStanzaAndOtherAccountAliases() = runBlocking {
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "stanza-root", "stanza root").copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, PEER, "stanza-wire"),
                ),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "stanza-thread", "stanza answer", threadId = "stanza-thread-id").copy(
                replyToId = "stanza-wire",
                replyToJid = PEER,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "author-root", "author root").copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "author-wire"),
                ),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "author-thread", "author answer", threadId = "author-thread-id").copy(
                replyToId = "author-wire",
                replyToJid = OTHER_PEER,
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "quarantine-root", "quarantine root").copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "quarantine-wire"),
                ),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "quarantine-thread", "quarantine answer", threadId = "quarantine-thread-id").copy(
                replyToId = "quarantine-wire",
                replyToJid = PEER,
            ),
        )
        database.messageDao().quarantineAlias(
            ACCOUNT,
            IdentityAliasKind.ORIGIN_ID,
            PEER,
            "quarantine-wire",
        )
        store.ingest(
            incoming(OTHER_ACCOUNT, "other-root", "other root").copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "other-wire"),
                ),
            ),
        )
        store.ingest(
            incoming(ACCOUNT, "cross-thread", "cross answer", threadId = "cross-thread-id").copy(
                replyToId = "other-wire",
                replyToJid = PEER,
            ),
        )

        val overview = ChatRepository(database).observeTimeline(ACCOUNT, PEER).first()

        assertEquals(
            listOf(
                "stanza-root",
                "stanza-thread",
                "author-root",
                "author-thread",
                "quarantine-root",
                "quarantine-thread",
                "cross-thread",
            ),
            overview.map(TimelineMessage::id),
        )
        assertTrue(overview.all { it.threadSummaries.isEmpty() })
    }

    @Test
    fun threadCommandsPersistAccountScopedRouteDraftAndLineageAcrossPresenterRecreation() = runBlocking {
        val repository = ChatRepository(database)
        val threadIds = ArrayDeque(listOf("thread-a", "thread-b"))
        fun presenter(accountId: String, bareJid: String) = DirectChatPresenter(
            account = accountConfiguration(accountId, bareJid),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
            threadingPolicy = ThreadingPolicy { ThreadId.require(threadIds.removeFirst()) },
        )
        val first = presenter(ACCOUNT, SELF)
        assertTrue(first.selectPeer(PEER))
        assertTrue(first.startNewThread())
        val root = first.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "thread-a" }.selectedThread!!
        first.updateDraft(
            DraftSnapshot(DirectConversationKey(ACCOUNT, PEER, root), "root draft", 1),
        ).await()
        assertTrue(first.startChildThread())
        val child = first.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "thread-b" }.selectedThread!!
        first.updateDraft(
            DraftSnapshot(DirectConversationKey(ACCOUNT, PEER, child), "child draft", 1),
        ).await()
        first.close()

        val restored = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
            threadingPolicy = ThreadingPolicy { ThreadId.require(threadIds.removeFirst()) },
            restoreRouteOnStart = true,
        )
        val restoredState = restored.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "thread-b" }
        assertEquals("thread-a", restoredState.selectedThread?.parentId?.value)
        assertEquals("child draft", restoredState.draft)
        assertEquals("root draft", repository.observeDraft(DirectConversationKey(ACCOUNT, PEER, root)).first())
        assertEquals("", repository.observeDraft(DirectConversationKey(OTHER_ACCOUNT, PEER, child)).first())

        val other = presenter(OTHER_ACCOUNT, OTHER_SELF)
        assertEquals(null, other.state.first().selectedPeer)
        restored.close()
        other.close()
    }

    @Test
    fun replyAsThreadAtomicallyPersistsRootReferenceAndRouteAcrossRecreation() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "thread-target", "target body").copy(
                senderJid = "$PEER/device",
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "target-wire-id"),
                ),
            ),
        )
        repository.saveDraft(ACCOUNT, PEER, "main draft")
        val first = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
            threadingPolicy = ThreadingPolicy { ThreadId.require("thread-reply") },
        )
        assertTrue(first.selectPeer(PEER))
        val target = first.state.first { it.messages.singleOrNull()?.replyReferenceId != null }.messages.single()

        assertTrue(first.startThreadFrom(target))

        val opened = first.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "thread-reply" }
        val expectedReply = DraftReply("target-wire-id", "$PEER/device", "target body", "device")
        assertEquals(expectedReply, opened.draftReply)
        assertEquals("", opened.draft)
        assertEquals("main draft", repository.observeDraft(ACCOUNT, PEER).first())
        first.close()

        var sent: DraftSnapshot? = null
        val restored = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, snapshot -> sent = snapshot; true },
            restoreRouteOnStart = true,
        )
        val restoredState = restored.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "thread-reply" }
        assertEquals(expectedReply, restoredState.draftReply)
        assertEquals("", restoredState.draft)
        val replyKey = DirectConversationKey(ACCOUNT, PEER, requireNotNull(restoredState.selectedThread))
        assertTrue(
            restored.updateDraft(
                DraftSnapshot(replyKey, "thread answer", 1, reply = restoredState.draftReply),
            ).await(),
        )
        assertTrue(
            restored.sendDraft(
                DraftSnapshot(replyKey, "thread answer", 2, reply = restoredState.draftReply),
            ).await(),
        )
        assertEquals("thread-reply", sent?.key?.thread?.id?.value)
        assertEquals(expectedReply, sent?.reply)
        restored.close()
    }

    @Test
    fun replyAsThreadUsesMessageLineageAndRejectsUntrustedOrStaleTargets() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        store.ingest(
            incoming(
                ACCOUNT,
                "threaded-target",
                "threaded body",
                threadId = "parent-thread",
                parentThreadId = "root-thread",
            ).copy(
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "threaded-wire-id"),
                ),
            ),
        )
        store.ingest(incoming(ACCOUNT, "untrusted-target", "ordinary body"))
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
            threadingPolicy = ThreadingPolicy { error("thread ID must not be allocated") },
        )
        assertTrue(presenter.selectPeer(PEER))
        val selected = presenter.state.first { state ->
            state.messages.any { it.id == "threaded-target" && it.replyReferenceId != null }
        }
        val threaded = selected.messages.single { it.id == "threaded-target" }
        val untrusted = selected.messages.single { it.id == "untrusted-target" }

        assertTrue(!presenter.startThreadFrom(untrusted))
        assertEquals(null, presenter.state.value.selectedThread)
        assertTrue(presenter.selectPeer(OTHER_PEER))
        presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == OTHER_PEER }
        assertTrue(!presenter.startThreadFrom(threaded))
        assertEquals(OTHER_PEER, presenter.state.value.selectedPeer)
        assertTrue(presenter.selectPeer(PEER))
        val currentThreaded = presenter.state.first { state ->
            state.selectedPeer == PEER &&
                state.messages.any { it.id == "threaded-target" && it.replyReferenceId != null }
        }.messages.single { it.id == "threaded-target" }

        assertTrue(presenter.startThreadFrom(currentThreaded))

        val expectedThread = ThreadRef(
            ThreadId.require("parent-thread"),
            ThreadId.require("root-thread"),
        )
        val opened = presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread == expectedThread }
        assertEquals(expectedThread, opened.selectedThread)
        assertEquals(ChatRoute(PEER, expectedThread), repository.observeRoute(ACCOUNT).first())
        val draftKey = DirectConversationKey(ACCOUNT, PEER, expectedThread)
        assertEquals("threaded-wire-id", repository.observeStoredDraft(draftKey).first().reply?.id)
        assertEquals("threaded-wire-id", opened.draftReply?.id)
        presenter.close()
    }

    @Test
    fun replyAsThreadTransactionRejectsStaleRouteAndPreservesEveryDraft() = runBlocking {
        val repository = ChatRepository(database)
        MessageStore(database).ingest(
            incoming(ACCOUNT, "race-target", "target body").copy(
                senderJid = "$PEER/device",
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "race-wire-id"),
                ),
            ),
        )
        val source = ChatRoute(PEER)
        val destinationThread = ThreadRef(ThreadId.require("race-thread"))
        val destination = source.copy(thread = destinationThread)
        val target = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER)).first().single()
        val reply = DraftReply("race-wire-id", "$PEER/device", "target body", "device")
        repository.saveRoute(ACCOUNT, source)
        repository.saveDraft(DirectConversationKey(ACCOUNT, PEER), "source draft")
        repository.saveDraft(DirectConversationKey(ACCOUNT, PEER, destinationThread), "destination draft")
        repository.saveDraft(DirectConversationKey(OTHER_ACCOUNT, PEER), "unrelated draft")

        repository.saveRoute(ACCOUNT, ChatRoute(OTHER_PEER))
        assertTrue(!repository.openThreadReply(ACCOUNT, source, destination, target, reply))

        assertEquals(ChatRoute(OTHER_PEER), repository.observeRoute(ACCOUNT).first())
        assertEquals("source draft", repository.observeDraft(ACCOUNT, PEER).first())
        assertEquals(
            "destination draft",
            repository.observeDraft(DirectConversationKey(ACCOUNT, PEER, destinationThread)).first(),
        )
        assertEquals("unrelated draft", repository.observeDraft(OTHER_ACCOUNT, PEER).first())
    }

    @Test
    fun replyAsThreadTransactionRevalidatesTrustedTargetAndRejectsMucOrReplacement() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        store.ingest(
            incoming(ACCOUNT, "trusted-target", "target body").copy(
                senderJid = "$PEER/device",
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, PEER, "trusted-wire-id"),
                ),
            ),
        )
        val source = ChatRoute(PEER)
        val destination = source.copy(thread = ThreadRef(ThreadId.require("trusted-thread")))
        val target = repository.observeTimeline(DirectConversationKey(ACCOUNT, PEER)).first().single()
        val reply = DraftReply("trusted-wire-id", "$PEER/device", "target body", "device")
        repository.saveRoute(ACCOUNT, source)

        assertTrue(
            !repository.openThreadReply(
                ACCOUNT,
                source,
                destination,
                target.copy(body = "same ID, replaced content"),
                reply,
            ),
        )
        assertEquals(source, repository.observeRoute(ACCOUNT).first())
        assertEquals("", repository.observeDraft(DirectConversationKey(ACCOUNT, PEER, destination.thread)).first())

        database.messageDao().quarantineAlias(
            ACCOUNT,
            IdentityAliasKind.ORIGIN_ID,
            PEER,
            "trusted-wire-id",
        )
        assertTrue(!repository.openThreadReply(ACCOUNT, source, destination, target, reply))
        assertEquals(source, repository.observeRoute(ACCOUNT).first())

        val room = "room@conference.example.org"
        store.ingest(
            IncomingMessage(
                accountId = ACCOUNT,
                localMessageId = "room-target",
                peerJid = room,
                senderJid = "$room/alice",
                direction = MessageDirection.INBOUND,
                messageKind = MessageKind.GROUPCHAT,
                threadId = null,
                parentThreadId = null,
                body = "room body",
                archiveOrdinal = null,
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.STANZA_ID, room, "room-wire-id"),
                ),
            ),
        )
        val roomSource = ChatRoute(room)
        val roomDestination = roomSource.copy(thread = ThreadRef(ThreadId.require("room-thread")))
        val roomTarget = repository.observeTimeline(DirectConversationKey(ACCOUNT, room)).first().single()
        repository.saveRoute(ACCOUNT, roomSource)
        assertTrue(
            !repository.openThreadReply(
                ACCOUNT,
                roomSource,
                roomDestination,
                roomTarget,
                DraftReply("room-wire-id", "$room/alice", "room body", "alice"),
            ),
        )
        assertEquals(roomSource, repository.observeRoute(ACCOUNT).first())
    }

    @Test
    fun replyAsThreadRejectsChatTargetInsidePersistedRoomWithoutMutation() = runBlocking {
        val room = "private-room@conference.example.org"
        val repository = ChatRepository(database)
        MessageStore(database).ingest(
            incoming(ACCOUNT, "private-room-target", "private room body").copy(
                peerJid = room,
                senderJid = "$room/alice",
                aliases = listOf(
                    TrustedIdentityAlias(IdentityAliasKind.ORIGIN_ID, room, "private-room-wire-id"),
                ),
            ),
        )
        repository.markRoom(ACCOUNT, room)
        val source = ChatRoute(room)
        val thread = ThreadRef(ThreadId.require("private-room-thread"))
        val destination = source.copy(thread = thread)
        val target = repository.observeTimeline(DirectConversationKey(ACCOUNT, room)).first().single()
        val reply = DraftReply(
            "private-room-wire-id",
            "$room/alice",
            "private room body",
            "alice",
        )
        repository.saveRoute(ACCOUNT, source)
        repository.saveDraft(DirectConversationKey(ACCOUNT, room), "source room draft")
        repository.saveDraft(DirectConversationKey(ACCOUNT, room, thread), "destination room draft")

        assertTrue(!repository.openThreadReply(ACCOUNT, source, destination, target, reply))
        assertEquals(source, repository.observeRoute(ACCOUNT).first())
        assertEquals("source room draft", repository.observeDraft(ACCOUNT, room).first())
        assertEquals(
            "destination room draft",
            repository.observeDraft(DirectConversationKey(ACCOUNT, room, thread)).first(),
        )

        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
        )
        assertTrue(presenter.selectPeer(room))
        val selected = presenter.state.first {
            it.selectedPeerGroupChat && it.messages.singleOrNull()?.replyReferenceId != null
        }
        withTimeout(5_000) { repository.observeRoute(ACCOUNT).first { it == source } }
        assertTrue(!presenter.startThreadFrom(selected.messages.single()))
        assertEquals(source, repository.observeRoute(ACCOUNT).first())
        assertEquals("source room draft", repository.observeDraft(ACCOUNT, room).first())
        assertEquals(
            "destination room draft",
            repository.observeDraft(DirectConversationKey(ACCOUNT, room, thread)).first(),
        )
        presenter.close()
    }

    @Test
    fun completeAttachmentDraftReopensAndEveryFieldIsObservable() = runBlocking {
        val thread = ThreadRef(ThreadId.require("child"), ThreadId.require("parent"))
        val key = DirectConversationKey(ACCOUNT, PEER, thread)
        val reply = DraftReply("reply", PEER, "quoted", "Peer")
        val original = DraftSnapshot(key, "caption", 1, reply = reply,
            attachmentUrl = "https://example.org/a", attachmentName = "a.png",
            attachmentMime = "image/png", attachmentSize = 42L)
        val repository = ChatRepository(database)
        val writer = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), repository, scope,
            enqueue = { _, _ -> true })
        assertTrue(writer.updateDraft(original).await())
        repository.saveRoute(ACCOUNT, ChatRoute(PEER, thread))
        writer.close()
        database.close()
        database = NemaDatabase.create(context, databaseName)
        val restored = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), ChatRepository(database), scope,
            enqueue = { _, _ -> true }, restoreRouteOnStart = true)
        fun attachments(state: DirectChatState) = listOf(
            state.draftAttachmentUrl, state.draftAttachmentName, state.draftAttachmentMime, state.draftAttachmentSize)
        fun attachments(draft: DraftSnapshot) = listOf(
            draft.attachmentUrl, draft.attachmentName, draft.attachmentMime, draft.attachmentSize)
        try {
            val loaded = withTimeout(5_000) { restored.state.first { it.draft == "caption" } }
            assertEquals(key, DirectConversationKey(loaded.accountId, requireNotNull(loaded.selectedPeer), loaded.selectedThread))
            assertEquals(reply, loaded.draftReply)
            assertEquals(attachments(original), attachments(loaded))
            assertEquals(loaded, loaded.copy())
            assertEquals(loaded.hashCode(), loaded.copy().hashCode())
            val variants = listOf(
                original.copy(attachmentUrl = "https://example.org/b"),
                original.copy(attachmentName = "b.png"),
                original.copy(attachmentMime = "application/octet-stream"),
                original.copy(attachmentSize = 0L),
                original.copy(attachmentUrl = null),
                original.copy(attachmentName = null),
                original.copy(attachmentMime = null),
                original.copy(attachmentSize = null),
            )
            for ((index, variant) in variants.withIndex()) {
                // Copy shares all other state (including the identity-compared timeline).
                val copied = loaded.copy(draftAttachmentUrl = variant.attachmentUrl,
                    draftAttachmentName = variant.attachmentName, draftAttachmentMime = variant.attachmentMime,
                    draftAttachmentSize = variant.attachmentSize)
                assertTrue(copied != loaded)
                assertTrue(copied.hashCode() != loaded.hashCode())
                assertEquals(attachments(variant), attachments(copied.copy()))
                assertTrue(restored.updateDraft(variant.copy(composerRevision = index + 2L)).await())
                val observed = withTimeout(5_000) { restored.state.first { attachments(it) == attachments(variant) } }
                assertEquals("caption", observed.draft)
                assertEquals(reply, observed.draftReply)
            }
            val attachmentOnly = original.copy(body = "", reply = null, composerRevision = 20)
            assertTrue(restored.updateDraft(attachmentOnly).await())
            assertEquals(attachments(original), attachments(withTimeout(5_000) {
                restored.state.first { it.draft.isEmpty() && it.draftReply == null && it.draftAttachmentSize == 42L }
            }))
            assertTrue(restored.updateDraft(DraftSnapshot(key, "", 21)).await())
            withTimeout(5_000) { restored.state.first { it.draftAttachmentUrl == null && it.draftAttachmentName == null &&
                it.draftAttachmentMime == null && it.draftAttachmentSize == null } }
            assertEquals(null, database.messageDao().draft(ACCOUNT, PEER, "6:parentchild"))
            assertEquals(StoredDraft(), ChatRepository(database).observeStoredDraft(key.copy(accountId = OTHER_ACCOUNT)).first())
            assertEquals(StoredDraft(), ChatRepository(database).observeStoredDraft(key.copy(thread = thread.copy(parentId = ThreadId.require("other")))).first())
        } finally {
            restored.close()
        }
    }

    @Test
    fun attachmentOnlyDraftSurvivesPresenterSave() = runBlocking {
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database), scope = scope, enqueue = { _, _ -> true },
        )
        val draft = DraftSnapshot(
            DirectConversationKey(ACCOUNT, PEER), "", 1,
            attachmentUrl = "https://example.org/file", attachmentName = "file.png",
            attachmentMime = "image/png", attachmentSize = 42L,
        )
        try {
            assertTrue(presenter.updateDraft(draft).await())
            assertTrue("Attachment-only accepted draft must remain durable", database.messageDao().directDraft(ACCOUNT, PEER) != null)
        } finally {
            presenter.close()
        }
    }

    @Test
    fun semanticReplyDraftSurvivesPresenterRecreationAndSend() = runBlocking {
        val repository = ChatRepository(database)
        val reply = DraftReply("target-id", "$PEER/device", "target body", "Peer")
        val first = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
        )
        assertTrue(first.selectPeer(PEER))
        assertTrue(
            first.updateDraft(
                DraftSnapshot(DirectConversationKey(ACCOUNT, PEER), "answer", 1, reply = reply),
            ).await(),
        )
        // Draft persistence does not await the independently persisted route.
        withTimeout(5_000) { repository.observeRoute(ACCOUNT).first { it == ChatRoute(PEER) } }
        first.close()
        var sent: DraftSnapshot? = null
        val restored = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, snapshot -> sent = snapshot; true },
            restoreRouteOnStart = true,
        )

        val state = withTimeout(5_000) {
            restored.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == PEER && it.draft == "answer" }
        }
        assertEquals(reply, state.draftReply)
        assertTrue(
            restored.sendDraft(
                DraftSnapshot(DirectConversationKey(ACCOUNT, PEER), state.draft, 2, reply = state.draftReply),
            ).await(),
        )
        assertEquals(reply, sent?.reply)
        restored.close()
    }

    @Test
    fun coldPresenterStartsHomeAfterPersistedThreadRoute() = runBlocking {
        val repository = ChatRepository(database)
        val persistedThread = ThreadRef(
            ThreadId.require("remembered-thread"),
            ThreadId.require("remembered-parent"),
        )
        repository.saveRoute(ACCOUNT, ChatRoute(PEER, persistedThread))

        val cold = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
        )

        val state = cold.state.first { it.conversationsReady }
        assertEquals(null, state.selectedPeer)
        assertEquals(null, state.selectedThread)
        withTimeout(5_000) { repository.observeRoute(ACCOUNT).first { it == null } }
        assertEquals(null, repository.observeRoute(ACCOUNT).first())
        cold.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun conversationsAreNotAuthoritativelyEmptyBeforeFirstQueryEmission() = runBlocking {
        val controlledScope = TestScope()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = controlledScope,
            enqueue = { _, _ -> true },
        )

        try {
            assertEquals(false, presenter.state.value.conversationsReady)
            withTimeout(5_000) {
                while (!presenter.state.value.conversationsReady) {
                    controlledScope.runCurrent()
                    yield()
                }
            }
            assertTrue(presenter.state.value.conversations.isEmpty())
        } finally {
            presenter.close()
            controlledScope.cancel()
        }
    }

    @Test
    fun presenterShowsSqliteConversationsOnFirstReadyState() = runBlocking {
        MessageStore(database).ingest(incoming(ACCOUNT, "cached", "hello from sqlite"))
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ -> true },
        )

        val state = presenter.state.first { it.conversationsReady }
        assertEquals(listOf(PEER), state.conversations.map(ConversationSummary::peerJid))
        assertEquals(listOf("hello from sqlite"), state.conversations.map(ConversationSummary::preview))
        presenter.close()
    }

    @Test
    fun coalescedDraftsPersistLatestBodyPerConversationWhileSendIsHeld() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        val sendEntered = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { account, snapshot ->
                sendEntered.complete(Unit)
                releaseSend.await()
                store.composeDirectDraft(
                    accountId = account.id.value,
                    operationId = "operation-coalesce",
                    localMessageId = "local-coalesce",
                    originId = "origin-coalesce",
                    peerJid = snapshot.key.canonicalBarePeer,
                    senderJid = account.bareJid.value,
                    body = snapshot.body,
                ) != null
            },
        )
        presenter.updateDraft(snapshot(ACCOUNT, PEER, "first")).await()
        val send = presenter.sendDraft(snapshot(ACCOUNT, PEER, "first"))
        sendEntered.await()
        val superseded = presenter.updateDraft(snapshot(ACCOUNT, PEER, "newer", revision = 2).copy(attachmentUrl = "old"))
        val latest = snapshot(ACCOUNT, PEER, "newer", revision = 3).copy(
            attachmentUrl = "https://example.org/latest", attachmentName = "latest.png", attachmentMime = "image/png", attachmentSize = 3L)
        val newer = presenter.updateDraft(latest)
        val other = presenter.updateDraft(snapshot(ACCOUNT, OTHER_PEER, "other"))
        assertTrue(!newer.isCompleted)
        assertTrue(!other.isCompleted)

        releaseSend.complete(Unit)
        assertTrue(send.await())
        assertTrue(newer.await())
        assertTrue(other.await())
        assertTrue(superseded.await())
        assertEquals(StoredDraft("newer", attachmentUrl = latest.attachmentUrl, attachmentName = latest.attachmentName,
            attachmentMime = latest.attachmentMime, attachmentSize = latest.attachmentSize),
            repository.observeStoredDraft(latest.key).first())
        assertEquals("first", store.messages(ACCOUNT).single().body)
        assertEquals("newer", repository.observeDraft(ACCOUNT, PEER).first())
        assertEquals("other", repository.observeDraft(ACCOUNT, OTHER_PEER).first())
        presenter.close()
    }

    @Test
    fun failedSendDoesNotStallLaterDraftPersists() = runBlocking {
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> error("send exploded") },
        )
        assertTrue(presenter.updateDraft(snapshot(ACCOUNT, PEER, "before")).await())
        runCatching { presenter.sendDraft(snapshot(ACCOUNT, PEER, "before")).await() }
        assertTrue(presenter.updateDraft(snapshot(ACCOUNT, PEER, "after")).await())
        assertEquals("after", repository.observeDraft(ACCOUNT, PEER).first())
        presenter.close()
    }

    @Test
    fun draftUpdatesDoNotRefetchPeerIdentities() = runBlocking {
        var identityCalls = 0
        MessageStore(database).ingest(incoming(ACCOUNT, "seed", "hello"))
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ -> true },
            ensurePeerIdentities = { _, _ -> identityCalls += 1 },
        )
        presenter.state.first { it.conversationsReady && it.conversations.isNotEmpty() }
        assertEquals(0, identityCalls)
        assertTrue(presenter.selectPeer(PEER))
        presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == PEER }
        while (identityCalls == 0) {
            yield()
        }
        val afterReady = identityCalls

        repeat(8) { index ->
            assertTrue(
                presenter.updateDraft(
                    snapshot(ACCOUNT, PEER, "typed-$index", revision = index.toLong()),
                ).await(),
            )
        }

        assertEquals(afterReady, identityCalls)
        presenter.close()
    }

    @Test
    fun openingAChatSetsSelectedPeerWithoutWaitingForIdentities() = runBlocking {
        val releaseIdentities = CompletableDeferred<Unit>()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ -> true },
            ensurePeerIdentities = { _, _ -> releaseIdentities.await() },
        )
        assertTrue(presenter.selectPeer(PEER))
        assertEquals(PEER, presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == PEER }.selectedPeer)
        assertTrue(!releaseIdentities.isCompleted)
        releaseIdentities.complete(Unit)
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun blockedIdentityFetchDoesNotDelayRoomEntryOrChangeItsThread() = runBlocking {
        val controlledScope = TestScope()
        val identityEntered = CompletableDeferred<Unit>()
        val releaseIdentity = CompletableDeferred<Unit>()
        val identityCancelled = CompletableDeferred<Unit>()
        val joinFinished = CompletableDeferred<Unit>()
        val releaseJoin = CompletableDeferred<Unit>()
        val joins = CopyOnWriteArrayList<String>()
        val identities = CopyOnWriteArrayList<String>()
        val room = "room@conference.example.org"
        val thread = ThreadRef(ThreadId.require("room-thread"))
        database.messageDao().upsertPeer(PeerEntity(ACCOUNT, room, room = true))
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = controlledScope,
            enqueue = { _, _ -> error("send not expected") },
            ensurePeerIdentities = { _, peers ->
                identities.addAll(peers)
                if (PEER in peers) {
                    identityEntered.complete(Unit)
                    try {
                        releaseIdentity.await()
                    } finally {
                        identityCancelled.complete(Unit)
                    }
                }
            },
            joinMuc = { peer ->
                joins += peer
                try {
                    releaseJoin.await()
                    true
                } finally {
                    joinFinished.complete(Unit)
                }
            },
        )
        suspend fun awaitPhase(predicate: () -> Boolean) {
            withTimeout(5_000) {
                while (!predicate()) {
                    controlledScope.runCurrent()
                    yield()
                }
            }
            controlledScope.runCurrent()
        }
        try {
            assertTrue(presenter.selectPeer(PEER))
            awaitPhase { identityEntered.isCompleted }
            assertTrue(presenter.selectPeer(room))
            assertTrue(presenter.continueThread(thread))
            awaitPhase {
                presenter.state.value.let {
                    it.contentStatus == ChatContentStatus.Ready && it.selectedThread == thread
                }
            }
            val occurrence = presenter.state.value.routeOccurrence
            assertEquals(ChatRoute(room, thread), occurrence.route)
            assertTrue(!releaseIdentity.isCompleted)
            assertEquals(listOf(room), joins)
            assertEquals(occurrence, presenter.state.value.routeOccurrence)
            releaseJoin.complete(Unit)
            awaitPhase { joinFinished.isCompleted }
            repeat(3) { index ->
                val body = "room draft $index"
                val saved = presenter.updateDraft(snapshot(ACCOUNT, room, body).copy(
                    key = DirectConversationKey(ACCOUNT, room, thread),
                ))
                awaitPhase { saved.isCompleted && presenter.state.value.draft == body }
                assertTrue(saved.await())
                assertEquals(listOf(room), joins)
                assertEquals(occurrence, presenter.state.value.routeOccurrence)
                assertEquals(thread, presenter.state.value.selectedThread)
            }
            presenter.close()
            awaitPhase { identityCancelled.isCompleted && joinFinished.isCompleted }
            releaseIdentity.complete(Unit)
            releaseJoin.complete(Unit)
            controlledScope.runCurrent()
            assertEquals(listOf(PEER), identities)
            assertEquals(listOf(room), joins)
        } finally {
            presenter.close()
            controlledScope.cancel()
            controlledScope.runCurrent()
        }
    }

    @Test
    fun openingARoomDoesNotWaitForMucJoin() = runBlocking {
        val joined = CompletableDeferred<Unit>()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ -> true },
            joinMuc = {
                joined.await()
                true
            },
        )
        assertTrue(presenter.joinRoom("room@conference.example.org"))
        assertEquals(
            "room@conference.example.org",
            presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == "room@conference.example.org" }.selectedPeer,
        )
        assertTrue(!joined.isCompleted)
        joined.complete(Unit)
        presenter.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedRoomJoinRetriesOnExplicitEntryAndReconnectButNotDraftEmissions() = runTest {
        val room = "room@conference.example.org"
        val connection = kotlinx.coroutines.flow.MutableStateFlow<Any?>("offline")
        val attempts = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val releases = kotlinx.coroutines.channels.Channel<Boolean>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        var count = 0
        val presenter = DirectChatPresenter(
            accountConfiguration(ACCOUNT, SELF), ChatRepository(database), backgroundScope, { _, _ -> true },
            directoryConnection = connection,
            joinMuc = { attempts.send(++count); releases.receive() },
        )
        suspend fun draft(body: String) {
            assertTrue(presenter.updateDraft(snapshot(ACCOUNT, room, body)).await())
            presenter.state.first { it.draft == body }
            runCurrent()
        }
        try {
            presenter.joinRoom(room)
            assertEquals(1, attempts.receive())
            presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == room }
            draft("in flight")
            val previous = presenter.state.value.routeOccurrence
            presenter.joinRoom(room)
            presenter.state.first { it.routeOccurrence != previous && it.contentStatus == ChatContentStatus.Ready }
            runCurrent()
            assertTrue(attempts.tryReceive().isFailure)
            releases.send(false)
            runCurrent()
            draft("failed")
            assertTrue(attempts.tryReceive().isFailure)
            presenter.joinRoom(room)
            assertEquals(2, attempts.receive())
            releases.send(true)
            runCurrent()
            presenter.joinRoom(room)
            draft("joined")
            assertTrue(attempts.tryReceive().isFailure)
            connection.value = "connected-generation-2"
            assertEquals(3, attempts.receive())
            releases.send(false)
            runCurrent()
            draft("second failure")
            assertTrue(attempts.tryReceive().isFailure)
            connection.value = "connected-generation-3"
            assertEquals(4, attempts.receive())
            releases.send(true)
            runCurrent()
        } finally { presenter.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun roomJoinExceptionAndCancellationCanRetryAndOldSuccessCannotSuppressNewConnection() = runTest {
        val room = "room@conference.example.org"
        val connection = kotlinx.coroutines.flow.MutableStateFlow<Any?>("generation-1")
        val attempts = kotlinx.coroutines.channels.Channel<CompletableDeferred<Boolean>>(
            kotlinx.coroutines.channels.Channel.UNLIMITED,
        )
        val presenter = DirectChatPresenter(
            accountConfiguration(ACCOUNT, SELF), ChatRepository(database), backgroundScope, { _, _ -> true },
            directoryConnection = connection,
            joinMuc = { CompletableDeferred<Boolean>().also { attempts.send(it) }.await() },
        )
        try {
            presenter.joinRoom(room)
            val old = attempts.receive()
            connection.value = "generation-2"
            val current = attempts.receive()
            old.complete(true)
            current.completeExceptionally(IllegalStateException("join failed"))
            runCurrent()
            presenter.joinRoom(room)
            val retry = attempts.receive()
            retry.cancel()
            runCurrent()
            presenter.joinRoom(room)
            attempts.receive().complete(true)
            runCurrent()
            val previous = presenter.state.value.routeOccurrence
            presenter.joinRoom(room)
            presenter.state.first { it.routeOccurrence != previous && it.contentStatus == ChatContentStatus.Ready }
            runCurrent()
            assertTrue(attempts.tryReceive().isFailure)
        } finally { presenter.close() }
    }

    @Test
    fun joiningTheSameRoomDoesNotStartArchiveSyncTwice() = runBlocking {
        var joins = 0
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ -> true },
            joinMuc = {
                joins += 1
                true
            },
        )
        val room = "room@conference.example.org"
        assertTrue(presenter.joinRoom(room))
        presenter.closeConversation()
        assertTrue(presenter.joinRoom(room))
        while (joins == 0) {
            yield()
        }
        repeat(4) { yield() }
        assertEquals(1, joins)
        presenter.close()
    }

    @Test
    fun closingAChatReturnsHomeWithoutWaitingForRoutePersist() = runBlocking {
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ -> true },
        )
        assertTrue(presenter.selectPeer(PEER))
        presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == PEER }
        presenter.closeConversation()
        assertEquals(null, presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == null }.selectedPeer)
        presenter.close()
    }

    @Test
    fun selectingAfterCloseKeepsTheNewPeerAndPersistsIt() = runBlocking {
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
        )
        assertTrue(presenter.selectPeer(PEER))
        presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == PEER }
        presenter.closeConversation()
        assertTrue(presenter.selectPeer(OTHER_PEER))
        assertEquals(OTHER_PEER, presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == OTHER_PEER }.selectedPeer)
        assertEquals(
            OTHER_PEER,
            repository.observeRoute(ACCOUNT).first { it?.peerJid == OTHER_PEER }?.peerJid,
        )
        assertTrue(presenter.updateDraft(snapshot(ACCOUNT, OTHER_PEER, "still-open")).await())
        assertEquals("still-open", repository.observeDraft(ACCOUNT, OTHER_PEER).first())
        presenter.close()
    }

    @Test
    fun restoreDoesNotClobberANewerInMemorySelect() = runBlocking {
        val repository = ChatRepository(database)
        repository.saveRoute(ACCOUNT, ChatRoute(PEER))
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
            restoreRouteOnStart = true,
        )
        assertTrue(presenter.selectPeer(OTHER_PEER))
        assertEquals(OTHER_PEER, presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == OTHER_PEER }.selectedPeer)
        assertEquals(
            OTHER_PEER,
            repository.observeRoute(ACCOUNT).first { it?.peerJid == OTHER_PEER }?.peerJid,
        )
        presenter.close()
    }

    @Test
    fun childThreadCanStartFromSelectedMessageThread() = runBlocking {
        val repository = ChatRepository(database)
        val parent = ThreadRef(ThreadId.require("message-thread"))
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> true },
            threadingPolicy = ThreadingPolicy { ThreadId.require("reply-thread") },
        )
        assertTrue(presenter.selectPeer(PEER))

        assertTrue(presenter.startChildThreadOf(parent))

        val selected = presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "reply-thread" }.selectedThread
        assertEquals(parent.id, selected?.parentId)
        presenter.close()
    }

    @Test
    fun draftsKeepCompleteThreadLineageScope() = runBlocking {
        val repository = ChatRepository(database)
        val first = DirectConversationKey(
            ACCOUNT,
            PEER,
            ThreadRef(ThreadId.require("shared-child"), ThreadId.require("first-parent")),
        )
        val second = DirectConversationKey(
            ACCOUNT,
            PEER,
            ThreadRef(ThreadId.require("shared-child"), ThreadId.require("second-parent")),
        )

        repository.saveDraft(first, "first lineage")
        repository.saveDraft(second, "second lineage")

        assertEquals("first lineage", repository.observeDraft(first).first())
        assertEquals("second lineage", repository.observeDraft(second).first())
    }

    @Test
    fun presenterProjectsPersistedDraftAndUncertainSend() = runBlocking {
        val store = MessageStore(database)
        val intent = outbound("uncertain")
        store.compose(intent)
        val claim = requireNotNull(store.claim(ACCOUNT, intent.operationId, generation = 3))
        store.recordPotentialDelivery(claim)
        val repository = ChatRepository(database)
        val retried = mutableListOf<org.thanosapollo.nema.storage.RetryUncertainKey>()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> error("send not expected") },
            retry = { _, key -> retried += key },
        )

        assertTrue(presenter.selectPeer("$PEER/resource"))
        presenter.updateDraft(snapshot(ACCOUNT, PEER, "survives restart")).await()
        val state = presenter.state.first {
            it.selectedPeer == PEER && it.draft == "survives restart" && it.messages.isNotEmpty()
        }

        assertEquals(DeliveryPresentation.UNCERTAIN, state.messages.single().delivery)
        val retryKey = org.thanosapollo.nema.storage.RetryUncertainKey(
            ACCOUNT,
            intent.operationId,
            generation = 3,
            attempt = 1,
        )
        assertEquals(retryKey, state.messages.single().retryUncertainKey)
        presenter.retryUncertain(retryKey)
        assertEquals(listOf(retryKey), retried)
        assertEquals("survives restart", repository.observeDraft(ACCOUNT, PEER).first())
    }

    @Test
    fun presenterRequestsAtomicPersistedDraftSend() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        val sent = mutableListOf<Pair<String, String>>()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { account, snapshot ->
                sent += account.id.value to snapshot.key.canonicalBarePeer
                store.composeDirectDraft(
                    accountId = account.id.value,
                    operationId = "operation-presenter",
                    localMessageId = "local-presenter",
                    originId = "origin-presenter",
                    peerJid = snapshot.key.canonicalBarePeer,
                    senderJid = account.bareJid.value,
                    body = snapshot.body,
                ) != null
            },
        )
        presenter.selectPeer(PEER)
        val captured = snapshot(ACCOUNT, PEER, "hello")
        presenter.updateDraft(captured).await()
        presenter.state.first { it.draft == "hello" }

        assertTrue(presenter.sendDraft(captured).await())

        assertEquals(listOf(ACCOUNT to PEER), sent)
        assertEquals("", repository.observeDraft(ACCOUNT, PEER).first())
        assertEquals("hello", store.messages(ACCOUNT).single().body)
    }

    @Test
    fun delayedThreadSendPreservesLaterPeer() = delayedThreadSendPreservesNavigation("peer")

    @Test
    fun delayedThreadSendPreservesClosedConversation() = delayedThreadSendPreservesNavigation("close")

    @Test
    fun delayedThreadSendPreservesNewOccurrenceOfSameRoute() = delayedThreadSendPreservesNavigation("return")

    private fun delayedThreadSendPreservesNavigation(destination: String) = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<DraftSnapshot>()
            val store = MessageStore(database)
            val presenter = DirectChatPresenter(
                account = accountConfiguration(ACCOUNT, SELF),
                repository = ChatRepository(database),
                scope = scope,
                enqueue = { account, captured ->
                    entered.complete(Unit)
                    release.await()
                    sent += captured
                    store.composeDirectDraft(
                        accountId = account.id.value,
                        operationId = "delayed-topic-operation",
                        localMessageId = "delayed-topic-message",
                        originId = "delayed-topic-origin",
                        peerJid = captured.key.canonicalBarePeer,
                        senderJid = account.bareJid.value,
                        body = captured.body,
                        thread = captured.outboundThread,
                        draftThread = captured.key.thread,
                    ) != null
                },
            )
            try {
                assertTrue(presenter.selectPeer(PEER))
                presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == PEER }
                val captured = snapshot(ACCOUNT, PEER, "accepted topic")
                val send = presenter.sendDraftAsNewThread(captured)
                entered.await()
                if (destination == "close") {
                    presenter.closeConversation()
                } else {
                    assertTrue(presenter.selectPeer(OTHER_PEER))
                    if (destination == "return") assertTrue(presenter.selectPeer(PEER))
                }
                release.complete(Unit)
                assertTrue(send.await())
                assertEquals(captured.key, sent.single().key)
                assertEquals(captured.body, sent.single().body)
                assertTrue(sent.single().outboundThread != null)
                assertEquals(PEER, store.messages(ACCOUNT).single().peerJid)
                assertEquals(captured.body, store.messages(ACCOUNT).single().body)
                assertEquals(sent.single().outboundThread?.id?.value, store.messages(ACCOUNT).single().threadId)
                assertEquals(1, store.outboxes(ACCOUNT).size)
                val marker = ThreadRef(ThreadId.require("navigation-observation"))
                // The public route command reads authoritative state synchronously;
                // a matching marker then makes the asynchronous projection observable.
                if (destination == "close") {
                    assertTrue(!presenter.continueThread(marker))
                } else {
                    // A stale topic in A->B->A is observable via child creation.
                    assertTrue(!presenter.startChildThread())
                    assertTrue(presenter.continueThread(marker))
                    val observed = presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread == marker }
                    assertEquals(if (destination == "return") PEER else OTHER_PEER, observed.selectedPeer)
                }
            } finally {
                release.complete(Unit)
                presenter.close()
            }
        }
    }

    @Test
    fun queuedThreadSendCapturesOccurrenceBeforeActionStarts() = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<DraftSnapshot>()
            val presenter = DirectChatPresenter(
                account = accountConfiguration(ACCOUNT, SELF),
                repository = ChatRepository(database),
                scope = scope,
                enqueue = { _, captured ->
                    if (captured.body == "predecessor") {
                        entered.complete(Unit)
                        release.await()
                    }
                    sent += captured
                    true
                },
            )
            try {
                assertTrue(presenter.selectPeer(PEER))
                val predecessor = presenter.sendDraft(snapshot(ACCOUNT, PEER, "predecessor", revision = 1))
                entered.await()
                val queued = presenter.sendDraftAsNewThread(snapshot(ACCOUNT, PEER, "queued", revision = 2))
                assertTrue(!queued.isCompleted)
                assertTrue(presenter.selectPeer(OTHER_PEER))
                assertTrue(presenter.selectPeer(PEER))
                release.complete(Unit)
                assertTrue(predecessor.await())
                assertTrue(queued.await())
                assertEquals(listOf("predecessor", "queued"), sent.map { it.body })
                assertEquals(PEER, sent.last().key.canonicalBarePeer)
                assertTrue(sent.last().outboundThread != null)
                assertTrue(!presenter.startChildThread())
            } finally {
                release.complete(Unit)
                presenter.close()
            }
        }
    }

    @Test
    fun restoredRoomThreadJoinsWithoutResettingRoute() = runBlocking {
        val repository = ChatRepository(database)
        val thread = ThreadRef(ThreadId.require("child"), ThreadId.require("parent"))
        repository.markRoom(ACCOUNT, PEER)
        repository.saveRoute(ACCOUNT, ChatRoute(PEER, thread))
        val joined = CompletableDeferred<Unit>()
        var joins = 0
        val presenter = DirectChatPresenter(
            accountConfiguration(ACCOUNT, SELF), repository, scope, { _, _ -> true },
            joinMuc = { joins++; joined.complete(Unit); true }, restoreRouteOnStart = true,
        )
        try {
            val ready = withTimeout(5_000) { presenter.state.first { it.selectedPeerGroupChat && it.contentStatus == ChatContentStatus.Ready } }
            withTimeout(5_000) { joined.await() }
            assertEquals(thread, ready.selectedThread)
            assertEquals(ChatRoute(PEER, thread), ready.routeOccurrence.route)
            assertEquals(ready.routeOccurrence, presenter.state.value.routeOccurrence)
            presenter.continueThread(thread)
            val reselected = withTimeout(5_000) { presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.routeOccurrence != ready.routeOccurrence } }
            assertEquals(thread, reselected.selectedThread)
            assertEquals(1, joins)
        } finally {
            presenter.close()
        }
    }

    @Test
    fun heldRoomQueriesExposeEveryOccurrenceAndDiscardOldContent() = runBlocking {
        MessageStore(database).ingest(incoming(ACCOUNT, "old-action", "old actionable body"))
        val gate = RouteQueryGate()
        database.close()
        database = Room.databaseBuilder(context, NemaDatabase::class.java, databaseName)
            .setQueryCoroutineContext(gate).allowMainThreadQueries().build()
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), ChatRepository(database), scope, { _, _ -> true })
        try {
            presenter.selectPeer(PEER)
            val ready = withTimeout(5_000) { presenter.state.first { it.messages.isNotEmpty() } }
            var generation = ready.routeOccurrence.generation
            val entered = gate.hold()
            val selections = mutableListOf<kotlinx.coroutines.Deferred<Boolean>>()
            suspend fun loading(peer: String, thread: ThreadRef? = null) {
                val loading = withTimeout(5_000) {
                    presenter.state.first { it.routeOccurrence.generation > generation }
                }
                generation = loading.routeOccurrence.generation
                assertEquals(ACCOUNT, loading.accountId)
                assertEquals(peer, loading.selectedPeer)
                assertEquals(thread, loading.selectedThread)
                assertEquals(ChatContentStatus.Loading, loading.contentStatus)
                assertTrue(loading.messages.isEmpty())
                assertEquals("", loading.draft)
                assertEquals(null, loading.draftReply)
                assertEquals(null, loading.selectedPeerDisplayName)
            }
            selections += async(start = CoroutineStart.UNDISPATCHED) { presenter.selectPeer(OTHER_PEER) }
            withTimeout(5_000) { entered.await() }
            loading(OTHER_PEER)
            for (peer in listOf(PEER, PEER)) {
                selections += async(start = CoroutineStart.UNDISPATCHED) { presenter.selectPeer(peer) }
                loading(peer)
            }
            val child = ThreadId.require("same-child")
            for (parent in listOf("first-parent", "second-parent")) {
                val thread = ThreadRef(child, ThreadId.require(parent))
                assertTrue(presenter.continueThread(thread))
                loading(PEER, thread)
            }
            presenter.closeConversation()
            val home = withTimeout(5_000) { presenter.state.first { it.selectedPeer == null } }
            assertTrue(home.routeOccurrence.generation > generation)
            generation = home.routeOccurrence.generation
            selections += async(start = CoroutineStart.UNDISPATCHED) { presenter.selectPeer(PEER) }
            loading(PEER)
            val finalOccurrence = presenter.state.value.routeOccurrence
            gate.release()
            selections.forEach { assertTrue(it.await()) }
            val reopened = withTimeout(5_000) { presenter.state.first { it.contentStatus == ChatContentStatus.Ready } }
            assertEquals(finalOccurrence, reopened.routeOccurrence)
            assertEquals("old-action", reopened.messages.single().id)
        } finally {
            gate.release()
            presenter.close()
        }
    }

    @Test
    fun authoritativeDraftReadyThenLiveFailureCanRetrySameOccurrenceRoute() = runBlocking {
        val repository = ChatRepository(database)
        val draft = snapshot(ACCOUNT, PEER, "stored before opening")
        repository.saveDraft(draft.key, draft.body)
        val entered = CompletableDeferred<Unit>()
        val fail = CompletableDeferred<Unit>()
        var shouldFail = true
        val presenter = DirectChatPresenter(
            accountConfiguration(ACCOUNT, SELF), repository, scope, { _, _ -> true },
            observeRtt = {
                kotlinx.coroutines.flow.flow {
                    emit(null)
                    if (shouldFail) {
                        entered.complete(Unit)
                        fail.await()
                        throw IllegalStateException("controlled live failure")
                    }
                }
            },
        )
        try {
            presenter.selectPeer(PEER)
            val ready = withTimeout(5_000) { presenter.state.first { it.selectedPeer == PEER && it.contentStatus == ChatContentStatus.Ready } }
            assertEquals("stored before opening", ready.draft)
            entered.await()
            fail.complete(Unit)
            val failed = withTimeout(5_000) { presenter.state.first { it.contentStatus == ChatContentStatus.Failed } }
            assertEquals(ready.routeOccurrence, failed.routeOccurrence)
            assertEquals("", failed.draft)
            assertTrue(failed.messages.isEmpty())
            shouldFail = false
            presenter.selectPeer(PEER)
            val recovered = withTimeout(5_000) { presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.routeOccurrence != ready.routeOccurrence } }
            assertEquals("stored before opening", recovered.draft)
            assertEquals(PEER, recovered.selectedPeer)
        } finally {
            fail.complete(Unit)
            presenter.close()
        }
    }

    @Test
    fun failedRoomReadAndListCannotKillNavigationProjection() = runTest {
        // Room uses real executors; keep timeout clocks real while runTest owns failures.
        kotlinx.coroutines.withContext(Dispatchers.Default) {
            database.close()
            val failReads = java.util.concurrent.atomic.AtomicBoolean(true)
            val listReadFailed = CompletableDeferred<Unit>()
            val draftReadFailed = CompletableDeferred<Unit>()
            val listSql = org.thanosapollo.nema.storage.CHEAP_CONVERSATION_SUMMARIES.replace(":accountId", "?").trim()
            // Fail SELECT execution only: dropping tables also breaks Room's invalidation
            // triggers in unrelated route-write transactions, leaking launch failures.
            database = Room.databaseBuilder(context, NemaDatabase::class.java, databaseName)
                .openHelperFactory { configuration ->
                    val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(configuration)
                    fun wrap(db: androidx.sqlite.db.SupportSQLiteDatabase) =
                        object : androidx.sqlite.db.SupportSQLiteDatabase by db {
                            override fun query(query: androidx.sqlite.db.SupportSQLiteQuery): android.database.Cursor =
                                query(query, null)

                            override fun query(
                                query: androidx.sqlite.db.SupportSQLiteQuery,
                                cancellationSignal: android.os.CancellationSignal?,
                            ): android.database.Cursor {
                                val sql = query.sql
                                if (failReads.get() && (sql.trim() == listSql ||
                                        sql.contains("FROM message_drafts"))) {
                                    if (sql.contains("FROM message_drafts")) draftReadFailed.complete(Unit)
                                    else listReadFailed.complete(Unit)
                                    // Let SQLite produce the exception inside the real Room observer.
                                    return db.query("SELECT * FROM deliberately_missing_read_source")
                                }
                                return if (cancellationSignal == null) db.query(query) else db.query(query, cancellationSignal)
                            }
                        }
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper by helper {
                        override val writableDatabase get() = wrap(helper.writableDatabase)
                        override val readableDatabase get() = wrap(helper.readableDatabase)
                    }
                }
                .build()
            val owner = SupervisorJob(backgroundScope.coroutineContext[Job])
            val repository = ChatRepository(database)
            val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), repository,
                CoroutineScope(backgroundScope.coroutineContext + owner + Dispatchers.Default), { _, _ -> true })
            try {
                assertTrue(presenter.selectPeer(PEER))
                val failed = withTimeout(5_000) { presenter.state.first { it.contentStatus == ChatContentStatus.Failed } }
                assertEquals(PEER, failed.selectedPeer)
                assertTrue(!failed.conversationsReady)
                assertTrue(failed.messages.isEmpty())
                withTimeout(5_000) { repository.observeRoute(ACCOUNT).first { it == ChatRoute(PEER) } }
                withTimeout(5_000) { draftReadFailed.await(); listReadFailed.await() }
                presenter.closeConversation()
                withTimeout(5_000) { presenter.state.first { it.selectedPeer == null } }
                withTimeout(5_000) { repository.observeRoute(ACCOUNT).first { it == null } }
                failReads.set(false)
                assertTrue(presenter.selectPeer(OTHER_PEER))
                val recovered = withTimeout(5_000) { presenter.state.first { it.selectedPeer == OTHER_PEER && it.contentStatus == ChatContentStatus.Ready } }
                assertTrue(!recovered.conversationsReady)
                assertTrue(presenter.selectPeer(PEER))
                val retried = withTimeout(5_000) { presenter.state.first { it.selectedPeer == PEER && it.contentStatus == ChatContentStatus.Ready } }
                assertTrue(retried.routeOccurrence.generation > failed.routeOccurrence.generation)
                withTimeout(5_000) { repository.observeRoute(ACCOUNT).first { it == ChatRoute(PEER) } }
            } finally {
                presenter.close()
                owner.cancel()
                owner.join()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun navigationPublishesBeforeInitialRoomQueriesComplete() = runTest {
        database.close()
        val queries = kotlinx.coroutines.test.TestCoroutineScheduler()
        database = Room.databaseBuilder(context, NemaDatabase::class.java, databaseName)
            .setQueryCoroutineContext(StandardTestDispatcher(queries))
            .allowMainThreadQueries()
            .build()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = backgroundScope,
            enqueue = { _, _ -> error("send not expected") },
        )
        try {
            val selection = async(start = CoroutineStart.UNDISPATCHED) { presenter.selectPeer(PEER) }
            runCurrent()
            assertEquals(PEER, presenter.state.value.selectedPeer)
            assertTrue(presenter.state.value.messages.isEmpty())
            assertEquals("", presenter.state.value.draft)
            assertTrue(!presenter.state.value.conversationsReady)
            presenter.closeConversation()
            runCurrent()
            assertEquals(null, presenter.state.value.selectedPeer)
            selection.cancel()
        } finally {
            presenter.close()
            queries.runCurrent()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun delayedNewThreadReadPreservesNavigationOccurrence() = runTest {
        database.close()
        database = Room.databaseBuilder(context, NemaDatabase::class.java, databaseName)
            .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
            .allowMainThreadQueries()
            .build()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = backgroundScope,
            enqueue = { _, _ -> error("send not expected") },
        )
        try {
            assertTrue(presenter.selectPeer(PEER))
            presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == PEER }
            for (destination in listOf("peer", "close", "return", "current")) {
                assertTrue(presenter.selectPeer(PEER))
                runCurrent()
                // Enter now; Room's first peer read must wait on the controlled
                // query dispatcher. No repository stub or elapsed-time barrier.
                val start = async(start = CoroutineStart.UNDISPATCHED) { presenter.startNewThread() }
                assertTrue(!start.isCompleted)
                val navigation = if (destination == "peer" || destination == "return") {
                    async(start = CoroutineStart.UNDISPATCHED) { presenter.selectPeer(OTHER_PEER) }
                } else null
                val returned = if (destination == "return") {
                    async(start = CoroutineStart.UNDISPATCHED) { presenter.selectPeer(PEER) }
                } else null
                if (destination == "close") presenter.closeConversation()
                assertEquals(destination == "current", start.await())
                navigation?.await()
                returned?.await()
                val marker = ThreadRef(ThreadId.require("read-observation-$destination"))
                if (destination == "close") {
                    assertTrue(!presenter.continueThread(marker))
                } else {
                    assertEquals(destination == "current", presenter.startChildThread())
                    assertTrue(presenter.continueThread(marker))
                    val observed = presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread == marker }
                    assertEquals(if (destination == "peer") OTHER_PEER else PEER, observed.selectedPeer)
                }
            }
        } finally {
            presenter.close()
        }
    }

    @Test
    fun sendDraftAsNewThreadOpensTheNewThread() = runBlocking {
        val repository = ChatRepository(database)
        val sent = mutableListOf<DraftSnapshot>()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, snapshot -> sent += snapshot; true },
            threadingPolicy = ThreadingPolicy { ThreadId.require("new-thread") },
        )
        assertTrue(presenter.selectPeer(PEER))
        val captured = snapshot(ACCOUNT, PEER, "thread root")

        assertTrue(presenter.sendDraftAsNewThread(captured).await())

        assertEquals(DirectConversationKey(ACCOUNT, PEER), sent.single().key)
        assertEquals("new-thread", sent.single().outboundThread?.id?.value)
        val session = requireNotNull(repository.observeCurrentSession(ACCOUNT, PEER).first())
        assertEquals(session.id, sent.single().outboundThread?.parentId)
        assertEquals(
            "new-thread",
            presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedThread?.id?.value == "new-thread" }.selectedThread?.id?.value,
        )
        presenter.close()
    }

    @Test
    fun newerDraftWaitsBehindCapturedSendAndRemainsAuthoritative() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        val sendEntered = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { account, snapshot ->
                sendEntered.complete(Unit)
                releaseSend.await()
                store.composeDirectDraft(
                    accountId = account.id.value,
                    operationId = "operation-ordered",
                    localMessageId = "local-ordered",
                    originId = "origin-ordered",
                    peerJid = snapshot.key.canonicalBarePeer,
                    senderJid = account.bareJid.value,
                    body = snapshot.body,
                ) != null
            },
        )
        val first = snapshot(ACCOUNT, PEER, "first", revision = 1).copy(
            attachmentUrl = "https://example.org/old", attachmentName = "old.png", attachmentMime = "image/png", attachmentSize = 1L)
        val newer = snapshot(ACCOUNT, PEER, "newer", revision = 2).copy(
            attachmentUrl = "https://example.org/new", attachmentName = "new.pdf", attachmentMime = "application/pdf", attachmentSize = 2L)
        presenter.updateDraft(first).await()

        val send = presenter.sendDraft(first)
        sendEntered.await()
        val update = presenter.updateDraft(newer)
        assertTrue(!update.isCompleted)

        releaseSend.complete(Unit)
        assertTrue(send.await())
        assertTrue(update.await())
        assertEquals("first", store.messages(ACCOUNT).single().body)
        assertEquals("newer", repository.observeDraft(ACCOUNT, PEER).first())
        assertEquals(StoredDraft("newer", attachmentUrl = newer.attachmentUrl, attachmentName = newer.attachmentName,
            attachmentMime = newer.attachmentMime, attachmentSize = newer.attachmentSize),
            repository.observeStoredDraft(newer.key).first())
        presenter.close()
    }

    @Test
    fun delayedDraftKeepsCapturedAccountAndPeerAfterSelectionChanges() = runBlocking {
        val repository = ChatRepository(database)
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ -> error("send not expected") },
        )
        val captured = snapshot(ACCOUNT, PEER, "draft A")
        assertTrue(presenter.selectPeer(OTHER_PEER))

        assertTrue(presenter.updateDraft(captured).await())

        assertEquals("draft A", repository.observeDraft(ACCOUNT, PEER).first())
        assertEquals("", repository.observeDraft(ACCOUNT, OTHER_PEER).first())
        assertEquals("", repository.observeDraft(OTHER_ACCOUNT, PEER).first())
        assertEquals(OTHER_PEER, presenter.state.first { it.contentStatus == ChatContentStatus.Ready && it.selectedPeer == OTHER_PEER }.selectedPeer)
    }

    @Test
    fun delayedSendAtomicallyConsumesOnlyCapturedDraft() = runBlocking {
        val repository = ChatRepository(database)
        val store = MessageStore(database)
        repository.saveDraft(ACCOUNT, PEER, "draft A")
        repository.saveDraft(ACCOUNT, OTHER_PEER, "draft B")
        repository.saveDraft(OTHER_ACCOUNT, PEER, "other account draft")
        val sent = mutableListOf<Pair<String, String>>()
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { account, snapshot ->
                sent += account.id.value to snapshot.key.canonicalBarePeer
                store.composeDirectDraft(
                    accountId = account.id.value,
                    operationId = "operation-delayed",
                    localMessageId = "local-delayed",
                    originId = "origin-delayed",
                    peerJid = snapshot.key.canonicalBarePeer,
                    senderJid = account.bareJid.value,
                    body = snapshot.body,
                ) != null
            },
        )
        val captured = snapshot(ACCOUNT, PEER, "draft A")
        assertTrue(presenter.selectPeer(OTHER_PEER))

        assertTrue(presenter.sendDraft(captured).await())

        assertEquals(listOf(ACCOUNT to PEER), sent)
        assertEquals("", repository.observeDraft(ACCOUNT, PEER).first())
        assertEquals("draft B", repository.observeDraft(ACCOUNT, OTHER_PEER).first())
        assertEquals("other account draft", repository.observeDraft(OTHER_ACCOUNT, PEER).first())
        assertEquals(listOf(PEER), store.messages(ACCOUNT).map { it.peerJid })
        assertEquals(listOf("operation-delayed"), store.outboxes(ACCOUNT).map { it.operationId })
        assertTrue(store.messages(OTHER_ACCOUNT).isEmpty())
        assertTrue(store.outboxes(OTHER_ACCOUNT).isEmpty())
    }

    @Test
    fun attachmentOutboundPreservesLiteralDraftAcrossRejectionRetryAndCompletion() = runBlocking {
        for (newThread in listOf(false, true)) {
            for ((index, body) in listOf("", " \t\n", " literal caption ").withIndex()) {
                val peer = "attachment-$newThread-$index@example.org"
                val repository = ChatRepository(database)
                val store = MessageStore(database)
                val captured = snapshot(ACCOUNT, peer, body, revision = 7).copy(
                    attachmentUrl = "https://example.org/file.png",
                    attachmentName = "file.png", attachmentMime = "image/png", attachmentSize = 42L,
                    reply = DraftReply("reply-id", peer, "quoted body", "Sender"),
                )
                val literal = StoredDraft(body, captured.reply, captured.attachmentUrl,
                    captured.attachmentName, captured.attachmentMime, captured.attachmentSize)
                var attempts = 0
                val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), repository, scope,
                    enqueue = { account, outbound ->
                        attempts += 1
                        if (attempts == 1) false else store.composeDirectDraft(
                            accountId = account.id.value, operationId = peer, localMessageId = peer,
                            originId = peer, peerJid = outbound.key.canonicalBarePeer,
                            senderJid = account.bareJid.value, body = outbound.body,
                            thread = outbound.outboundThread ?: outbound.key.thread,
                            draftThread = outbound.key.thread,
                            attachmentUrl = outbound.attachmentUrl, attachmentName = outbound.attachmentName,
                            attachmentMime = outbound.attachmentMime, attachmentSize = outbound.attachmentSize,
                            replyToId = outbound.reply?.id, replyToJid = outbound.reply?.to,
                            replyFallbackBody = outbound.reply?.body, replyFallbackSender = outbound.reply?.senderLabel,
                        ) != null
                    })
                try {
                    val identity = PendingSendIdentity(captured.key, captured.composerRevision)
                    suspend fun send() = if (newThread) presenter.sendDraftAsNewThread(captured).await()
                        else presenter.sendDraft(captured).await()
                    assertTrue(presenter.updateDraft(captured).await())
                    assertEquals(literal, repository.observeStoredDraft(captured.key).first())
                    assertTrue(!send())
                    assertEquals(1, attempts)
                    assertTrue(!presenter.isSending(identity))
                    assertEquals(literal, repository.observeStoredDraft(captured.key).first())
                    assertTrue(store.outbox(ACCOUNT, peer) == null)
                    assertTrue(send())
                    assertEquals(2, attempts)
                    val completed = withTimeout(5_000) {
                        presenter.state.first { identity in it.completedSendSnapshots }
                    }
                    assertEquals(captured, completed.completedSendSnapshots[identity])
                    assertTrue(!send())
                    assertEquals(2, attempts)
                    val outbox = requireNotNull(store.outbox(ACCOUNT, peer))
                    assertEquals(OutboxStatus.PENDING, outbox.status)
                    val message = store.messages(ACCOUNT).single { it.localMessageId == outbox.messageId }
                    assertEquals(if (body.isBlank()) captured.attachmentUrl else body, message.body)
                    assertEquals(captured.attachmentUrl, message.attachmentUrl)
                    assertEquals(captured.attachmentName, message.attachmentName)
                    assertEquals(captured.attachmentMime, message.attachmentMime)
                    assertEquals(captured.attachmentSize, message.attachmentSize)
                    assertEquals(captured.reply?.id, message.replyToId)
                    assertEquals(captured.reply?.to, message.replyToJid)
                    assertEquals(captured.reply?.body, message.replyFallbackBody)
                    if (newThread) assertTrue(message.parentThreadId != null)
                    assertEquals(StoredDraft(), repository.observeStoredDraft(captured.key).first())
                } finally {
                    presenter.close()
                }
            }
        }
    }

    @Test
    fun blankDraftWithoutUsableAttachmentStillRejectsBothSendPaths() = runBlocking {
        val presenter = DirectChatPresenter(accountConfiguration(ACCOUNT, SELF), ChatRepository(database), scope,
            enqueue = { _, _ -> error("Blank draft must not reach enqueue") })
        try {
            for (body in listOf("", " \t\n")) for (url in listOf(null, "", " \t")) {
                val captured = snapshot(ACCOUNT, PEER, body).copy(attachmentUrl = url)
                assertTrue(!presenter.sendDraft(captured).await())
                assertTrue(!presenter.sendDraftAsNewThread(captured).await())
            }
            val correction = snapshot(ACCOUNT, PEER, " ").copy(
                correction = DraftCorrection("local", "wire", "original"))
            assertTrue(!presenter.sendDraft(correction).await())
            assertTrue(!presenter.sendDraftAsNewThread(correction).await())
        } finally {
            presenter.close()
        }
    }

    @Test
    fun sendDraftRecordsRevisionOnceUntilSettled() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        var enqueues = 0
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ ->
                enqueues += 1
                gate.await()
            },
        )
        val snap = snapshot(ACCOUNT, PEER, "once")
        val first = presenter.sendDraft(snap)
        val identity = PendingSendIdentity(snap.key, snap.composerRevision)
        presenter.state.first { identity in it.pendingSendIdentities }
        val second = presenter.sendDraft(snap)
        assertEquals(setOf(identity), presenter.state.value.pendingSendIdentities)
        assertTrue(!second.await())
        gate.complete(true)
        assertTrue(first.await())
        presenter.state.first { identity in it.completedSendSnapshots }
        assertEquals(1, enqueues)
        presenter.acknowledgeCompletedSends(setOf(identity))
        presenter.state.first { it.completedSendSnapshots.isEmpty() }
        presenter.close()
    }

    @Test
    fun explodedSendReleasesRevisionForRetry() = runBlocking {
        var enqueues = 0
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ ->
                enqueues += 1
                if (enqueues == 1) error("send exploded")
                true
            },
        )
        val snap = snapshot(ACCOUNT, PEER, "once")
        val identity = PendingSendIdentity(snap.key, snap.composerRevision)
        runCatching { presenter.sendDraft(snap).await() }
        presenter.state.first {
            identity !in it.pendingSendIdentities && identity !in it.completedSendSnapshots
        }
        assertTrue(presenter.sendDraft(snap).await())
        assertEquals(2, enqueues)
        presenter.close()
    }

    @Test
    fun invalidActionKeysFailClosed() = runBlocking {
        val repository = ChatRepository(database)
        var enqueueCalls = 0
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, _ ->
                enqueueCalls += 1
                true
            },
        )
        val wrongAccount = snapshot(OTHER_ACCOUNT, PEER, "wrong account")
        val nonCanonical = DraftSnapshot(
            DirectConversationKey(ACCOUNT, "$PEER/resource"),
            "noncanonical",
            composerRevision = 1,
        )

        assertTrue(!presenter.updateDraft(wrongAccount).await())
        assertTrue(!presenter.sendDraft(wrongAccount).await())
        assertTrue(!presenter.updateDraft(nonCanonical).await())
        assertTrue(!presenter.sendDraft(nonCanonical).await())

        assertEquals("", repository.observeDraft(ACCOUNT, PEER).first())
        assertEquals("", repository.observeDraft(OTHER_ACCOUNT, PEER).first())
        assertEquals(0, enqueueCalls)
    }

    @Test
    fun presenterCloseCancelsItsRoomCollectionScope() {
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ -> true },
        )
        val parentJob = requireNotNull(scope.coroutineContext[Job])
        assertTrue(parentJob.children.any { it.isActive })

        presenter.close()

        assertTrue(parentJob.children.none { it.isActive })
    }

    private suspend fun addAccount(id: String, bareJid: String) {
        database.accountDao().upsert(
            AccountEntity(id, bareJid, id, null, "example.org", null, null),
        )
    }

    private fun accountConfiguration(id: String, bareJid: String) = AccountConfiguration.create(
        id = AccountId.require(id),
        bareJid = bareJid,
        authenticationId = id,
        authorizationId = null,
        serviceDomain = "example.org",
        networkEndpoint = null,
    )

    private fun key(accountId: String, peerJid: String) = DirectConversationKey(accountId, peerJid)

    private fun snapshot(
        accountId: String,
        peerJid: String,
        body: String,
        revision: Long = 1,
    ) = DraftSnapshot(key(accountId, peerJid), body, revision)

    private fun incoming(
        accountId: String,
        localId: String,
        body: String,
        threadId: String? = null,
        parentThreadId: String? = null,
    ) = IncomingMessage(
        accountId = accountId,
        localMessageId = localId,
        peerJid = PEER,
        senderJid = PEER,
        direction = MessageDirection.INBOUND,
        messageKind = MessageKind.CHAT,
        threadId = threadId,
        parentThreadId = parentThreadId,
        body = body,
        archiveOrdinal = null,
        aliases = emptyList(),
    )

    private fun outbound(suffix: String) = OutboundIntent(
        accountId = ACCOUNT,
        operationId = "operation-$suffix",
        localMessageId = "local-$suffix",
        originId = "origin-$suffix",
        peerJid = PEER,
        senderJid = SELF,
        messageKind = MessageKind.CHAT,
        threadId = null,
        parentThreadId = null,
        body = "outbound body",
    )

    companion object {
        private const val ACCOUNT = "account"
        private const val OTHER_ACCOUNT = "other-account"
        private const val SELF = "account@example.org"
        private const val OTHER_SELF = "other@example.org"
        private const val PEER = "peer@example.org"
        private const val OTHER_PEER = "other-peer@example.org"
    }
}
