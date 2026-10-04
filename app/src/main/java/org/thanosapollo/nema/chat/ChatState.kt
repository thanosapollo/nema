package org.thanosapollo.nema.chat

import org.thanosapollo.nema.xmpp.omemo.protectedStatus

import org.thanosapollo.nema.xmpp.threads.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID

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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.filter

import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
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
import org.thanosapollo.nema.storage.MessageReactionEntity
import org.thanosapollo.nema.storage.MessageStore
import org.thanosapollo.nema.storage.MessageThreadTitleEntity
import org.thanosapollo.nema.storage.MessageThreadEntity
import org.thanosapollo.nema.storage.NamedThreadRow
import org.thanosapollo.nema.thread.threadNameError
import org.thanosapollo.nema.storage.OutboxStatus
import org.thanosapollo.nema.storage.PeerEntity
import org.thanosapollo.nema.storage.PeerIdentityStore
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
import org.thanosapollo.nema.xmpp.rtt.liveTypingLabel

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
    val protectedState: String = "NONE",
) {
    val displayLabel: String
        get() = peerDisplayLabel(peerJid, displayName, localNickname)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConversationSummary) return false
        return peerJid == other.peerJid &&
            preview == other.preview && protectedState == other.protectedState &&
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
        result = 31 * result + protectedState.hashCode()
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
    val locallyNamed: Boolean = false,
    val unreadCount: Int = 0,
    val shared: org.thanosapollo.nema.storage.NamedThreadRow? = null,

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
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
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
    val reactions: List<org.thanosapollo.nema.xmpp.reactions.ReactionDisplay> = emptyList(),
    val protectedState: String = "NONE",
)

enum class ChatContentStatus { Loading, Ready, Failed }

data class ChatRouteOccurrence(val route: ChatRoute?, val generation: Int)

class VisibleReadRequest(
    val accountId: String,
    val occurrence: ChatRouteOccurrence,
    messageIds: Set<String>,
    observedTimelineIds: List<String> = messageIds.toList(),
) {
    val messageIds: Set<String> = messageIds.toSet()
    // Chronological, route-projected snapshot at layout observation, not dispatch time.
    val observedTimelineIds: List<String> =
        if (observedTimelineIds is TimelineReadSnapshot) observedTimelineIds else observedTimelineIds.toList()
}

data class DirectChatState(
    val accountId: String,
    val conversations: List<ConversationSummary> = emptyList(),
    val conversationsReady: Boolean = false,
    val routeOccurrence: ChatRouteOccurrence = ChatRouteOccurrence(null, 0),
    val contentStatus: ChatContentStatus = ChatContentStatus.Ready,
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
    val mainUnreadCount: Int = 0,
    val messages: List<TimelineMessage> = emptyList(),
    val draft: String = "",
    val draftReply: DraftReply? = null,
    val draftAttachmentUrl: String? = null,
    val draftAttachmentName: String? = null,
    val draftAttachmentMime: String? = null,
    val draftAttachmentSize: Long? = null,
    val typingLabel: String? = null,
    val pendingSendIdentities: Set<PendingSendIdentity> = emptySet(),
    val completedSendSnapshots: Map<PendingSendIdentity, DraftSnapshot> = emptyMap(),
) {
    val selectedPeerLabel: String?
        get() = selectedPeer?.let { peerDisplayLabel(it, selectedPeerDisplayName, selectedPeerLocalNickname) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DirectChatState) return false
        return accountId == other.accountId &&
            conversations == other.conversations &&
            conversationsReady == other.conversationsReady &&
            routeOccurrence == other.routeOccurrence &&
            contentStatus == other.contentStatus &&
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
            mainUnreadCount == other.mainUnreadCount &&
            messages === other.messages &&
            draft == other.draft &&
            draftReply == other.draftReply &&
            draftAttachmentUrl == other.draftAttachmentUrl &&
            draftAttachmentName == other.draftAttachmentName &&
            draftAttachmentMime == other.draftAttachmentMime &&
            draftAttachmentSize == other.draftAttachmentSize &&
            typingLabel == other.typingLabel &&
            pendingSendIdentities == other.pendingSendIdentities &&
            completedSendSnapshots == other.completedSendSnapshots &&
            selectedPeerPhotoBytes.contentEquals(other.selectedPeerPhotoBytes)
    }

    override fun hashCode(): Int {
        var result = accountId.hashCode()
        result = 31 * result + conversations.hashCode()
        result = 31 * result + conversationsReady.hashCode()
        result = 31 * result + routeOccurrence.hashCode()
        result = 31 * result + contentStatus.hashCode()
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
        result = 31 * result + mainUnreadCount
        result = 31 * result + System.identityHashCode(messages)
        result = 31 * result + draft.hashCode()
        result = 31 * result + (draftReply?.hashCode() ?: 0)
        result = 31 * result + (draftAttachmentUrl?.hashCode() ?: 0)
        result = 31 * result + (draftAttachmentName?.hashCode() ?: 0)
        result = 31 * result + (draftAttachmentMime?.hashCode() ?: 0)
        result = 31 * result + (draftAttachmentSize?.hashCode() ?: 0)
        result = 31 * result + (typingLabel?.hashCode() ?: 0)
        result = 31 * result + pendingSendIdentities.hashCode()
        result = 31 * result + completedSendSnapshots.hashCode()
        return result
    }
}

data class DirectConversationKey(
    val accountId: String,
    val canonicalBarePeer: String,
    val thread: ThreadRef? = null,
)

data class PendingSendIdentity(
    val key: DirectConversationKey,
    val composerRevision: Long,
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

internal data class ConversationTimeline(
    val messages: List<TimelineMessage>,
    val mainUnreadCount: Int,
    val recentThreads: List<RecentThread>,
)

class ChatRepository(database: NemaDatabase) {
    private val dao = database.messageDao()
    private val messages = MessageStore(database)
    private val peers = PeerIdentityStore(dao)

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

    suspend fun markMessagesRead(accountId: String, peerJid: String, messageIds: List<String>) =
        messages.markMessagesRead(accountId, peerJid, messageIds)

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
                preview = protectedStatus(row.protectedState) ?: row.preview,
                protectedState = row.protectedState,
                localSequence = row.localSequence,
                displayName = row.displayName,
                localNickname = row.localNickname,
                photoBytes = row.photoBytes,
                photoMime = row.photoMime,
                groupChat = row.groupChat,
                sentAtEpochMs = row.sentAtEpochMs,
                unreadCount = row.unreadCount,
                previewSender = if (row.protectedState != "NONE") null else previewSenderLabel(
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

    fun observeTimeline(key: DirectConversationKey): Flow<List<TimelineMessage>> =
        observeConversationTimeline(key).map { it.messages }

    internal fun observeConversationTimeline(key: DirectConversationKey): Flow<ConversationTimeline> = combine(
        dao.observeDirectTimeline(key.accountId, key.canonicalBarePeer),
        dao.observeDirectReplyAliases(key.accountId, key.canonicalBarePeer),
        dao.observePeer(key.accountId, key.canonicalBarePeer).map { it?.toIdentityFacts() },
        dao.observeMessageReactions(key.accountId, key.canonicalBarePeer),
        dao.observeNamedThreads(key.accountId, key.canonicalBarePeer),
    ) { rows, aliases, peer, reactionRows, named ->
        presentTimeline(rows, aliases, key, peer, reactionRows, named)
    }.flowOn(Dispatchers.Default)

    private fun presentTimeline(
        rows: List<TimelineRow>,
        aliases: List<TrustedIdentityAliasEntity>,
        key: DirectConversationKey,
        peer: PeerIdentityFacts?,
        reactionRows: List<MessageReactionEntity> = emptyList(),
        named: List<NamedThreadRow> = emptyList(),
    ): ConversationTimeline {
        val aliasesByMessage = aliases
            .filter { it.messageId != null }
            .groupBy(TrustedIdentityAliasEntity::messageId, TrustedIdentityAliasEntity::value)
            .mapValues { it.value.toSet() }
        val reactionsByMessage = reactionRows
            .mapNotNull { row -> row.localMessageId?.takeIf { row.emojis.isNotEmpty() }?.let { it to row } }
            .groupBy({ it.first }, { it.second })
        val timeline = chronologicalTimelineRows(rows)
            .map { row -> row.toPresentation(aliasesByMessage[row.localMessageId].orEmpty()) }
            .let { messages ->
                val replies = ReplyIndex(messages)
                messages.map { it.withReplyPresentation(replies) }
            }
            .map { message ->
                val group = reactionsByMessage[message.id].orEmpty()
                message.copy(
                    reactions = org.thanosapollo.nema.xmpp.reactions.reactionDisplaysFor(
                        localMessageId = message.id,
                        senderState = group.map {
                            it.senderBareJid to org.thanosapollo.nema.xmpp.reactions.decodeReactionEmojis(it.emojis)
                        },
                        chosenSender = null,
                    ),
                )
            }
        val kind = if (peer?.room == true) MessageKind.GROUPCHAT else MessageKind.CHAT
        val threads = ThreadIndex(timeline)
        val namedIds = named.filter { it.messageKind == kind }.mapTo(hashSetOf()) { it.threadId }
        // Main activity uses exactly the same visible membership as Main read admission,
        // not a peer-wide total minus named counts (which would include hidden children).
        val main = (if (peer?.room == true) timeline else timeline.projectThreads(null, threads)).filterNot {
            (if (it.groupChat) MessageKind.GROUPCHAT else MessageKind.CHAT) == kind &&
                it.thread?.id?.value in namedIds
        }
        val mainIds = main.mapTo(hashSetOf(), TimelineMessage::id)
        val unread = rows.count {
            it.messageKind == kind && it.localMessageId in mainIds &&
                it.direction == MessageDirection.INBOUND && it.unreadEligible && !it.locallyRead
        }
        val selected = when {
            key.thread == null -> main
            peer?.room == true -> timeline.filterByThread(key.thread)
            // Naming keeps exact bodies, but must not remove links to existing child history.
            key.thread.id.value in namedIds -> timeline.projectThreads(key.thread, threads).filterByThread(key.thread)
            else -> timeline.projectThreads(key.thread, threads)
        }
        return ConversationTimeline(selected, unread, timeline.recentThreads(named, threads))
    }

    fun observeCurrentSession(accountId: String, peerJid: String): Flow<ThreadRef?> =
        combine(dao.observeDirectThreadSession(accountId, peerJid), dao.observeNamedThreads(accountId, peerJid)) { session, named ->
            session?.takeUnless { current -> named.any {
                it.messageKind == MessageKind.CHAT && it.threadId == current.threadId
            } }?.let { ThreadRef(ThreadId.require(it.threadId)) }
        }

    suspend fun ensureCurrentSession(accountId: String, peerJid: String): ThreadRef =
        messages.ensureDirectThreadSession(accountId, peerJid)

    fun observeRecentThreads(accountId: String, peerJid: String): Flow<List<RecentThread>> =
        observeConversationTimeline(DirectConversationKey(accountId, peerJid)).map { it.recentThreads }

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
        if (threadNameError(title) != null) return false
        dao.saveThreadTitle(
            MessageThreadTitleEntity(accountId, peerJid, target.messageKind, target.threadId, title.trim()),
        )
        return true
    }

    suspend fun createNamedThread(
        accountId: String,
        peerJid: String,
        messageKind: MessageKind,
        title: String,
    ): ThreadRef? {
        if (canonicalDirectPeer(peerJid) != peerJid || threadNameError(title) != null) return null
        val thread = ThreadingPolicy().newTopic()
        return thread.takeIf {
            dao.createNamedThread(
                MessageThreadEntity(accountId, peerJid, messageKind, thread.id.value, null), title.trim(),
            )
        }
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

    suspend fun saveDraft(
        key: DirectConversationKey,
        body: String,
        reply: DraftReply? = null,
        attachmentUrl: String? = null,
        attachmentName: String? = null,
        attachmentMime: String? = null,
        attachmentSize: Long? = null,
    ) {
        dao.saveDraft(
            key.accountId,
            key.canonicalBarePeer,
            key.thread.draftKey(),
            body,
            reply?.id,
            reply?.to,
            reply?.body,
            reply?.senderLabel,
            attachmentUrl,
            attachmentName,
            attachmentMime,
            attachmentSize,
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
        if (target.protectedState != "NONE") return false
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

    fun observePeer(accountId: String, peerJid: String): Flow<PeerIdentityFacts?> =
        dao.observePeer(accountId, peerJid).map { it?.toIdentityFacts() }

    suspend fun markRoom(accountId: String, peerJid: String) {
        peers.saveRoom(accountId, peerJid, true)
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
    private val loadOlder: suspend (AccountConfiguration, OlderHistoryScope, () -> Boolean) -> OlderHistoryStatus = { _, _, _ -> OlderHistoryStatus.Error },
    private val observeRoom: (String) -> Flow<RoomView?> = { flowOf(null) },
    private val observeTyping: (String) -> Flow<List<String>> = { flowOf(emptyList()) },
    private val observeRtt: (String) -> Flow<String?> = { flowOf(null) },
    private val notifyComposer: (String, Boolean) -> Unit = { _, _ -> },
    private val threadingPolicy: ThreadingPolicy = ThreadingPolicy(),
    private val restoreRouteOnStart: Boolean = false,
    private val directoryConnection: Flow<Any?> = flowOf(Unit),
    private val keepDirectory: suspend (AccountConfiguration, String, MessageKind, String) -> Boolean = { _, _, _, _ -> false },
    private val refreshDirectory: suspend (AccountConfiguration, String, MessageKind) -> DirectoryView = { _, _, _ -> DirectoryView() },
    private val changeDirectory: suspend (AccountConfiguration, String, MessageKind, DirectoryAction?) -> DirectoryChange = { _, _, _, _ -> DirectoryChange(DirectoryView()) },

) {
    private data class PendingDraft(
        var snapshot: DraftSnapshot,
        val waiters: MutableList<CompletableDeferred<Boolean>> = mutableListOf(),
    )

    private data class SelectedConversation(
        val occurrence: ChatRouteOccurrence,
        val messages: List<TimelineMessage>,
        val draft: StoredDraft,
        val peer: PeerIdentityFacts?,
        val room: RoomView?,
        val currentSession: ThreadRef?,
        val recentThreads: List<RecentThread>,
        val mainUnreadCount: Int = 0,
        val composers: List<String> = emptyList(),
        val rttText: String? = null,
        val status: ChatContentStatus = ChatContentStatus.Ready,
    ) {
        val route get() = occurrence.route
        fun peerLabelForTyping(groupChat: Boolean): String? {
            if (groupChat) return null
            val jid = route?.peerJid ?: return null
            return peerDisplayLabel(jid, peer?.remoteProfileName, peer?.localNickname)
        }
    }

    private val actionLock = Any()
    private val presenterJob = SupervisorJob(scope.coroutineContext[Job])
    private val presenterScope = CoroutineScope(scope.coroutineContext + presenterJob)
    private var actionTail: Job? = null
    private val coalescedDrafts = linkedMapOf<DirectConversationKey, PendingDraft>()
    private var draftFlushScheduled = false
    private var ensuredPeerJids: Set<String> = emptySet()
    private val routeReady = CompletableDeferred<Unit>()
    private val routeGeneration = AtomicInteger(0)
    private class RoomJoins(val connection: Any?) {
        val joined = mutableSetOf<String>()
        val inFlight = mutableSetOf<String>()
    }
    private val selectedRoute = MutableStateFlow(ChatRouteOccurrence(null, 0))
    private val olderHistory = MutableStateFlow(OlderHistoryState())
    val olderHistoryState: StateFlow<OlderHistoryState> get() = olderHistory
    private var olderConnection: Any? = null
    private var olderAttempt: Any? = null

    suspend fun loadOlderHistory(origin: ChatRouteOccurrence) {
        val attempt = Any()
        val target: OlderHistoryScope
        synchronized(selectedRoute) {
            val snapshot = state.value
            if (!presenterJob.isActive || selectedRoute.value != origin || snapshot.routeOccurrence != origin ||
                snapshot.contentStatus != ChatContentStatus.Ready || origin.route == null) return
            val previous = olderHistory.value
            if (previous.occurrence == origin && previous.status in setOf(OlderHistoryStatus.Pending, OlderHistoryStatus.Exhausted)) return
            target = if (snapshot.selectedPeerGroupChat) OlderHistoryScope.Room(origin.route.peerJid) else OlderHistoryScope.Personal
            olderAttempt = attempt
            olderHistory.value = OlderHistoryState(origin, OlderHistoryStatus.Pending)
        }
        val current = { synchronized(selectedRoute) {
            presenterJob.isActive && selectedRoute.value == origin && olderAttempt === attempt
        } }
        try {
            val result = loadOlder(account, target, current)
            synchronized(selectedRoute) {
                if (current()) olderHistory.value = OlderHistoryState(origin, result)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(selectedRoute) {
                if (current()) olderHistory.value = OlderHistoryState(origin, OlderHistoryStatus.Error)
            }
        } finally {
            synchronized(selectedRoute) {
                if (current() && olderHistory.value.status == OlderHistoryStatus.Pending)
                    olderHistory.value = OlderHistoryState(origin, OlderHistoryStatus.Error)
            }
        }
    }

    private val directory = MutableStateFlow(DirectoryView(DirectoryMode.CHECKING))
    val directoryState: StateFlow<DirectoryView> get() = directory

    suspend fun refreshNamedThreads(origin: ChatRouteOccurrence) {
        if (selectedRoute.value != origin || state.value.routeOccurrence != origin || state.value.contentStatus != ChatContentStatus.Ready) return
        val route = origin.route ?: return
        val kind = if (state.value.selectedPeerGroupChat) MessageKind.GROUPCHAT else MessageKind.CHAT
        val result = try { refreshDirectory(account, route.peerJid, kind) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DirectoryView(DirectoryMode.ERROR) }
        if (selectedRoute.value == origin) directory.value = result
    }

    suspend fun retryDirectoryChange(origin: ChatRouteOccurrence): Boolean = changeShared(origin, null)

    suspend fun keepCurrentDirectory(origin: ChatRouteOccurrence): Boolean {
        if (selectedRoute.value != origin || state.value.routeOccurrence != origin) return false
        val route = origin.route ?: return false
        val operation = directory.value.pendingOperation ?: return false
        val kind = if (state.value.selectedPeerGroupChat) MessageKind.GROUPCHAT else MessageKind.CHAT
        val result = try { keepDirectory(account, route.peerJid, kind, operation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        if (result) refreshNamedThreads(origin)
        return result
    }

    private suspend fun changeShared(origin: ChatRouteOccurrence, action: DirectoryAction?): Boolean {
        if (selectedRoute.value != origin || state.value.routeOccurrence != origin) return false
        val route = origin.route ?: return false
        val kind = if (state.value.selectedPeerGroupChat) MessageKind.GROUPCHAT else MessageKind.CHAT
        val result = try { changeDirectory(account, route.peerJid, kind, action) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { DirectoryChange(DirectoryView(DirectoryMode.UNCERTAIN)) }
        if (selectedRoute.value == origin) {
            directory.value = result.view
            if (result.confirmed) refreshNamedThreads(origin)
        }
        return result.confirmed
    }

    suspend fun archiveNamedThread(origin: ChatRouteOccurrence, recent: RecentThread): Boolean {
        val action = sharedAction(recent, directory.value.context ?: return false,
            archived = !(recent.shared?.archived ?: return false)) ?: return false
        return changeShared(origin, action)
    }

    private fun sharedAction(recent: RecentThread, context: DirectoryContext, title: String? = null, archived: Boolean? = null): DirectoryAction? {
        if (directory.value.context != context || !directory.value.writable || state.value.recentThreads.none {
            it.thread == recent.thread && it.messageKind == recent.messageKind
        }) return null
        val row = recent.shared ?: return null
        if (!row.canModify || row.retired || row.authority != context.authority ||
            row.incarnation.orEmpty() != (context.scope as? ThreadDirectoryScope.Muc)?.incarnation.orEmpty()) return null
        return DirectoryAction(context, UUID.fromString(recent.thread.id.value), revision = row.revision, title = title, archived = archived)
    }

    private val sendGuard = MutableStateFlow(SendGuard())
    private data class SendGuard(
        val pending: Set<PendingSendIdentity> = emptySet(),
        val completed: Map<PendingSendIdentity, DraftSnapshot> = emptyMap(),
    )
    private fun emptySelection(occurrence: ChatRouteOccurrence, status: ChatContentStatus) =
        SelectedConversation(occurrence, emptyList(), StoredDraft(), null, null, null, emptyList(), status = status)

    private data class LocalSubscription(val route: ChatRoute, val generation: Int)
    private val localSubscription = MutableStateFlow<LocalSubscription?>(null)

    private data class LocalConversation(
        val subscription: LocalSubscription,
        val messages: List<TimelineMessage> = emptyList(),
        val peer: PeerIdentityFacts? = null,
        val currentSession: ThreadRef? = null,
        val recentThreads: List<RecentThread> = emptyList(),
        val mainUnreadCount: Int = 0,
        val status: ChatContentStatus = ChatContentStatus.Loading,
    )

    // Keep exactly the last route's Room subscriptions live while Home is visible.
    // This is not a draft cache: each occurrence below must read its draft anew.
    // Peer/thread changes replace this read model; closing the account cancels it.
    private val localConversation = localSubscription.mapNotNull { it }
        .flatMapLatest { subscription ->
            val route = subscription.route
            flow {
                emit(LocalConversation(subscription))
                emitAll(combine(
                    repository.observeConversationTimeline(DirectConversationKey(account.id.value, route.peerJid, route.thread)),
                    repository.observePeer(account.id.value, route.peerJid),
                    repository.observeCurrentSession(account.id.value, route.peerJid),
                ) { timeline, peer, session ->
                    LocalConversation(subscription, timeline.messages, peer, session, timeline.recentThreads, timeline.mainUnreadCount, ChatContentStatus.Ready)
                })
            }.retryWhen { failure, _ ->
                if (failure is CancellationException) throw failure
                val failedAt = selectedRoute.value
                emit(LocalConversation(subscription, status = ChatContentStatus.Failed))
                // A deliberate new occurrence retries failed local queries, not a timer.
                selectedRoute.first { it != failedAt && it.route == route }
                true
            }
        }.stateInReadModel(presenterScope, null)

    private val selectedConversation = selectedRoute.flatMapLatest { occurrence ->
        val route = occurrence.route
        if (route == null) {
            flowOf(emptySelection(occurrence, ChatContentStatus.Ready))
        } else {
            val key = DirectConversationKey(account.id.value, route.peerJid, route.thread)
            val subscription = synchronized(selectedRoute) { localSubscription.value }
            flow {
                emit(emptySelection(occurrence, ChatContentStatus.Loading))
                emitAll(
                    combine(
                        localConversation.mapNotNull { it.value }.filter { it.subscription == subscription },
                        repository.observeStoredDraft(key),
                        observeRoom(route.peerJid).onStart { emit(null) },
                        observeTyping(route.peerJid).onStart { emit(emptyList()) },
                        observeRtt(route.peerJid).onStart { emit(null) },
                    ) { local, draft, room, composers, rttText ->
                        if (local.status != ChatContentStatus.Ready) {
                            emptySelection(occurrence, local.status)
                        } else {
                            SelectedConversation(
                                occurrence, local.messages, draft, local.peer, room,
                                local.currentSession, local.recentThreads, local.mainUnreadCount, composers, rttText,
                            )
                        }
                    },
                )
            }.catch { failure ->
                if (failure is CancellationException) throw failure
                emit(emptySelection(occurrence, ChatContentStatus.Failed))
            }
        }
    }

    val state = combine(
        flow {
            emit(false to emptyList<ConversationSummary>())
            emit(true to repository.cachedConversations(account.id.value))
            emitAll(repository.observeConversations(account.id.value).map { true to it })
        }.catch { failure ->
            if (failure is CancellationException) throw failure
            emit(false to emptyList())
        },
        selectedConversation,
        sendGuard,
        selectedRoute,
    ) { conversations, loaded, guard, occurrence ->
        val selected = loaded.takeIf { it.occurrence == occurrence }
            ?: emptySelection(occurrence, if (occurrence.route == null) ChatContentStatus.Ready else ChatContentStatus.Loading)
        val groupChat = selected.peer?.room == true
        val selectedKind = if (groupChat) MessageKind.GROUPCHAT else MessageKind.CHAT
        DirectChatState(
            accountId = account.id.value,
            conversations = conversations.second,
            conversationsReady = conversations.first,
            routeOccurrence = occurrence,
            contentStatus = selected.status,
            selectedPeer = selected.route?.peerJid,
            selectedPeerDisplayName = selected.peer?.remoteProfileName,
            selectedPeerLocalNickname = selected.peer?.localNickname,
            selectedPeerPhotoBytes = selected.peer?.photoBytes,
            selectedPeerPhotoMime = selected.peer?.photoMime,
            selectedPeerGroupChat = groupChat,
            selectedRoomSubject = selected.room?.subject,
            selectedRoomOccupantCount = selected.room?.occupantCount ?: 0,
            selectedThread = selected.route?.thread,
            currentSession = selected.currentSession.takeUnless { groupChat },
            recentThreads = selected.recentThreads.filter { it.messageKind == selectedKind },
            mainUnreadCount = selected.mainUnreadCount,
            messages = selected.messages,
            draft = selected.draft.body,
            draftReply = selected.draft.reply,
            draftAttachmentUrl = selected.draft.attachmentUrl,
            draftAttachmentName = selected.draft.attachmentName,
            draftAttachmentMime = selected.draft.attachmentMime,
            draftAttachmentSize = selected.draft.attachmentSize,
            typingLabel = liveTypingLabel(
                composers = selected.composers,
                directName = selected.peerLabelForTyping(groupChat),
                rttText = selected.rttText,
            ),
            pendingSendIdentities = guard.pending,
            completedSendSnapshots = guard.completed,
        )
    }.stateIn(
        presenterScope,
        SharingStarted.Eagerly,
        DirectChatState(accountId = account.id.value),
    )

    init {
        presenterScope.launch {
            directoryConnection.collect { connection ->
                synchronized(selectedRoute) {
                    if (olderConnection != connection) {
                        olderConnection = connection
                        olderAttempt = null
                        olderHistory.value = OlderHistoryState()
                    }
                }
            }
        }
        presenterScope.launch {
            combine(state.map { Triple(it.routeOccurrence, it.contentStatus, it.selectedPeerGroupChat) }.distinctUntilChanged(), directoryConnection) { route, connection -> route to connection }
                .collectLatest { (route, _) ->
                    if (route.second == ChatContentStatus.Ready && route.first.route != null) refreshNamedThreads(route.first)
                }
        }
        presenterScope.launch {
            if (restoreRouteOnStart) {
                val restored = repository.observeRoute(account.id.value).first()
                synchronized(selectedRoute) {
                    if (routeGeneration.get() == 0) {
                        publishRoute(restored, routeGeneration.incrementAndGet())
                    }
                }
            } else if (routeGeneration.get() == 0) {
                repository.saveRoute(account.id.value, null)
            }
            routeReady.complete(Unit)
        }
        presenterScope.launch {
            var joins: RoomJoins? = null
            combine(
                state.filter { it.contentStatus == ChatContentStatus.Ready }
                    .map { it.routeOccurrence to it.selectedPeerGroupChat }.distinctUntilChanged(),
                directoryConnection.distinctUntilChanged(),
            ) { route, connection -> route to connection }.collect { (route, connection) ->
                val current = joins?.takeIf { it.connection == connection }
                    ?: RoomJoins(connection).also { joins = it }
                val peer = route.first.route?.peerJid ?: return@collect
                if (!route.second) return@collect
                // Retry only on a new route occurrence or connection, not on drafts,
                // room-view updates, or other presentation emissions after failure.
                if (synchronized(current) { peer !in current.joined && current.inFlight.add(peer) }) {
                    presenterScope.launch {
                        var joined = false
                        try {
                            joined = joinMuc(peer)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A failed join remains retryable on explicit entry/reconnect.
                        } finally {
                            synchronized(current) {
                                current.inFlight.remove(peer)
                                if (joined) current.joined.add(peer)
                            }
                        }
                    }
                }
            }
        }
        presenterScope.launch {
            state.collect { snapshot ->
                val peer = snapshot.selectedPeer ?: return@collect
                if (snapshot.contentStatus != ChatContentStatus.Ready) return@collect
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

    suspend fun selectPeer(value: String): Boolean = selectNotificationDestination(value, null)

    fun selectNotificationDestination(value: String, thread: ThreadRef?): Boolean {
        val canonical = canonicalDirectPeer(value) ?: return false
        selectRoute(ChatRoute(canonical, thread))
        return true
    }

    suspend fun markVisibleConversationRead(request: VisibleReadRequest): Boolean {
        val peer = request.occurrence.route?.peerJid ?: return false
        val caller = currentCoroutineContext()
        fun owns(snapshot: DirectChatState): Boolean =
            presenterJob.isActive && request.accountId == account.id.value && snapshot.accountId == request.accountId &&
                selectedRoute.value == request.occurrence && snapshot.routeOccurrence == request.occurrence &&
                snapshot.contentStatus == ChatContentStatus.Ready

        while (true) {
            val messages = synchronized(selectedRoute) {
                val snapshot = state.value
                if (!owns(snapshot)) return false
                snapshot.messages
            }
            val ids = withContext(Dispatchers.Default) {
                val rendered = messages.mapTo(hashSetOf()) { it.id }
                val boundary = request.observedTimelineIds.indexOfLast {
                    it in request.messageIds && it in rendered
                }
                request.observedTimelineIds.take(boundary + 1).filter { it in rendered }
            }
            val write = synchronized(selectedRoute) {
                val snapshot = state.value
                if (!owns(snapshot)) return false
                // A same-occurrence edit can remove/reproject rows while preparation is suspended.
                // Recompute against that membership without admitting later observation IDs.
                if (snapshot.messages !== messages) null
                else {
                    if (ids.isEmpty()) return false
                    // The final cancellation check linearizes admission. From here the presenter
                    // owns persistence, independently of subsequent effect disposal or navigation.
                    caller.ensureActive()
                    presenterScope.async(Dispatchers.IO) {
                        try {
                            repository.markMessagesRead(request.accountId, peer, ids)
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
            }
            if (write != null) return write.await()
        }
    }

    suspend fun joinRoom(value: String): Boolean {
        val canonical = canonicalDirectPeer(value) ?: return false
        selectRoute(ChatRoute(canonical))
        presenterScope.launch {
            repository.markRoom(account.id.value, canonical)
        }
        return true
    }

    fun closeConversation() {
        selectRoute(null)
    }

    suspend fun startNewThread(): Boolean {
        val origin = currentRouteOccurrence() ?: return false
        val route = origin.route
        val groupChat = repository.observePeer(account.id.value, route.peerJid).first()?.room == true
        val currentSession = if (groupChat) {
            null
        } else {
            repository.observeCurrentSession(account.id.value, route.peerJid).first()
                ?: repository.ensureCurrentSession(account.id.value, route.peerJid)
        }
        return selectRouteIfCurrent(origin, route.copy(thread = newTopic(currentSession)))
    }

    suspend fun continueThread(thread: ThreadRef): Boolean {
        val origin = currentRouteOccurrence() ?: return false
        return selectRouteIfCurrent(origin, origin.route.copy(thread = threadingPolicy.replyTo(thread)))
    }

    suspend fun startChildThread(): Boolean {
        val origin = currentRouteOccurrence() ?: return false
        val parent = origin.route.thread ?: return false
        return selectRouteIfCurrent(origin, origin.route.copy(thread = threadingPolicy.childOf(parent)))
    }

    suspend fun startChildThreadOf(parent: ThreadRef): Boolean {
        val origin = currentRouteOccurrence() ?: return false
        return selectRouteIfCurrent(origin, origin.route.copy(thread = threadingPolicy.childOf(parent)))
    }

    suspend fun startThreadFrom(message: TimelineMessage): Boolean {
        if (message.protectedState != "NONE") return false
        val origin = currentRouteOccurrence() ?: return false
        val route = origin.route
        val current = state.value
        if (current.routeOccurrence != ChatRouteOccurrence(route, origin.generation)) return false
        if (current.selectedPeer != route.peerJid || current.selectedThread != route.thread) return false
        if (current.selectedPeerGroupChat) return false
        if (current.messages.singleOrNull { it.id == message.id } != message) return false
        val reference = message.replyReferenceId ?: return false
        val thread = message.thread?.let(threadingPolicy::replyTo) ?: newTopic(
            current.currentSession ?: repository.ensureCurrentSession(account.id.value, route.peerJid),
        )
        val nextRoute = route.copy(thread = thread)
        val reply = DraftReply(
            id = reference,
            to = message.senderJid,
            body = message.body,
            senderLabel = message.senderJid.replySenderLabel(),
        )
        if (selectedRoute.value != ChatRouteOccurrence(route, origin.generation)) return false
        if (!repository.openThreadReply(account.id.value, route, nextRoute, message, reply)) return false
        // The committed reply draft remains recoverable if its originating view was replaced.
        return selectRouteIfCurrent(origin, nextRoute)
    }

    fun closeThread() {
        val route = selectedRoute.value.route ?: return
        selectRoute(route.copy(thread = null))
    }

    fun close() {
        presenterJob.cancel()
    }

    fun updateDraft(snapshot: DraftSnapshot): Deferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        synchronized(actionLock) {
            if (!presenterJob.isActive) {
                result.complete(false)
                return result
            }
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
                    // Retirement can prevent even the worker's try/finally from starting.
                    it.invokeOnCompletion {
                        if (!presenterJob.isActive) failPendingDrafts()
                    }
                    it.start()
                }
            }
        }
        return result
    }

    private fun DraftSnapshot.outboundBody(): String =
        if (body.isBlank()) attachmentUrl?.takeIf { it.isNotBlank() } ?: body else body

    fun sendDraft(snapshot: DraftSnapshot): Deferred<Boolean> {
        val identity = PendingSendIdentity(snapshot.key, snapshot.composerRevision)
        if (!claimSend(identity)) return CompletableDeferred(false)
        return submitAction {
            val sent = owns(snapshot.key) &&
                snapshot.outboundThread == null &&
                snapshot.outboundBody().isNotBlank() &&
                enqueue(account, snapshot.copy(body = snapshot.outboundBody()))
            settleSend(identity, snapshot, sent)
            sent
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause != null) settleSend(identity, snapshot, false)
            }
        }
    }

    fun sendDraftAsNewThread(snapshot: DraftSnapshot): Deferred<Boolean> {
        val origin = currentRouteOccurrence()
        val identity = PendingSendIdentity(snapshot.key, snapshot.composerRevision)
        if (!claimSend(identity)) return CompletableDeferred(false)
        return submitAction {
            if (!owns(snapshot.key) ||
                snapshot.key.thread != null ||
                snapshot.outboundThread != null ||
                snapshot.outboundBody().isBlank()
            ) {
                settleSend(identity, snapshot, false)
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
            val sent = enqueue(account, snapshot.copy(body = snapshot.outboundBody(), outboundThread = thread))
            if (sent && origin != null && origin.route == ChatRoute(snapshot.key.canonicalBarePeer)) {
                selectRouteIfCurrent(origin, origin.route.copy(thread = thread))
            }
            settleSend(identity, snapshot, sent)
            sent
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause != null) settleSend(identity, snapshot, false)
            }
        }
    }

    fun isSending(identity: PendingSendIdentity): Boolean {
        val guard = sendGuard.value
        return identity in guard.pending || identity in guard.completed
    }

    fun acknowledgeCompletedSends(ids: Set<PendingSendIdentity>) {
        if (ids.isEmpty()) return
        synchronized(actionLock) {
            val guard = sendGuard.value
            sendGuard.value = guard.copy(completed = guard.completed - ids)
        }
    }

    private fun claimSend(identity: PendingSendIdentity): Boolean = synchronized(actionLock) {
        val guard = sendGuard.value
        if (identity in guard.pending || identity in guard.completed) return false
        sendGuard.value = guard.copy(pending = guard.pending + identity)
        true
    }

    private fun settleSend(identity: PendingSendIdentity, snapshot: DraftSnapshot, sent: Boolean) {
        synchronized(actionLock) {
            val guard = sendGuard.value
            if (identity !in guard.pending) return
            sendGuard.value = guard.copy(
                pending = guard.pending - identity,
                completed = if (sent) guard.completed + (identity to snapshot) else guard.completed,
            )
        }
    }

    fun selectThreadDestination(origin: ChatRouteOccurrence, thread: ThreadRef?) {
        if (selectedRoute.value != origin || state.value.routeOccurrence != origin) return
        val route = origin.route ?: return
        if (thread != null && state.value.recentThreads.none { it.thread == thread && it.locallyNamed }) return
        selectRouteIfCurrent(RouteOccurrence(route, origin.generation), route.copy(thread = thread))
    }

    suspend fun createNamedThread(origin: ChatRouteOccurrence, title: String): Boolean {
        if (selectedRoute.value != origin || state.value.routeOccurrence != origin ||
            state.value.contentStatus != ChatContentStatus.Ready || threadNameError(title) != null
        ) return false
        val route = origin.route ?: return false
        val kind = if (state.value.selectedPeerGroupChat) MessageKind.GROUPCHAT else MessageKind.CHAT
        if (directory.value.mode == DirectoryMode.CHECKING) refreshNamedThreads(origin)
        if (selectedRoute.value != origin) return false
        if (directory.value.mode != DirectoryMode.LOCAL_ONLY) return false
        val thread = repository.createNamedThread(account.id.value, route.peerJid, kind, title) ?: return false
        // Persistence may finish after switching away. Keep the created directory row, but
        // never redirect a successor route or its composer to the old operation's result.
        selectRouteIfCurrent(RouteOccurrence(route, origin.generation), route.copy(thread = thread))
        return true
    }

    /** The form captures this capability before accepting a shared name, not at submission. */
    suspend fun createSharedNamedThread(origin: ChatRouteOccurrence, title: String, authored: DirectoryContext): Boolean {
        if (selectedRoute.value != origin || state.value.routeOccurrence != origin ||
            state.value.contentStatus != ChatContentStatus.Ready || threadNameError(title) != null ||
            !directory.value.writable || directory.value.context != authored) return false
        val route = origin.route ?: return false
        val action = DirectoryAction(authored, title = title.trim())
        if (!changeShared(origin, action)) return false
        selectRouteIfCurrent(RouteOccurrence(route, origin.generation), route.copy(thread = ThreadRef(ThreadId.require(action.threadId.toString()))))
        return true
    }

    /** Shared forms retain their authored context; null authorizes only a private local rename. */
    suspend fun renameNamedThread(origin: ChatRouteOccurrence, recent: RecentThread, title: String, authored: DirectoryContext?): Boolean {
        if (selectedRoute.value != origin || state.value.routeOccurrence != origin) return false
        if (recent.shared != null) {
            if (threadNameError(title) != null) return false
            return changeShared(origin, sharedAction(recent, authored ?: return false, title = title.trim()) ?: return false)
        }
        return renameThread(recent, title)
    }

    suspend fun renameThread(recent: RecentThread, title: String): Boolean {
        if (recent.shared != null) return false
        val route = selectedRoute.value.route ?: return false
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
            try {
                for (pending in batch) {
                    val ok = try {
                        if (!owns(pending.snapshot.key)) {
                            false
                        } else {
                            repository.saveDraft(
                                pending.snapshot.key,
                                pending.snapshot.body,
                                pending.snapshot.reply,
                                pending.snapshot.attachmentUrl,
                                pending.snapshot.attachmentName,
                                pending.snapshot.attachmentMime,
                                pending.snapshot.attachmentSize,
                            )
                            // Save completion, not ephemeral typing, owns durable success.
                            pending.waiters.forEach { waiter -> waiter.complete(true) }
                            if (!pending.snapshot.groupChat) {
                                notifyComposer(
                                    pending.snapshot.key.canonicalBarePeer,
                                    pending.snapshot.body.isNotEmpty(),
                                )
                            }
                            true
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    pending.waiters.forEach { waiter -> waiter.complete(ok) }
                }
            } finally {
                // These waiters no longer belong to coalescedDrafts. Settle untouched keys
                // on cancellation too; already committed results retain their success.
                batch.forEach { pending ->
                    pending.waiters.forEach { waiter -> waiter.complete(false) }
                }
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
            predecessor?.let { runCatching { it.join() } }
            action()
        }.also {
            actionTail = it
            it.start()
        }
    }

    private data class RouteOccurrence(val route: ChatRoute, val generation: Int)

    private fun currentRouteOccurrence(): RouteOccurrence? = synchronized(selectedRoute) {
        selectedRoute.value.let { occurrence -> occurrence.route?.let { RouteOccurrence(it, occurrence.generation) } }
    }

    private fun selectRouteIfCurrent(origin: RouteOccurrence, route: ChatRoute): Boolean =
        synchronized(selectedRoute) {
            if (routeGeneration.get() != origin.generation || selectedRoute.value.route != origin.route) {
                return@synchronized false
            }
            selectRoute(route)
            true
        }

    private fun selectRoute(route: ChatRoute?) = synchronized(selectedRoute) {
        val generation = routeGeneration.incrementAndGet()
        publishRoute(route, generation)
        presenterScope.launch { persistRoute(generation) }
        Unit
    }

    // Called under selectedRoute's lock: invalidate before publishing navigation, not
    // after flatMapLatest finishes cancelling. Home retains the uninterrupted lifetime.
    private fun publishRoute(route: ChatRoute?, generation: Int) {
        directory.value = DirectoryView(DirectoryMode.CHECKING)
        if (route != null && localSubscription.value?.route != route) {
            localSubscription.value = LocalSubscription(route, generation)
        }
        selectedRoute.value = ChatRouteOccurrence(route, generation)
    }

    private suspend fun persistRoute(generation: Int) {
        routeReady.await()
        if (generation != routeGeneration.get()) return
        repository.saveRoute(account.id.value, selectedRoute.value.route)
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

private fun MessageDraftEntity.toStoredDraft() = StoredDraft(
    body = body,
    attachmentUrl = attachmentUrl,
    attachmentName = attachmentName,
    attachmentMime = attachmentMime,
    attachmentSize = attachmentSize,
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

internal data class ResolvedThread(
    val thread: ThreadRef,
    val root: TimelineMessage,
    val members: List<TimelineMessage>,
)

internal fun List<TimelineMessage>.projectThreads(
    selected: ThreadRef?,
    threads: ThreadIndex,
): List<TimelineMessage> {
    val resolved = threads.resolved.values
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
                    latestPreview = protectedStatus(latest.protectedState) ?: latest.body,
                )
            }
        message.copy(threadSummaries = attached)
    }
}

// One grouping and one reply index per immutable history, shared by Main, the
// selected thread and recent threads. Full ThreadRef (including parent) is the key.
internal class ThreadIndex(timeline: List<TimelineMessage>) {
    val resolved: Map<ThreadRef, ResolvedThread>

    init {
        val replies = ReplyIndex(timeline)
        val membersByThread = timeline.filter { !it.groupChat && it.thread?.parentId != null }
            .groupBy { requireNotNull(it.thread) }
        resolved = membersByThread.mapValues { (thread, members) ->
            val memberIds = members.mapTo(hashSetOf(), TimelineMessage::id)
            val externalRoot = members.firstNotNullOfOrNull { member ->
                replies.resolve(member)?.takeIf { candidate ->
                    candidate.protectedState == "NONE" && !candidate.groupChat && candidate.id !in memberIds &&
                        (candidate.thread?.id == thread.parentId ||
                            (candidate.thread == null && thread.parentId != null))
                }
            }
            ResolvedThread(thread, externalRoot ?: members.first(),
                if (externalRoot == null) members.drop(1) else members)
        }
    }
}

internal fun List<TimelineMessage>.recentThreads(
    titles: List<NamedThreadRow>,
    threads: ThreadIndex,
): List<RecentThread> {
    val customTitles = titles.associateBy { it.messageKind to it.threadId }
    val history = withIndex()
        .filter { it.value.thread != null }
        .groupBy {
            val kind = if (it.value.groupChat) MessageKind.GROUPCHAT else MessageKind.CHAT
            kind to requireNotNull(it.value.thread)
        }
        .map { (key, indexed) ->
            val (kind, thread) = key
            val members = indexed.map { it.value }
            val resolved = if (kind == MessageKind.CHAT && thread.parentId != null) {
                threads.resolved[thread]
            } else {
                null
            }
            val replies = resolved?.members?.size ?: (members.size - 1).coerceAtLeast(0)
            val root = resolved?.root ?: members.first()
            val defaultTitle = (protectedStatus(root.protectedState) ?: root.body).threadTitlePreview()
            val title = customTitles[kind to thread.id.value]?.title ?: defaultTitle
            val local = customTitles[kind to thread.id.value]
            indexed.maxOf { it.index } to RecentThread(
                thread, title, replies, kind, locallyNamed = local != null, unreadCount = local?.unreadCount ?: 0,
                shared = local?.takeIf { it.authority != null },
            )
        }
        .groupBy { it.second.messageKind }
        .values
        .flatMap { kind -> kind.sortedByDescending { it.first }.take(10) }
        .sortedByDescending { it.first }
        .map { it.second }
    val named = titles.map { row ->
        history.firstOrNull { it.messageKind == row.messageKind && it.thread.id.value == row.threadId }
            ?: RecentThread(
                ThreadRef(ThreadId.require(row.threadId), row.parentThreadId?.let(ThreadId::require)),
                row.title, 0, row.messageKind, locallyNamed = true, unreadCount = row.unreadCount,
                shared = row.takeIf { it.authority != null },
            )
    }
    // No empty or older named row disappears merely because other messages arrived.
    return history + named.filterNot { entry -> history.any {
        it.messageKind == entry.messageKind && it.thread == entry.thread
    } }
}

private fun String.threadTitlePreview(): String {
    val normalized = trim()
    if (normalized.isEmpty()) return "Thread"
    val codePoints = normalized.codePointCount(0, normalized.length).coerceAtMost(20)
    return normalized.substring(0, normalized.offsetByCodePoints(0, codePoints))
}

// A null map value records ambiguity, rather than choosing a first/last match.
// Author-qualified indexes bound lookups even when many senders reuse one wire ID.
internal class ReplyIndex(timeline: List<TimelineMessage>) {
    private val unqualified = hashMapOf<String, TimelineMessage?>()
    private val direct = hashMapOf<Pair<String, String>, TimelineMessage?>()
    private val muc = hashMapOf<Pair<String, String>, TimelineMessage?>()

    init {
        for (message in timeline) {
            val references = message.replyReferenceIds + listOfNotNull(message.replyReferenceId)
            for (reference in references) {
                unqualified.addCandidate(reference, message)
                if (message.groupChat) muc.addCandidate(reference to message.senderJid, message)
                else direct.addCandidate(reference to message.senderJid.substringBefore('/'), message)
            }
        }
    }

    fun resolve(message: TimelineMessage): TimelineMessage? {
        val reference = message.replyToId ?: return null
        val author = message.replyToJid ?: return unqualified[reference]
        val directKey = reference to author.substringBefore('/')
        val mucKey = reference to author
        return when {
            direct.containsKey(directKey) && muc.containsKey(mucKey) -> null
            direct.containsKey(directKey) -> direct[directKey]
            else -> muc[mucKey]
        }
    }

    private fun <K> MutableMap<K, TimelineMessage?>.addCandidate(key: K, message: TimelineMessage) {
        this[key] = if (containsKey(key)) null else message
    }
}

private fun TimelineMessage.withReplyPresentation(replies: ReplyIndex): TimelineMessage {
    if (protectedState != "NONE" || replyToId == null) return this
    val target = replies.resolve(this)
    if (target != null && target.protectedState != "NONE") return copy(reply = null)
    val fallback = replyFallbackBody?.toFallbackPreview()
    val previewBody = target?.body ?: fallback?.body ?: return this
    val sender = target?.senderJid?.replySenderLabel()
        ?: fallback?.senderLabel
        ?: replyToJid?.replySenderLabel()
        ?: "message"
    return copy(reply = MessageReplyPresentation(sender, previewBody))
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
