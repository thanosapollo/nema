package org.thanosapollo.nema.chat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        assertEquals(summary.preview, timeline.last().body)
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
    fun archivedTailWinsEqualTimeTieAgainstLooseMessage() = runBlocking {
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
        val timeline = repository.observeTimeline(ACCOUNT, PEER).first()

        assertEquals("archive tie winner", summary.preview)
        assertEquals(summary.preview, timeline.last().body)
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
        val requests = mutableListOf<Pair<AccountId, Set<String>>>()
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
    fun childWithoutReplyMetadataStaysVisibleInMainTimeline() = runBlocking {
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
        assertTrue(rootOnlyOverview.all { it.threadSummaries.isEmpty() })
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

        assertEquals(listOf("parent", "child-root", "child-reply"), overview.map(TimelineMessage::id))
        assertTrue(overview.all { it.threadSummaries.isEmpty() })
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
        val opened = presenter.state.first { it.selectedThread?.id?.value == "opened-child" }
        assertEquals("session", opened.selectedThread?.parentId?.value)
        presenter.closeThread()
        presenter.state.first { it.selectedThread == null }
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

        val opened = presenter.state.first { it.selectedThread?.id?.value == "first-child" }
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
        val room = presenter.state.first { it.selectedPeerGroupChat && it.recentThreads.isNotEmpty() }
        assertEquals(null, room.currentSession)
        assertEquals(listOf(MessageKind.GROUPCHAT), room.recentThreads.map(RecentThread::messageKind))

        assertTrue(presenter.startNewThread())
        val opened = presenter.state.first { it.selectedThread?.id?.value == "opened-room-topic" }
        assertEquals(null, opened.selectedThread?.parentId)
        presenter.closeThread()
        presenter.state.first { it.selectedThread == null }
        assertTrue(
            presenter.sendDraftAsNewThread(
                snapshot(ACCOUNT, PEER, "quick room topic").copy(groupChat = true),
            ).await(),
        )
        assertEquals(null, sent.single().outboundThread?.parentId)
        presenter.close()
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
        assertEquals(listOf("child-member"), focused.map(TimelineMessage::id))
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
        assertEquals(listOf("thread-first", "thread-later"), dedicated.map(TimelineMessage::id))
        assertTrue(dedicated.all { it.thread == thread })

        database.close()
        database = NemaDatabase.create(context, databaseName)
        val reopened = ChatRepository(database)
        assertEquals(listOf("root"), reopened.observeTimeline(ACCOUNT, PEER).first().map(TimelineMessage::id))
        assertEquals(
            listOf("thread-first", "thread-later"),
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
            listOf("thread-first", "thread-later"),
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
        assertEquals(listOf("thread-first", "thread-later"), dedicated.map(TimelineMessage::id))
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
        assertEquals(listOf("child-reply"), childView.map(TimelineMessage::id))
        assertTrue(childView.single().threadSummaries.isEmpty())
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
        val root = first.state.first { it.selectedThread?.id?.value == "thread-a" }.selectedThread!!
        first.updateDraft(
            DraftSnapshot(DirectConversationKey(ACCOUNT, PEER, root), "root draft", 1),
        ).await()
        assertTrue(first.startChildThread())
        val child = first.state.first { it.selectedThread?.id?.value == "thread-b" }.selectedThread!!
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
        val restoredState = restored.state.first { it.selectedThread?.id?.value == "thread-b" }
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

        val opened = first.state.first { it.selectedThread?.id?.value == "thread-reply" }
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
        val restoredState = restored.state.first { it.selectedThread?.id?.value == "thread-reply" }
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
            threadingPolicy = ThreadingPolicy { ThreadId.require("child-thread") },
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
        presenter.state.first { it.selectedPeer == OTHER_PEER }
        assertTrue(!presenter.startThreadFrom(threaded))
        assertEquals(OTHER_PEER, presenter.state.value.selectedPeer)
        assertTrue(presenter.selectPeer(PEER))
        val currentThreaded = presenter.state.first { state ->
            state.selectedPeer == PEER &&
                state.messages.any { it.id == "threaded-target" && it.replyReferenceId != null }
        }.messages.single { it.id == "threaded-target" }

        assertTrue(presenter.startThreadFrom(currentThreaded))

        val opened = presenter.state.first { it.selectedThread?.id?.value == "child-thread" }
        assertEquals("parent-thread", opened.selectedThread?.parentId?.value)
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
        first.close()
        var sent: DraftSnapshot? = null
        val restored = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = repository,
            scope = scope,
            enqueue = { _, snapshot -> sent = snapshot; true },
            restoreRouteOnStart = true,
        )

        val state = restored.state.first { it.selectedPeer == PEER && it.draft == "answer" }
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
        assertEquals(null, repository.observeRoute(ACCOUNT).first())
        cold.close()
    }

    @Test
    fun conversationsAreNotAuthoritativelyEmptyBeforeFirstQueryEmission() = runBlocking {
        val presenter = DirectChatPresenter(
            account = accountConfiguration(ACCOUNT, SELF),
            repository = ChatRepository(database),
            scope = scope,
            enqueue = { _, _ -> true },
        )

        assertEquals(false, presenter.state.value.conversationsReady)
        assertTrue(presenter.state.first { it.conversationsReady }.conversations.isEmpty())
        presenter.close()
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
        val newer = presenter.updateDraft(snapshot(ACCOUNT, PEER, "newer"))
        val other = presenter.updateDraft(snapshot(ACCOUNT, OTHER_PEER, "other"))
        assertTrue(!newer.isCompleted)
        assertTrue(!other.isCompleted)

        releaseSend.complete(Unit)
        assertTrue(send.await())
        assertTrue(newer.await())
        assertTrue(other.await())
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
        presenter.state.first { it.selectedPeer == PEER }
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
        assertEquals(PEER, presenter.state.first { it.selectedPeer == PEER }.selectedPeer)
        assertTrue(!releaseIdentities.isCompleted)
        releaseIdentities.complete(Unit)
        presenter.close()
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
            presenter.state.first { it.selectedPeer == "room@conference.example.org" }.selectedPeer,
        )
        assertTrue(!joined.isCompleted)
        joined.complete(Unit)
        presenter.close()
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
        presenter.state.first { it.selectedPeer == PEER }
        presenter.closeConversation()
        assertEquals(null, presenter.state.first { it.selectedPeer == null }.selectedPeer)
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
        presenter.state.first { it.selectedPeer == PEER }
        presenter.closeConversation()
        assertTrue(presenter.selectPeer(OTHER_PEER))
        assertEquals(OTHER_PEER, presenter.state.first { it.selectedPeer == OTHER_PEER }.selectedPeer)
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
        assertEquals(OTHER_PEER, presenter.state.first { it.selectedPeer == OTHER_PEER }.selectedPeer)
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

        val selected = presenter.state.first { it.selectedThread?.id?.value == "reply-thread" }.selectedThread
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
            presenter.state.first { it.selectedThread?.id?.value == "new-thread" }.selectedThread?.id?.value,
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
        val first = snapshot(ACCOUNT, PEER, "first", revision = 1)
        val newer = snapshot(ACCOUNT, PEER, "newer", revision = 2)
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
        assertEquals(OTHER_PEER, presenter.state.first { it.selectedPeer == OTHER_PEER }.selectedPeer)
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
