package org.thanosapollo.nema.chat

import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jxmpp.jid.impl.JidCreate
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.storage.ChatNavigationEntity
import org.thanosapollo.nema.storage.ConversationListRow
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageDraftEntity
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.MessageThreadTitleEntity
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.storage.PeerEntity
import org.thanosapollo.nema.storage.RetryUncertainKey
import org.thanosapollo.nema.storage.TimelineRow
import org.thanosapollo.nema.storage.TrustedIdentityAliasEntity
import org.thanosapollo.nema.thread.MessageKind
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.ThreadingPolicy
import org.thanosapollo.nema.thread.draftKey
import org.thanosapollo.nema.xmpp.transport.AccountId
import org.thanosapollo.nema.xmpp.vcard.peerDisplayLabel
import org.thanosapollo.nema.xmpp.muc.RoomView

data class ConversationSummary(
    val peerJid: String,
    val preview: String,
    val localSequence: Long,
    val displayName: String? = null,
    val localNickname: String? = null,
    val photoBytes: ByteArray? = null,
    val photoMime: String? = null,
    val groupChat: Boolean = false,
    val sentAtEpochMs: Long? = null,
    val unreadCount: Int = 0,
    val previewSender: String? = null,
) {
    val displayLabel: String
        get() = peerDisplayLabel(peerJid, displayName, localNickname)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConversationSummary) return false
        return peerJid == other.peerJid &&
            preview == other.preview &&
            localSequence == other.localSequence &&
            displayName == other.displayName &&
            localNickname == other.localNickname &&
            photoMime == other.photoMime &&
            groupChat == other.groupChat &&
            sentAtEpochMs == other.sentAtEpochMs &&
            unreadCount == other.unreadCount &&
            previewSender == other.previewSender &&
            photoBytes.contentEquals(other.photoBytes)
    }

    override fun hashCode(): Int {
        var result = peerJid.hashCode()
        result = 31 * result + preview.hashCode()
        result = 31 * result + localSequence.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (localNickname?.hashCode() ?: 0)
        result = 31 * result + (photoMime?.hashCode() ?: 0)
        result = 31 * result + groupChat.hashCode()
        result = 31 * result + (sentAtEpochMs?.hashCode() ?: 0)
        result = 31 * result + unreadCount
        result = 31 * result + (previewSender?.hashCode() ?: 0)
        result = 31 * result + (photoBytes?.contentHashCode() ?: 0)
        return result
    }
}

enum class DeliveryPresentation {
    QUEUED,
    SENDING,
    SENT,
    CONFIRMED,
    DELIVERED,
    READ,
    UNCERTAIN,
    FAILED,
}

data class MessageReplyPresentation(
    val senderLabel: String,
    val body: String,
)

data class ThreadSummary(
    val thread: ThreadRef,
    val replyCount: Int,
    val latestMessageId: String,
    val latestPreview: String,
)

fun threadSummaryLabel(replyCount: Int): String = "Thread · $replyCount replies"

fun previewSenderLabel(
    groupChat: Boolean,
    outgoing: Boolean,
    senderJid: String,
    peerJid: String,
): String? {
    if (outgoing) return "You"
    if (!groupChat) return null
    val nick = senderJid.substringAfterLast('/', missingDelimiterValue = "")
    return nick.takeIf(String::isNotEmpty)
}

data class RecentThread(
    val thread: ThreadRef,
    val title: String,
    val replyCount: Int,
    val messageKind: MessageKind,
)

data class DraftReply(
    val id: String,
    val to: String?,
    val body: String,
    val senderLabel: String,
) {
    init {
        require(id.isNotEmpty()) { "Reply ID must not be empty" }
        require(to == null || to.isNotEmpty()) { "Reply JID must not be empty" }
    }
}

data class StoredDraft(
    val body: String = "",
    val reply: DraftReply? = null,
)

data class TimelineMessage(
    val id: String,
    val senderJid: String,
    val body: String,
    val outgoing: Boolean,
    val delivery: DeliveryPresentation?,
    val retryUncertainKey: RetryUncertainKey?,
    val thread: ThreadRef?,
    val groupChat: Boolean = false,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val replyReferenceId: String? = null,
    val replyReferenceIds: Set<String> = emptySet(),
    val replyToId: String? = null,
    val replyToJid: String? = null,
    val replyFallbackBody: String? = null,
    val reply: MessageReplyPresentation? = null,
    val markable: Boolean = false,
    val markerTargetId: String? = null,
    val edited: Boolean = false,
    val correctionReferenceId: String? = null,
    val sentAtEpochMs: Long? = null,
    val threadSummaries: List<ThreadSummary> = emptyList(),
)

data class DirectChatState(
    val accountId: String,
    val conversations: List<ConversationSummary> = emptyList(),
    val conversationsReady: Boolean = false,
    val selectedPeer: String? = null,
    val selectedPeerDisplayName: String? = null,
    val selectedPeerLocalNickname: String? = null,
    val selectedPeerPhotoBytes: ByteArray? = null,
    val selectedPeerPhotoMime: String? = null,
    val selectedPeerGroupChat: Boolean = false,
    val selectedRoomSubject: String? = null,
    val selectedRoomOccupantCount: Int = 0,
    val selectedThread: ThreadRef? = null,
    val currentSession: ThreadRef? = null,
    val recentThreads: List<RecentThread> = emptyList(),
    val messages: List<TimelineMessage> = emptyList(),
    val draft: String = "",
    val draftReply: DraftReply? = null,
) {
    val selectedPeerLabel: String?
        get() = selectedPeer?.let { peerDisplayLabel(it, selectedPeerDisplayName, selectedPeerLocalNickname) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DirectChatState) return false
        return accountId == other.accountId &&
            conversations == other.conversations &&
            conversationsReady == other.conversationsReady &&
            selectedPeer == other.selectedPeer &&
            selectedPeerDisplayName == other.selectedPeerDisplayName &&
            selectedPeerLocalNickname == other.selectedPeerLocalNickname &&
            selectedPeerPhotoMime == other.selectedPeerPhotoMime &&
            selectedPeerGroupChat == other.selectedPeerGroupChat &&
            selectedRoomSubject == other.selectedRoomSubject &&
            selectedRoomOccupantCount == other.selectedRoomOccupantCount &&
            selectedThread == other.selectedThread &&
            currentSession == other.currentSession &&
            recentThreads == other.recentThreads &&
            messages === other.messages &&
            draft == other.draft &&
            draftReply == other.draftReply &&
            selectedPeerPhotoBytes.contentEquals(other.selectedPeerPhotoBytes)
    }

    override fun hashCode(): Int {
        var result = accountId.hashCode()
        result = 31 * result + conversations.hashCode()
        result = 31 * result + conversationsReady.hashCode()
        result = 31 * result + (selectedPeer?.hashCode() ?: 0)
        result = 31 * result + (selectedPeerDisplayName?.hashCode() ?: 0)
        result = 31 * result + (selectedPeerLocalNickname?.hashCode() ?: 0)
        result = 31 * result + (selectedPeerPhotoMime?.hashCode() ?: 0)
        result = 31 * result + selectedPeerGroupChat.hashCode()
        result = 31 * result + (selectedRoomSubject?.hashCode() ?: 0)
        result = 31 * result + selectedRoomOccupantCount
        result = 31 * result + (selectedPeerPhotoBytes?.contentHashCode() ?: 0)
        result = 31 * result + (selectedThread?.hashCode() ?: 0)
        result = 31 * result + (currentSession?.hashCode() ?: 0)
        result = 31 * result + recentThreads.hashCode()
        result = 31 * result + System.identityHashCode(messages)
        result = 31 * result + draft.hashCode()
        result = 31 * result + (draftReply?.hashCode() ?: 0)
        return result
    }
}

data class DirectConversationKey(
    val accountId: String,
    val canonicalBarePeer: String,
    val thread: ThreadRef? = null,
)

data class ChatRoute(
    val peerJid: String,
    val thread: ThreadRef? = null,
)

data class DraftCorrection(
    val localMessageId: String,
    val referenceId: String,
    val originalBody: String,
) {
    init {
        require(localMessageId.isNotEmpty()) { "Correction local target must not be empty" }
        require(referenceId.isNotEmpty()) { "Correction wire target must not be empty" }
        require(originalBody.isNotEmpty()) { "Correction original body must not be empty" }
    }
}

data class DraftSnapshot(
    val key: DirectConversationKey,
    val body: String,
    val composerRevision: Long,
    val outboundThread: ThreadRef? = null,
    val groupChat: Boolean = false,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val reply: DraftReply? = null,
    val correction: DraftCorrection? = null,
) {
    init {
        require(key.thread == null || outboundThread == null) {
            "Draft cannot target both an open thread and a new thread"
        }
        require(correction == null || (!groupChat && attachmentUrl == null && reply == null)) {
            "Corrections must be direct text without attachments or replies"
        }
        require(correction == null || body != correction.originalBody) {
            "Correction must change the message body"
        }
    }
}

class ChatRepository(database: NemaDatabase) {
    private val dao = database.messageDao()
    private val messages = MessageStore(database)

    fun observeConversations(accountId: String): Flow<List<ConversationSummary>> =
        combine(
            dao.observeCachedConversationSummaries(accountId),
            dao.observeRooms(accountId),
        ) { rows, rooms ->
            conversationSummaries(rows, rooms)
        }

    suspend fun cachedConversations(accountId: String): List<ConversationSummary> =
        conversationSummaries(
            dao.cachedConversationSummaries(accountId),
            dao.rooms(accountId),
        )

    suspend fun markConversationRead(accountId: String, peerJid: String): Boolean {
        dao.insertPeer(PeerEntity(accountId, peerJid))
        return dao.updatePeerLastRead(accountId, peerJid) == 1
    }

    private fun conversationSummaries(
        rows: List<ConversationListRow>,
        rooms: List<PeerEntity>,
    ): List<ConversationSummary> {
        val latestRows = rows.sortedWith(
            compareByDescending<ConversationListRow> {
                it.sentAtEpochMs ?: Long.MIN_VALUE
            }.thenByDescending { it.localSequence }.thenBy { it.peerJid },
        )
        val present = latestRows.map { it.peerJid }.toSet()
        val summaries = latestRows.map { row ->
            ConversationSummary(
                peerJid = row.peerJid,
                preview = row.preview,
                localSequence = row.localSequence,
                displayName = row.displayName,
                localNickname = row.localNickname,
                photoBytes = row.photoBytes,
                photoMime = row.photoMime,
                groupChat = row.groupChat,
                sentAtEpochMs = row.sentAtEpochMs,
                unreadCount = row.unreadCount,
                previewSender = previewSenderLabel(
                    groupChat = row.groupChat,
                    outgoing = row.direction == MessageDirection.OUTBOUND,
                    senderJid = row.senderJid,
                    peerJid = row.peerJid,
                ),
            )
        }
        val emptyRooms = rooms
            .filter { it.jid !in present }
            .map { room ->
                ConversationSummary(
                    peerJid = room.jid,
                    preview = "",
                    localSequence = 0,
                    displayName = room.displayName,
                    localNickname = room.localNickname,
                    photoBytes = room.photoBytes,
                    photoMime = room.photoMime,
                    groupChat = true,
                )
            }
        return summaries + emptyRooms
    }

    fun observeTimeline(accountId: String, peerJid: String): Flow<List<TimelineMessage>> =
        observeTimeline(DirectConversationKey(accountId, peerJid))

    fun observeTimeline(key: DirectConversationKey): Flow<List<TimelineMessage>> = combine(
        dao.observeDirectTimeline(key.accountId, key.canonicalBarePeer),
        dao.observeDirectReplyAliases(key.accountId, key.canonicalBarePeer),
        dao.observePeer(key.accountId, key.canonicalBarePeer),
    ) { rows, aliases, peer ->
        presentTimeline(rows, aliases, key, peer)
    }.flowOn(Dispatchers.Default)

    suspend fun cachedTimeline(key: DirectConversationKey): List<TimelineMessage> = presentTimeline(
        rows = dao.cachedDirectTimeline(key.accountId, key.canonicalBarePeer),
        aliases = emptyList(),
        key = key,
        peer = dao.peer(key.accountId, key.canonicalBarePeer),
    )

    private fun presentTimeline(
        rows: List<TimelineRow>,
        aliases: List<TrustedIdentityAliasEntity>,
        key: DirectConversationKey,
        peer: PeerEntity?,
    ): List<TimelineMessage> {
        val aliasesByMessage = aliases
            .filter { it.messageId != null }
            .groupBy(TrustedIdentityAliasEntity::messageId, TrustedIdentityAliasEntity::value)
            .mapValues { it.value.toSet() }
        val timeline = chronologicalTimelineRows(rows)
            .map { row -> row.toPresentation(aliasesByMessage[row.localMessageId].orEmpty()) }
            .let { messages -> messages.map { it.withReplyPresentation(messages) } }
        return if (peer?.room == true || timeline.any(TimelineMessage::groupChat)) {
            timeline.filterByThread(key.thread)
        } else {
            timeline.projectThreads(key.thread)
        }
    }

    fun observeCurrentSession(accountId: String, peerJid: String): Flow<ThreadRef?> =
        dao.observeDirectThreadSession(accountId, peerJid).map { session ->
            session?.let { ThreadRef(ThreadId.require(it.threadId)) }
        }

    suspend fun ensureCurrentSession(accountId: String, peerJid: String): ThreadRef =
        messages.ensureDirectThreadSession(accountId, peerJid)

    fun observeRecentThreads(accountId: String, peerJid: String): Flow<List<RecentThread>> = combine(
        dao.observeDirectTimeline(accountId, peerJid),
        dao.observeDirectReplyAliases(accountId, peerJid),
        dao.observeThreadTitles(accountId, peerJid),
    ) { rows, aliases, titles ->
        val aliasesByMessage = aliases
            .filter { it.messageId != null }
            .groupBy(TrustedIdentityAliasEntity::messageId, TrustedIdentityAliasEntity::value)
            .mapValues { it.value.toSet() }
        chronologicalTimelineRows(rows)
            .map { row -> row.toPresentation(aliasesByMessage[row.localMessageId].orEmpty()) }
            .let { messages -> messages.map { it.withReplyPresentation(messages) } }
            .recentThreads(titles)
    }.flowOn(Dispatchers.Default)

    suspend fun renameThread(
        accountId: String,
        peerJid: String,
        messageKind: MessageKind,
        thread: ThreadRef,
        title: String,
    ): Boolean {
        val target = dao.thread(accountId, peerJid, messageKind, thread.id.value)
            ?.takeIf { it.parentThreadId == thread.parentId?.value }
            ?: return false
        val normalized = title.trim()
        if (normalized.isEmpty()) {
            dao.deleteThreadTitle(accountId, peerJid, target.messageKind, target.threadId)
        } else {
            dao.saveThreadTitle(
                MessageThreadTitleEntity(accountId, peerJid, target.messageKind, target.threadId, normalized),
            )
        }
        return true
    }

    fun observeDraft(accountId: String, peerJid: String): Flow<String> =
        observeDraft(DirectConversationKey(accountId, peerJid))

    fun observeDraft(key: DirectConversationKey): Flow<String> =
        observeStoredDraft(key).map { it.body }

    fun observeStoredDraft(key: DirectConversationKey): Flow<StoredDraft> =
        dao.observeDraft(
            key.accountId,
            key.canonicalBarePeer,
            key.thread.draftKey(),
        ).map { it?.toStoredDraft() ?: StoredDraft() }

    suspend fun saveDraft(accountId: String, peerJid: String, body: String) {
        saveDraft(DirectConversationKey(accountId, peerJid), body)
    }

    suspend fun saveDraft(key: DirectConversationKey, body: String, reply: DraftReply? = null) {
        dao.saveDraft(
            key.accountId,
            key.canonicalBarePeer,
            key.thread.draftKey(),
            body,
            reply?.id,
            reply?.to,
            reply?.body,
            reply?.senderLabel,
        )
    }

    fun observeRoute(accountId: String): Flow<ChatRoute?> =
        dao.observeNavigation(accountId).map { it?.toRoute() }

    suspend fun saveRoute(accountId: String, route: ChatRoute?) {
        if (route == null) {
            dao.clearNavigation(accountId)
        } else {
            dao.saveNavigation(route.toEntity(accountId))
        }
    }

    suspend fun openThreadReply(
        accountId: String,
        sourceRoute: ChatRoute,
        route: ChatRoute,
        target: TimelineMessage,
        reply: DraftReply,
    ): Boolean {
        val thread = requireNotNull(route.thread) { "Thread reply route requires a thread" }
        return dao.saveNavigationWithDraft(
            expectedNavigation = sourceRoute.toEntity(accountId),
            navigation = route.toEntity(accountId),
            targetMessageId = target.id,
            targetSenderJid = target.senderJid,
            targetBody = target.body,
            targetThreadId = target.thread?.id?.value,
            targetParentThreadId = target.thread?.parentId?.value,
            draft = MessageDraftEntity(
                accountId = accountId,
                peerJid = route.peerJid,
                messageKind = MessageKind.CHAT,
                threadKey = thread.draftKey(),
                body = "",
                replyToId = reply.id,
                replyToJid = reply.to,
                replyFallbackBody = reply.body,
                replyFallbackSender = reply.senderLabel,
            ),
        )
    }

    fun observePeer(accountId: String, peerJid: String): Flow<PeerEntity?> =
        dao.observePeer(accountId, peerJid)

    suspend fun markRoom(accountId: String, peerJid: String) {
        dao.savePeerRoom(accountId, peerJid, true)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DirectChatPresenter(
    private val account: AccountConfiguration,
    private val repository: ChatRepository,
    scope: CoroutineScope,
    private val enqueue: suspend (AccountConfiguration, DraftSnapshot) -> Boolean,
    private val retry: suspend (AccountConfiguration, RetryUncertainKey) -> Unit = { _, _ -> },
    private val ensurePeerIdentities: suspend (AccountId, Collection<String>) -> Unit = { _, _ -> },
    private val joinMuc: suspend (String) -> Boolean = { false },
    private val observeRoom: (String) -> Flow<RoomView?> = { flowOf(null) },
    private val threadingPolicy: ThreadingPolicy = ThreadingPolicy(),
    private val restoreRouteOnStart: Boolean = false,
) {
    private data class PendingDraft(
        var snapshot: DraftSnapshot,
        val waiters: MutableList<CompletableDeferred<Boolean>> = mutableListOf(),
    )

    private data class SelectedConversation(
        val route: ChatRoute?,
        val messages: List<TimelineMessage>,
        val draft: StoredDraft,
        val peer: PeerEntity?,
        val room: RoomView?,
        val currentSession: ThreadRef?,
        val recentThreads: List<RecentThread>,
    )

    private val actionLock = Any()
    private val presenterJob = SupervisorJob(scope.coroutineContext[Job])
    private val presenterScope = CoroutineScope(scope.coroutineContext + presenterJob)
    private var actionTail: Job? = null
    private val coalescedDrafts = linkedMapOf<DirectConversationKey, PendingDraft>()
    private var draftFlushScheduled = false
    private var ensuredPeerJids: Set<String> = emptySet()
    private val routeReady = CompletableDeferred<Unit>()
    private val routeGeneration = AtomicInteger(0)
    private val joinedRooms = mutableSetOf<String>()
    private val selectedRoute = MutableStateFlow<ChatRoute?>(null)
    private val selectedConversation = selectedRoute.flatMapLatest { route ->
        if (route == null) {
            flowOf(SelectedConversation(null, emptyList(), StoredDraft(), null, null, null, emptyList()))
        } else {
            val key = DirectConversationKey(account.id.value, route.peerJid, route.thread)
            flow {
                emit(
                    withContext(Dispatchers.Default) {
                        SelectedConversation(
                            route,
                            repository.cachedTimeline(key),
                            repository.observeStoredDraft(key).first(),
                            repository.observePeer(account.id.value, route.peerJid).first(),
                            null,
                            repository.observeCurrentSession(account.id.value, route.peerJid).first(),
                            emptyList(),
                        )
                    },
                )
                emitAll(
                    combine(
                        repository.observeTimeline(key),
                        repository.observeStoredDraft(key),
                        repository.observePeer(account.id.value, route.peerJid),
                        observeRoom(route.peerJid),
                        repository.observeCurrentSession(account.id.value, route.peerJid),
                    ) { messages, draft, peer, room, currentSession ->
                        SelectedConversation(route, messages, draft, peer, room, currentSession, emptyList())
                    }.combine(repository.observeRecentThreads(account.id.value, route.peerJid)) { selected, recent ->
                        selected.copy(recentThreads = recent)
                    },
                )
            }
        }
    }

    val state = combine(
        flow {
            emit(repository.cachedConversations(account.id.value))
            emitAll(repository.observeConversations(account.id.value))
        },
        selectedConversation,
    ) { conversations, selected ->
        val groupChat = selected.peer?.room == true || selected.messages.any { it.groupChat }
        val selectedKind = if (groupChat) MessageKind.GROUPCHAT else MessageKind.CHAT
        DirectChatState(
            accountId = account.id.value,
            conversations = conversations,
            conversationsReady = true,
            selectedPeer = selected.route?.peerJid,
            selectedPeerDisplayName = selected.peer?.displayName,
            selectedPeerLocalNickname = selected.peer?.localNickname,
            selectedPeerPhotoBytes = selected.peer?.photoBytes,
            selectedPeerPhotoMime = selected.peer?.photoMime,
            selectedPeerGroupChat = groupChat,
            selectedRoomSubject = selected.room?.subject,
            selectedRoomOccupantCount = selected.room?.occupantCount ?: 0,
            selectedThread = selected.route?.thread,
            currentSession = selected.currentSession.takeUnless { groupChat },
            recentThreads = selected.recentThreads.filter { it.messageKind == selectedKind },
            messages = selected.messages,
            draft = selected.draft.body,
            draftReply = selected.draft.reply,
        )
    }.stateIn(
        presenterScope,
        SharingStarted.Eagerly,
        DirectChatState(accountId = account.id.value),
    )

    init {
        presenterScope.launch {
            if (restoreRouteOnStart) {
                val restored = repository.observeRoute(account.id.value).first()
                if (routeGeneration.get() == 0) selectedRoute.value = restored
            } else if (routeGeneration.get() == 0) {
                repository.saveRoute(account.id.value, null)
            }
            routeReady.complete(Unit)
        }
        presenterScope.launch {
            state.collect { snapshot ->
                val peer = snapshot.selectedPeer ?: return@collect
                if (peer == ensuredPeerJids.singleOrNull()) return@collect
                ensuredPeerJids = setOf(peer)
                try {
                    withContext(Dispatchers.IO) {
                        ensurePeerIdentities(account.id, listOf(peer))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun selectPeer(value: String): Boolean {
        val canonical = canonicalDirectPeer(value) ?: return false
        selectRoute(ChatRoute(canonical))
        repository.markConversationRead(account.id.value, canonical)
        return true
    }

    suspend fun markVisibleConversationRead(): Boolean {
        val peer = selectedRoute.value?.peerJid ?: return false
        return repository.markConversationRead(account.id.value, peer)
    }

    suspend fun joinRoom(value: String): Boolean {
        val canonical = canonicalDirectPeer(value) ?: return false
        selectRoute(ChatRoute(canonical))
        repository.markConversationRead(account.id.value, canonical)
        presenterScope.launch {
            repository.markRoom(account.id.value, canonical)
            if (joinedRooms.add(canonical)) {
                joinMuc(canonical)
            }
        }
        return true
    }

    fun closeConversation() {
        selectRoute(null)
    }

    suspend fun startNewThread(): Boolean {
        val route = selectedRoute.value ?: return false
        val groupChat = repository.observePeer(account.id.value, route.peerJid).first()?.room == true ||
            repository.observeTimeline(account.id.value, route.peerJid).first().any { it.groupChat }
        val currentSession = if (groupChat) {
            null
        } else {
            repository.observeCurrentSession(account.id.value, route.peerJid).first()
                ?: repository.ensureCurrentSession(account.id.value, route.peerJid)
        }
        selectRoute(route.copy(thread = newTopic(currentSession)))
        return true
    }

    suspend fun continueThread(thread: ThreadRef): Boolean {
        val route = selectedRoute.value ?: return false
        selectRoute(route.copy(thread = threadingPolicy.replyTo(thread)))
        return true
    }

    suspend fun startChildThread(): Boolean {
        val route = selectedRoute.value ?: return false
        val parent = route.thread ?: return false
        return startChildThreadOf(parent)
    }

    suspend fun startChildThreadOf(parent: ThreadRef): Boolean {
        val route = selectedRoute.value ?: return false
        selectRoute(route.copy(thread = threadingPolicy.childOf(parent)))
        return true
    }

    suspend fun startThreadFrom(message: TimelineMessage): Boolean {
        val route = selectedRoute.value ?: return false
        val current = state.value
        if (current.selectedPeer != route.peerJid || current.selectedThread != route.thread) return false
        if (current.selectedPeerGroupChat) return false
        if (current.messages.singleOrNull { it.id == message.id } != message) return false
        val reference = message.replyReferenceId ?: return false
        val thread = message.thread?.let(threadingPolicy::childOf) ?: newTopic(
            current.currentSession ?: repository.ensureCurrentSession(account.id.value, route.peerJid),
        )
        val nextRoute = route.copy(thread = thread)
        val reply = DraftReply(
            id = reference,
            to = message.senderJid,
            body = message.body,
            senderLabel = message.senderJid.replySenderLabel(),
        )
        if (selectedRoute.value != route) return false
        if (!repository.openThreadReply(account.id.value, route, nextRoute, message, reply)) return false
        selectRoute(nextRoute)
        return true
    }

    fun closeThread() {
        val route = selectedRoute.value ?: return
        selectRoute(route.copy(thread = null))
    }

    fun close() {
        presenterJob.cancel()
    }

    fun updateDraft(snapshot: DraftSnapshot): Deferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        synchronized(actionLock) {
            val pending = coalescedDrafts.getOrPut(snapshot.key) { PendingDraft(snapshot) }
            pending.snapshot = snapshot
            pending.waiters += result
            if (!draftFlushScheduled) {
                draftFlushScheduled = true
                val predecessor = actionTail
                presenterScope.async(start = CoroutineStart.LAZY) {
                    try {
                        try {
                            predecessor?.join()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                        }
                        flushCoalescedDrafts()
                    } catch (cancelled: CancellationException) {
                        failPendingDrafts()
                        throw cancelled
                    } catch (_: Exception) {
                        failPendingDrafts()
                    }
                }.also {
                    actionTail = it
                    it.start()
                }
            }
        }
        return result
    }

    fun sendDraft(snapshot: DraftSnapshot): Deferred<Boolean> = submitAction {
        owns(snapshot.key) &&
            snapshot.outboundThread == null &&
            snapshot.body.isNotBlank() &&
            enqueue(account, snapshot)
    }

    fun sendDraftAsNewThread(snapshot: DraftSnapshot): Deferred<Boolean> = submitAction {
        if (!owns(snapshot.key) ||
            snapshot.key.thread != null ||
            snapshot.outboundThread != null ||
            snapshot.body.isBlank()
        ) {
            return@submitAction false
        }
        val currentSession = if (snapshot.groupChat) {
            null
        } else {
            repository.observeCurrentSession(
                snapshot.key.accountId,
                snapshot.key.canonicalBarePeer,
            ).first() ?: repository.ensureCurrentSession(
                snapshot.key.accountId,
                snapshot.key.canonicalBarePeer,
            )
        }
        val thread = newTopic(currentSession)
        val sent = enqueue(account, snapshot.copy(outboundThread = thread))
        if (sent) {
            selectRoute(ChatRoute(snapshot.key.canonicalBarePeer, thread))
        }
        sent
    }

    suspend fun renameThread(recent: RecentThread, title: String): Boolean {
        val route = selectedRoute.value ?: return false
        val current = state.value
        val stillPresent = current.recentThreads.any {
            it.messageKind == recent.messageKind && it.thread == recent.thread
        }
        if (current.selectedPeer != route.peerJid || !stillPresent) return false
        return repository.renameThread(
            account.id.value,
            route.peerJid,
            recent.messageKind,
            recent.thread,
            title,
        )
    }

    suspend fun retryUncertain(key: RetryUncertainKey) {
        if (key.accountId == account.id.value) retry(account, key)
    }

    private suspend fun flushCoalescedDrafts() {
        while (true) {
            val batch: List<PendingDraft>
            synchronized(actionLock) {
                if (coalescedDrafts.isEmpty()) {
                    draftFlushScheduled = false
                    return
                }
                batch = coalescedDrafts.values.map { pending ->
                    PendingDraft(pending.snapshot, pending.waiters.toMutableList())
                }
                coalescedDrafts.clear()
            }
            for (pending in batch) {
                val ok = try {
                    if (!owns(pending.snapshot.key)) {
                        false
                    } else {
                        repository.saveDraft(
                            pending.snapshot.key,
                            pending.snapshot.body,
                            pending.snapshot.reply,
                        )
                        true
                    }
                } catch (cancelled: CancellationException) {
                    pending.waiters.forEach { waiter -> waiter.complete(false) }
                    failPendingDrafts()
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                pending.waiters.forEach { waiter -> waiter.complete(ok) }
            }
        }
    }

    private fun failPendingDrafts() {
        val waiters = synchronized(actionLock) {
            val pending = coalescedDrafts.values.flatMap { it.waiters }
            coalescedDrafts.clear()
            draftFlushScheduled = false
            pending
        }
        waiters.forEach { waiter -> waiter.complete(false) }
    }

    private fun submitAction(action: suspend () -> Boolean): Deferred<Boolean> = synchronized(actionLock) {
        val predecessor = actionTail
        presenterScope.async(start = CoroutineStart.LAZY) {
            predecessor?.join()
            action()
        }.also {
            actionTail = it
            it.start()
        }
    }

    private fun selectRoute(route: ChatRoute?) {
        selectedRoute.value = route
        val generation = routeGeneration.incrementAndGet()
        presenterScope.launch { persistRoute(generation) }
    }

    private suspend fun persistRoute(generation: Int) {
        routeReady.await()
        if (generation != routeGeneration.get()) return
        repository.saveRoute(account.id.value, selectedRoute.value)
    }

    private fun owns(key: DirectConversationKey): Boolean =
        key.accountId == account.id.value &&
            canonicalDirectPeer(key.canonicalBarePeer) == key.canonicalBarePeer

    private fun newTopic(currentSession: ThreadRef?): ThreadRef =
        currentSession?.let(threadingPolicy::childOf) ?: threadingPolicy.newTopic()
}

internal fun canonicalDirectPeer(value: String): String? = runCatching {
    val bare = JidCreate.from(value.trim()).asBareJid()
    require(bare.isEntityBareJid) { "Direct peer must be an entity JID" }
    bare.asEntityBareJidOrThrow().toString()
}.getOrNull()

private fun TimelineRow.toPresentation(replyReferenceIds: Set<String>) = TimelineMessage(
    id = localMessageId,
    senderJid = senderJid,
    body = correctedBody ?: body,
    outgoing = direction == MessageDirection.OUTBOUND,
    delivery = if (direction == MessageDirection.OUTBOUND) {
        receiptStage?.let(org.thanosapollo.nema.xmpp.transport.MessageReceiptStage::valueOf)
            ?.toPresentation()
            ?: outboxStatus?.let(OutboxStatus::valueOf).toPresentation()
    } else {
        null
    },
    retryUncertainKey = if (outboxStatus == OutboxStatus.UNCERTAIN.name) {
        RetryUncertainKey(
            accountId = accountId,
            operationId = requireNotNull(operationId),
            generation = requireNotNull(outboxGeneration),
            attempt = requireNotNull(outboxAttempt),
        )
    } else {
        null
    },
    thread = threadId?.let {
        ThreadRef(
            id = ThreadId.require(it),
            parentId = parentThreadId?.let(ThreadId::require),
        )
    },
    groupChat = messageKind == MessageKind.GROUPCHAT,
    attachmentUrl = attachmentUrl,
    attachmentName = attachmentName,
    attachmentMime = attachmentMime,
    attachmentSize = attachmentSize,
    replyReferenceId = replyReferenceId,
    replyReferenceIds = replyReferenceIds,
    replyToId = replyToId,
    replyToJid = replyToJid,
    replyFallbackBody = replyFallbackBody,
    markable = markable,
    markerTargetId = markerTargetId,
    edited = edited,
    correctionReferenceId = operationId,
    sentAtEpochMs = sentAtEpochMs,
)

private fun <T> chronological(
    rows: List<T>,
    archiveOrdinal: (T) -> Long?,
    archiveSpine: (T) -> Pair<String, String>,
    sentAt: (T) -> Long?,
    sequence: (T) -> Long,
    id: (T) -> String,
): List<T> {
    val archived = rows.filter { archiveOrdinal(it) != null }
        .groupBy(archiveSpine)
        .values
        .map { spine ->
            ChronologyChain(
                spine.sortedWith(compareBy({ requireNotNull(archiveOrdinal(it)) }, sequence, id)),
                archived = true,
            )
        }
    val loose = rows.filter { archiveOrdinal(it) == null }
        .sortedWith(compareBy({ sentAt(it) ?: Long.MIN_VALUE }, sequence, id))
    val latestFirst = Comparator<ChronologyChain<T>> { left, right ->
        compareValues(sentAt(right.current) ?: Long.MIN_VALUE, sentAt(left.current) ?: Long.MIN_VALUE)
            .takeIf { it != 0 }
            ?: compareValues(right.archived, left.archived).takeIf { it != 0 }
            ?: compareValues(sequence(right.current), sequence(left.current)).takeIf { it != 0 }
            ?: compareValues(id(right.current), id(left.current))
    }
    val pending = PriorityQueue(latestFirst)
    pending.addAll(archived)
    if (loose.isNotEmpty()) pending += ChronologyChain(loose, archived = false)
    val reverse = ArrayList<T>(rows.size)
    while (pending.isNotEmpty()) {
        val chain = pending.remove()
        reverse += chain.current
        if (chain.retreat()) pending += chain
    }
    reverse.reverse()
    return reverse
}

private class ChronologyChain<T>(private val rows: List<T>, val archived: Boolean) {
    private var index = rows.lastIndex
    val current: T
        get() = rows[index]

    fun retreat(): Boolean = --index >= 0
}

private fun chronologicalTimelineRows(rows: List<TimelineRow>) = chronological(
    rows,
    archiveOrdinal = { it.conversationArchiveOrdinal },
    archiveSpine = { it.conversationArchiveAuthority to it.conversationArchiveScope },
    sentAt = { it.sentAtEpochMs },
    sequence = { it.localSequence },
    id = { it.localMessageId },
)

private fun MessageDraftEntity.toStoredDraft() = StoredDraft(
    body = body,
    reply = replyToId?.let {
        DraftReply(
            id = it,
            to = replyToJid,
            body = requireNotNull(replyFallbackBody),
            senderLabel = requireNotNull(replyFallbackSender),
        )
    },
)

private fun List<TimelineMessage>.filterByThread(thread: ThreadRef?): List<TimelineMessage> =
    if (thread == null) {
        this
    } else {
        filter { it.thread == thread }
    }

private data class ResolvedThread(
    val thread: ThreadRef,
    val root: TimelineMessage,
    val members: List<TimelineMessage>,
)

private fun List<TimelineMessage>.projectThreads(
    selected: ThreadRef?,
): List<TimelineMessage> {
    val resolved = asSequence()
        .filter { !it.groupChat }
        .mapNotNull(TimelineMessage::thread)
        .filter { it.parentId != null }
        .distinct()
        .mapNotNull(::resolveThread)
        .toList()
    val visible = if (selected == null) {
        val hidden = resolved.flatMap(ResolvedThread::members).mapTo(mutableSetOf(), TimelineMessage::id)
        filterNot { it.id in hidden }
    } else {
        val memberIds = filter { it.thread == selected }.mapTo(mutableSetOf(), TimelineMessage::id)
        resolved.firstOrNull { it.thread == selected }?.root?.id?.let(memberIds::add)
        filter { it.id in memberIds }
    }
    val summaries = resolved.groupBy { it.root.id }
    return visible.map { message ->
        val attached = summaries[message.id].orEmpty()
            .filter { it.thread != selected }
            .map { thread ->
                val latest = thread.members.lastOrNull() ?: thread.root
                ThreadSummary(
                    thread = thread.thread,
                    replyCount = thread.members.size,
                    latestMessageId = latest.id,
                    latestPreview = latest.body,
                )
            }
        message.copy(threadSummaries = attached)
    }
}

private fun List<TimelineMessage>.resolveThread(
    thread: ThreadRef,
): ResolvedThread? {
    val members = filter { it.thread == thread && !it.groupChat }
    if (members.isEmpty()) return null
    val memberIds = members.mapTo(hashSetOf(), TimelineMessage::id)
    val externalRoot = members.firstNotNullOfOrNull { member ->
        member.resolveReplyTarget(this)?.takeIf { candidate ->
            !candidate.groupChat &&
                candidate.id !in memberIds &&
                (candidate.thread?.id == thread.parentId ||
                    (candidate.thread == null && thread.parentId != null))
        }
    }
    val root = externalRoot ?: members.first()
    val replies = if (externalRoot == null) members.drop(1) else members
    return ResolvedThread(thread, root, replies)
}

private fun List<TimelineMessage>.recentThreads(
    titles: List<MessageThreadTitleEntity>,
): List<RecentThread> {
    val customTitles = titles.associateBy { it.messageKind to it.threadId }
    return withIndex()
        .filter { it.value.thread != null }
        .groupBy {
            val kind = if (it.value.groupChat) MessageKind.GROUPCHAT else MessageKind.CHAT
            kind to requireNotNull(it.value.thread)
        }
        .map { (key, indexed) ->
            val (kind, thread) = key
            val members = indexed.map { it.value }
            val resolved = if (kind == MessageKind.CHAT && thread.parentId != null) {
                resolveThread(thread)
            } else {
                null
            }
            val replies = resolved?.members?.size ?: (members.size - 1).coerceAtLeast(0)
            val defaultTitle = (resolved?.root ?: members.first()).body.threadTitlePreview()
            val title = customTitles[kind to thread.id.value]?.title ?: defaultTitle
            indexed.maxOf { it.index } to RecentThread(thread, title, replies, kind)
        }
        .groupBy { it.second.messageKind }
        .values
        .flatMap { kind -> kind.sortedByDescending { it.first }.take(10) }
        .sortedByDescending { it.first }
        .map { it.second }
}

private fun String.threadTitlePreview(): String {
    val normalized = trim()
    if (normalized.isEmpty()) return "Thread"
    val codePoints = normalized.codePointCount(0, normalized.length).coerceAtMost(20)
    return normalized.substring(0, normalized.offsetByCodePoints(0, codePoints))
}

private fun TimelineMessage.resolveReplyTarget(timeline: List<TimelineMessage>): TimelineMessage? {
    val reference = replyToId ?: return null
    val candidates = timeline.filter {
        it.replyReferenceId == reference || reference in it.replyReferenceIds
    }
    return if (replyToJid == null) {
        candidates.singleOrNull()
    } else {
        candidates.filter { it.matchesReplyAuthor(requireNotNull(replyToJid)) }.singleOrNull()
    }
}

private fun TimelineMessage.withReplyPresentation(timeline: List<TimelineMessage>): TimelineMessage {
    if (replyToId == null) return this
    val target = resolveReplyTarget(timeline)
    val fallback = replyFallbackBody?.toFallbackPreview()
    val previewBody = target?.body ?: fallback?.body ?: return this
    val sender = target?.senderJid?.replySenderLabel()
        ?: fallback?.senderLabel
        ?: replyToJid?.replySenderLabel()
        ?: "message"
    return copy(reply = MessageReplyPresentation(sender, previewBody))
}

private fun TimelineMessage.matchesReplyAuthor(expected: String): Boolean = if (groupChat) {
    senderJid == expected
} else {
    senderJid.substringBefore('/') == expected.substringBefore('/')
}

private data class FallbackReplyPreview(val senderLabel: String?, val body: String)

private fun String.toFallbackPreview(): FallbackReplyPreview? {
    val lines = lineSequence()
        .filter(String::isNotBlank)
        .map { it.removePrefix("> ").removePrefix(">") }
        .toList()
    if (lines.isEmpty()) return null
    val header = lines.first().takeIf { it.endsWith(" wrote:") }
    val body = (if (header == null) lines else lines.drop(1)).joinToString("\n").trim()
    if (body.isEmpty()) return null
    return FallbackReplyPreview(header?.removeSuffix(" wrote:"), body)
}

private fun String.replySenderLabel(): String =
    substringAfterLast('/').takeUnless { it == this } ?: substringBefore('@')

private fun ChatRoute.toEntity(accountId: String) = ChatNavigationEntity(
    accountId = accountId,
    peerJid = peerJid,
    threadId = thread?.id?.value,
    parentThreadId = thread?.parentId?.value,
)

private fun ChatNavigationEntity.toRoute() = ChatRoute(
    peerJid = peerJid,
    thread = threadId?.let {
        ThreadRef(
            id = ThreadId.require(it),
            parentId = parentThreadId?.let(ThreadId::require),
        )
    },
)

private fun OutboxStatus?.toPresentation(): DeliveryPresentation? = when (this) {
    OutboxStatus.PENDING -> DeliveryPresentation.QUEUED
    OutboxStatus.IN_FLIGHT -> DeliveryPresentation.SENDING
    OutboxStatus.ACKNOWLEDGED -> DeliveryPresentation.SENT
    OutboxStatus.CONFIRMED -> DeliveryPresentation.CONFIRMED
    OutboxStatus.UNCERTAIN -> DeliveryPresentation.UNCERTAIN
    OutboxStatus.FAILED -> DeliveryPresentation.FAILED
    null -> null
}

private fun org.thanosapollo.nema.xmpp.transport.MessageReceiptStage.toPresentation(): DeliveryPresentation =
    when (this) {
        org.thanosapollo.nema.xmpp.transport.MessageReceiptStage.RECEIVED -> DeliveryPresentation.DELIVERED
        org.thanosapollo.nema.xmpp.transport.MessageReceiptStage.DISPLAYED,
        org.thanosapollo.nema.xmpp.transport.MessageReceiptStage.ACKNOWLEDGED,
        -> DeliveryPresentation.READ
    }
