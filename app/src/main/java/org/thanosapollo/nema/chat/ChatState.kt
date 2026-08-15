package org.thanosapollo.nema.chat

import java.util.PriorityQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jxmpp.jid.impl.JidCreate
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.storage.ChatNavigationEntity
import org.thanosapollo.nema.storage.ConversationListRow
import org.thanosapollo.nema.storage.NemaDatabase
import org.thanosapollo.nema.storage.MessageDirection
import org.thanosapollo.nema.storage.MessageDraftEntity
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.storage.PeerEntity
import org.thanosapollo.nema.storage.RetryUncertainKey
import org.thanosapollo.nema.storage.TimelineRow
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
        result = 31 * result + (photoBytes?.contentHashCode() ?: 0)
        return result
    }
}

enum class DeliveryPresentation {
    QUEUED,
    SENDING,
    SENT,
    CONFIRMED,
    UNCERTAIN,
    FAILED,
}

data class MessageReplyPresentation(
    val senderLabel: String,
    val body: String,
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
    val replyToId: String? = null,
    val replyToJid: String? = null,
    val replyFallbackBody: String? = null,
    val reply: MessageReplyPresentation? = null,
    val sentAtEpochMs: Long? = null,
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
) {
    init {
        require(key.thread == null || outboundThread == null) {
            "Draft cannot target both an open thread and a new thread"
        }
    }
}

class ChatRepository(database: NemaDatabase) {
    private val dao = database.messageDao()

    fun observeConversations(accountId: String): Flow<List<ConversationSummary>> =
        combine(
            dao.observeConversationSummaries(accountId),
            dao.observeRooms(accountId),
        ) { rows, rooms ->
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
            summaries + emptyRooms
        }

    fun observeTimeline(accountId: String, peerJid: String): Flow<List<TimelineMessage>> =
        observeTimeline(DirectConversationKey(accountId, peerJid))

    fun observeTimeline(key: DirectConversationKey): Flow<List<TimelineMessage>> =
        dao.observeDirectTimeline(key.accountId, key.canonicalBarePeer).map { rows ->
            val visibleRows = rows
                .filter { row ->
                    key.thread == null ||
                        (row.threadId == key.thread.id.value &&
                            row.parentThreadId == key.thread.parentId?.value)
                }
            val visible = chronologicalTimelineRows(visibleRows)
                .map(TimelineRow::toPresentation)
            visible.map { it.withReplyPresentation(visible) }
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
    private data class SelectedConversation(
        val route: ChatRoute?,
        val messages: List<TimelineMessage>,
        val draft: StoredDraft,
        val peer: PeerEntity?,
        val room: RoomView?,
    )

    private val actionLock = Any()
    private val presenterJob = SupervisorJob(scope.coroutineContext[Job])
    private val presenterScope = CoroutineScope(scope.coroutineContext + presenterJob)
    private var actionTail: Job? = null
    private val routeReady = CompletableDeferred<Unit>()
    private val selectedRoute = flow {
        if (!restoreRouteOnStart) repository.saveRoute(account.id.value, null)
        routeReady.complete(Unit)
        emitAll(repository.observeRoute(account.id.value))
    }.stateIn(
        presenterScope,
        SharingStarted.Eagerly,
        null,
    )
    private val selectedConversation = selectedRoute.flatMapLatest { route ->
        if (route == null) {
            flowOf(SelectedConversation(null, emptyList(), StoredDraft(), null, null))
        } else {
            val key = DirectConversationKey(account.id.value, route.peerJid, route.thread)
            combine(
                repository.observeTimeline(key),
                repository.observeStoredDraft(key),
                repository.observePeer(account.id.value, route.peerJid),
                observeRoom(route.peerJid),
            ) { messages, draft, peer, room ->
                SelectedConversation(route, messages, draft, peer, room)
            }
        }
    }

    val state = combine(
        repository.observeConversations(account.id.value),
        selectedConversation,
    ) { conversations, selected ->
        DirectChatState(
            accountId = account.id.value,
            conversations = conversations,
            conversationsReady = true,
            selectedPeer = selected.route?.peerJid,
            selectedPeerDisplayName = selected.peer?.displayName,
            selectedPeerLocalNickname = selected.peer?.localNickname,
            selectedPeerPhotoBytes = selected.peer?.photoBytes,
            selectedPeerPhotoMime = selected.peer?.photoMime,
            selectedPeerGroupChat = selected.peer?.room == true ||
                selected.messages.any { it.groupChat },
            selectedRoomSubject = selected.room?.subject,
            selectedRoomOccupantCount = selected.room?.occupantCount ?: 0,
            selectedThread = selected.route?.thread,
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
            state.collect { snapshot ->
                val peers = buildSet {
                    snapshot.conversations.forEach { add(it.peerJid) }
                    snapshot.selectedPeer?.let(::add)
                }
                if (peers.isNotEmpty()) {
                    try {
                        ensurePeerIdentities(account.id, peers)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Soft failure: keep collecting for later state/connection.
                    }
                }
            }
        }
    }

    suspend fun selectPeer(value: String): Boolean {
        val canonical = canonicalDirectPeer(value) ?: return false
        selectRoute(ChatRoute(canonical))
        ensurePeerIdentities(account.id, listOf(canonical))
        return true
    }

    suspend fun joinRoom(value: String): Boolean {
        val canonical = canonicalDirectPeer(value) ?: return false
        repository.markRoom(account.id.value, canonical)
        selectRoute(ChatRoute(canonical))
        joinMuc(canonical)
        return true
    }

    fun closeConversation() {
        submitAction {
            selectRoute(null)
            true
        }
    }

    suspend fun startNewThread(): Boolean {
        val route = selectedRoute.value ?: return false
        selectRoute(route.copy(thread = threadingPolicy.newTopic()))
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
        val thread = message.thread?.let(threadingPolicy::childOf) ?: threadingPolicy.newTopic()
        val nextRoute = route.copy(thread = thread)
        val reply = DraftReply(
            id = reference,
            to = message.senderJid,
            body = message.body,
            senderLabel = message.senderJid.replySenderLabel(),
        )
        if (selectedRoute.value != route) return false
        if (!repository.openThreadReply(account.id.value, route, nextRoute, message, reply)) return false
        return withTimeoutOrNull(5_000) {
            selectedRoute.first { it != route }
        } == nextRoute
    }

    fun closeThread() {
        submitAction {
            val route = selectedRoute.value ?: return@submitAction false
            selectRoute(route.copy(thread = null))
            true
        }
    }

    fun close() {
        presenterJob.cancel()
    }

    fun updateDraft(snapshot: DraftSnapshot): Deferred<Boolean> = submitAction {
        if (!owns(snapshot.key)) {
            false
        } else {
            repository.saveDraft(snapshot.key, snapshot.body, snapshot.reply)
            true
        }
    }

    fun sendDraft(snapshot: DraftSnapshot): Deferred<Boolean> = submitAction {
        owns(snapshot.key) &&
            snapshot.outboundThread == null &&
            snapshot.body.isNotBlank() &&
            enqueue(account, snapshot)
    }

    fun sendDraftAsNewThread(snapshot: DraftSnapshot): Deferred<Boolean> = submitAction {
        owns(snapshot.key) &&
            snapshot.key.thread == null &&
            snapshot.outboundThread == null &&
            snapshot.body.isNotBlank() &&
            enqueue(account, snapshot.copy(outboundThread = threadingPolicy.newTopic()))
    }

    suspend fun retryUncertain(key: RetryUncertainKey) {
        if (key.accountId == account.id.value) retry(account, key)
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

    private suspend fun selectRoute(route: ChatRoute?) {
        routeReady.await()
        repository.saveRoute(account.id.value, route)
        selectedRoute.first { it == route }
    }

    private fun owns(key: DirectConversationKey): Boolean =
        key.accountId == account.id.value &&
            canonicalDirectPeer(key.canonicalBarePeer) == key.canonicalBarePeer
}

internal fun canonicalDirectPeer(value: String): String? = runCatching {
    val bare = JidCreate.from(value.trim()).asBareJid()
    require(bare.isEntityBareJid) { "Direct peer must be an entity JID" }
    bare.asEntityBareJidOrThrow().toString()
}.getOrNull()

private fun TimelineRow.toPresentation() = TimelineMessage(
    id = localMessageId,
    senderJid = senderJid,
    body = body,
    outgoing = direction == MessageDirection.OUTBOUND,
    delivery = if (direction == MessageDirection.OUTBOUND) {
        outboxStatus?.let(OutboxStatus::valueOf).toPresentation()
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
    replyToId = replyToId,
    replyToJid = replyToJid,
    replyFallbackBody = replyFallbackBody,
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

private fun TimelineMessage.withReplyPresentation(timeline: List<TimelineMessage>): TimelineMessage {
    val reference = replyToId ?: return this
    val candidates = timeline.filter { it.replyReferenceId == reference }
    val target = if (replyToJid == null) {
        candidates.singleOrNull()
    } else {
        candidates.filter { it.matchesReplyAuthor(requireNotNull(replyToJid)) }.singleOrNull()
    }
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
