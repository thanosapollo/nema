package org.thanosapollo.nema.ui.chat

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.LinkedHashMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.thanosapollo.nema.chat.DeliveryPresentation
import org.thanosapollo.nema.chat.ChatRouteOccurrence
import org.thanosapollo.nema.chat.VisibleReadRequest
import org.thanosapollo.nema.chat.ChatContentStatus
import org.thanosapollo.nema.chat.OlderHistoryState
import org.thanosapollo.nema.chat.OlderHistoryStatus
import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.DraftCorrection
import org.thanosapollo.nema.chat.DraftReply
import org.thanosapollo.nema.chat.DraftSnapshot
import org.thanosapollo.nema.chat.PendingSendIdentity
import org.thanosapollo.nema.chat.RecentThread
import org.thanosapollo.nema.chat.ThreadSummary
import org.thanosapollo.nema.chat.TimelineMessage
import org.thanosapollo.nema.chat.threadSummaryLabel
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.thread.draftKey
import org.thanosapollo.nema.ui.HomeContent
import org.thanosapollo.nema.ui.SettingsProfileScreen
import org.thanosapollo.nema.ui.SettingsRow
import org.thanosapollo.nema.ui.SettingsRowTone
import org.thanosapollo.nema.ui.SettingsSectionHeader
import org.thanosapollo.nema.ui.quietConnectionStatus
import org.thanosapollo.nema.ui.theme.LocalChatBackgroundUri
import org.thanosapollo.nema.ui.theme.readableOn
import org.thanosapollo.nema.ui.theme.toComposeColor
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingState
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingMutationResult
import org.thanosapollo.nema.xmpp.httpupload.attachmentActionLabel
import org.thanosapollo.nema.xmpp.httpupload.attachmentBodyCaption
import org.thanosapollo.nema.xmpp.httpupload.isInlineImage
import org.thanosapollo.nema.xmpp.httpupload.resolvedAttachmentMime
import org.thanosapollo.nema.xmpp.httpupload.shouldRenderInlineImage
import org.thanosapollo.nema.xmpp.httpupload.slotFilename
import org.thanosapollo.nema.xmpp.muc.roomSubtitle

@Composable
internal fun messageBubbleColors(outgoing: Boolean): Pair<Color, Color> = if (outgoing) {
    MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
} else {
    MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
}

internal enum class ThreadRouteRevealDirection { FORWARD, BACKWARD, NONE }

internal data class ThreadRouteRevealStart(
    val offsetFraction: Float,
    val scale: Float,
    val durationMillis: Int,
)

internal fun threadRouteRevealDirection(
    previous: DirectConversationKey,
    current: DirectConversationKey,
): ThreadRouteRevealDirection {
    if (previous.accountId != current.accountId ||
        previous.canonicalBarePeer != current.canonicalBarePeer ||
        previous == current
    ) {
        return ThreadRouteRevealDirection.NONE
    }
    val oldThread = previous.thread
    val newThread = current.thread
    return when {
        oldThread == null && newThread != null -> ThreadRouteRevealDirection.FORWARD
        oldThread != null && newThread?.parentId == oldThread.id -> ThreadRouteRevealDirection.FORWARD
        oldThread != null && newThread == null -> ThreadRouteRevealDirection.BACKWARD
        oldThread?.parentId != null && newThread?.id == oldThread.parentId -> ThreadRouteRevealDirection.BACKWARD
        else -> ThreadRouteRevealDirection.NONE
    }
}

internal fun threadRouteRevealStart(
    direction: ThreadRouteRevealDirection,
    layoutDirection: LayoutDirection,
): ThreadRouteRevealStart {
    val logicalOffset = when (direction) {
        ThreadRouteRevealDirection.FORWARD -> 0.11f
        ThreadRouteRevealDirection.BACKWARD -> -0.09f
        ThreadRouteRevealDirection.NONE -> 0f
    }
    val physicalOffset = if (layoutDirection == LayoutDirection.Ltr) logicalOffset else -logicalOffset
    return when (direction) {
        ThreadRouteRevealDirection.FORWARD -> ThreadRouteRevealStart(physicalOffset, 0.985f, 220)
        ThreadRouteRevealDirection.BACKWARD -> ThreadRouteRevealStart(physicalOffset, 0.985f, 190)
        ThreadRouteRevealDirection.NONE -> ThreadRouteRevealStart(0f, 1f, 0)
    }
}

/** Home remains composed, but a visible conversation owns its complete hit-test plane. */
private fun Modifier.ownConversationTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            // This highest-z, non-sharing pointer node excludes Home from the hit-test chain.
            // Observe without consuming so descendant drag/selection detectors retain all passes.
            awaitPointerEvent()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConversationContent(
    state: DirectChatState,
    connectionStatus: String,
    onSelectPeer: suspend (String) -> Boolean,
    onJoinRoom: suspend (String) -> Boolean = onSelectPeer,
    onCloseConversation: () -> Unit,
    onMarkVisibleRead: suspend (VisibleReadRequest) -> Boolean = { true },
    onDraftChange: (DraftSnapshot) -> Deferred<Boolean>,
    onSend: (DraftSnapshot) -> Deferred<Boolean>,
    onAcknowledgeCompletedSends: (Set<PendingSendIdentity>) -> Unit = {},
    onStartNewThread: suspend () -> Boolean = { false },
    onContinueThread: suspend (ThreadRef) -> Boolean = { false },
    onStartChildThread: suspend () -> Boolean = { false },
    onStartThreadFrom: suspend (TimelineMessage) -> Boolean = { false },
    onCloseThread: () -> Unit = {},
    onRenameThread: suspend (RecentThread, String) -> Boolean = { _, _ -> false },
    onSelectThreadDestination: (ChatRouteOccurrence, ThreadRef?) -> Unit = { _, _ -> },
    directoryView: org.thanosapollo.nema.xmpp.threads.DirectoryView = org.thanosapollo.nema.xmpp.threads.DirectoryView(),
    onRefreshNamedThreads: suspend (ChatRouteOccurrence) -> Unit = {},
    onArchiveNamedThread: suspend (ChatRouteOccurrence, RecentThread) -> Boolean = { _, _ -> false },
    onRetryDirectoryChange: suspend (ChatRouteOccurrence) -> Boolean = { false },
    onKeepCurrentDirectory: suspend (ChatRouteOccurrence) -> Boolean = { false },
    onCreateNamedThread: suspend (ChatRouteOccurrence, String) -> Boolean = { _, _ -> false },
    onCreateSharedNamedThread: suspend (ChatRouteOccurrence, String, org.thanosapollo.nema.xmpp.threads.DirectoryContext) -> Boolean = { _, _, _ -> false },
    onRenameNamedThread: suspend (ChatRouteOccurrence, RecentThread, String, org.thanosapollo.nema.xmpp.threads.DirectoryContext?) -> Boolean = { _, _, _, _ -> false },
    blockingSession: SessionIdentity? = null,
    onSavePeerNickname: suspend (DirectConversationKey, String) -> Boolean = { _, _ -> false },
    onLoadPeerBlocking: suspend (SessionIdentity, DirectConversationKey) -> PeerBlockingState = { _, _ ->
        PeerBlockingState(supported = false)
    },
    onSetPeerBlocked: suspend (SessionIdentity, DirectConversationKey, Boolean) -> PeerBlockingMutationResult = { _, _, _ ->
        PeerBlockingMutationResult.NotAttempted
    },
    onSharePeer: ((String) -> Unit)? = null,
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    ownLabel: String = "",
    ownPhotoBytes: ByteArray? = null,
    onOpenOwnProfile: () -> Unit = {},
    onUploadFile: suspend (String, String?, ByteArray) -> org.thanosapollo.nema.xmpp.httpupload.UploadedFile? = { _, _, _ -> null },
    onUseAttachment: suspend (String, String?, String?) -> Boolean = { _, _, _ -> false },
    isAttachmentCached: (String) -> Boolean = { false },
    onLoadInlineImage: suspend (String) -> ImageBitmap? = { null },
    readReceiptsEnabled: Boolean = false,
    activityResumed: Boolean = false,
    onMessageDisplayed: suspend (TimelineMessage) -> Boolean = { false },
    onReact: suspend (TimelineMessage, String) -> Boolean = { _, _ -> false },
    modifier: Modifier = Modifier,
    composerOwner: ComposerOwner = rememberComposerOwner(state.accountId),
    olderHistory: OlderHistoryState = OlderHistoryState(),
    onLoadOlder: suspend (ChatRouteOccurrence) -> Unit = {},
) {
    val venue = state.conversationVenue()
    key(state.accountId) {
        val scope = rememberCoroutineScope()
        var pendingSendIdentities by composerOwner.pendingSendIdentities
        var completedSendSnapshots by composerOwner.completedSendSnapshots
        var failedSendIdentities by composerOwner.failedSendIdentities
        val pendingDraftAttempts by composerOwner.pendingDraftAttempts
        var composerStates by composerOwner.composerStates
        val venueByConversation = composerOwner.venueByConversation
        LaunchedEffect(state.pendingSendIdentities, state.completedSendSnapshots) {
            pendingSendIdentities = pendingSendIdentities + state.pendingSendIdentities
            completedSendSnapshots = completedSendSnapshots + state.completedSendSnapshots
        }
        val timelineViewports = remember(state.accountId) {
            TimelineViewportStore()
        }
        val currentConversationKey by rememberUpdatedState(
            state.selectedPeer?.let { DirectConversationKey(state.accountId, it, state.selectedThread) },
        )
        val currentBlockingSession by rememberUpdatedState(blockingSession)
        val backgroundUri = LocalChatBackgroundUri.current
        val selectedPeer = state.selectedPeer
        Box(modifier.fillMaxSize()) {
            ChatBackground(backgroundUri)
            HomeContent(
                conversations = state.conversations,
                conversationsReady = state.conversationsReady,
                connectionStatus = connectionStatus,
                ownLabel = ownLabel,
                ownPhotoBytes = ownPhotoBytes,
                onSelectPeer = onSelectPeer,
                onJoinRoom = onJoinRoom,
                onOpenOwnProfile = onOpenOwnProfile,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.background.copy(
                            alpha = if (backgroundUri == null) 1f else 0.88f,
                        ),
                    )
                    .then(if (selectedPeer != null) Modifier.clearAndSetSemantics { } else Modifier),
            )
            if (selectedPeer != null) {
                Box(Modifier.fillMaxSize().ownConversationTouches()) {
                    if (state.contentStatus != ChatContentStatus.Ready) {
                        val close = { if (state.selectedThread != null) onCloseThread() else onCloseConversation() }
                        BackHandler { close() }
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            TopAppBar(
                                modifier = Modifier.height(64.dp).testTag("conversation-top-bar"),
                                title = {
                                    Column {
                                        Text(selectedPeer, style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        state.selectedThread?.let {
                                            Text("Thread ${it.id.value}", style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                },
                                navigationIcon = { TextButton(onClick = { close() }) { Text("Back") } },
                                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            )
                            if (state.contentStatus == ChatContentStatus.Failed) {
                                Text("Unable to load conversation")
                            } else {
                                org.thanosapollo.nema.ui.LoadingContent("Loading conversation", Modifier.weight(1f))
                            }
                        }
                    }
                    if (state.contentStatus == ChatContentStatus.Ready) {
                        val conversationKey = DirectConversationKey(
                            state.accountId,
                            selectedPeer,
                            state.selectedThread,
                        )
                        venueByConversation[conversationKey] = venue
                        val peerKey = conversationKey.copy(thread = null)
                        val peerLabel = state.selectedPeerLabel ?: state.selectedPeer.orEmpty()
                        val conversationInfo = venue.toConversationInfo(
                            address = selectedPeer,
                            localNickname = state.selectedPeerLocalNickname,
                            remoteProfileName = state.selectedPeerDisplayName,
                        )
                        val infoTitle = when (conversationInfo) {
                            is ConversationInfo.DirectContact -> "Contact info"
                            is ConversationInfo.Room -> "Room info"
                        }
                        val openInfoDescription = when (conversationInfo) {
                            is ConversationInfo.DirectContact -> "Open contact info"
                            is ConversationInfo.Room -> "Open room info"
                        }
                        var showPeerProfile by rememberSaveable(
                            peerKey.accountId,
                            peerKey.canonicalBarePeer,
                        ) { mutableStateOf(false) }
                        var backProgress by remember { mutableFloatStateOf(0f) }
                        val layoutDirection = LocalLayoutDirection.current
                        var previousConversationKey by remember { mutableStateOf(conversationKey) }
                        val routeRevealStart = remember(conversationKey, layoutDirection) {
                            threadRouteRevealStart(
                                threadRouteRevealDirection(previousConversationKey, conversationKey),
                                layoutDirection,
                            )
                        }
                        val routeRevealProgress = remember(conversationKey, layoutDirection) {
                            Animatable(if (routeRevealStart.durationMillis == 0) 1f else 0f)
                        }
                        LaunchedEffect(state.selectedPeer, state.selectedThread) {
                            backProgress = 0f
                        }
                        LaunchedEffect(conversationKey, layoutDirection) {
                            previousConversationKey = conversationKey
                            if (routeRevealStart.durationMillis > 0) {
                                routeRevealProgress.animateTo(
                                    1f,
                                    animationSpec = tween(routeRevealStart.durationMillis),
                                )
                            }
                        }
                        BackHandler(enabled = showPeerProfile) { showPeerProfile = false }
                        BackHandler(enabled = !showPeerProfile && state.selectedThread != null) {
                            onCloseThread()
                        }
                        PredictiveBackHandler(enabled = !showPeerProfile && state.selectedThread == null) { events ->
                            try {
                                events.collect { event ->
                                    backProgress = event.progress
                                }
                                backProgress = 0f
                                onCloseConversation()
                            } catch (cancelled: CancellationException) {
                                backProgress = 0f
                                throw cancelled
                            }
                        }
                        if (showPeerProfile) {
                            ConversationInfoContent(
                                key = peerKey,
                                blockingSession = blockingSession,
                                label = peerLabel,
                                info = conversationInfo,
                                photoBytes = state.selectedPeerPhotoBytes,
                                recentThreads = state.recentThreads,
                                onBack = { showPeerProfile = false },
                                onSaveNickname = onSavePeerNickname,
                                onLoadBlocking = onLoadPeerBlocking,
                                onSetBlocked = onSetPeerBlocked,
                                isCurrentBlockingOwner = { session, key ->
                                    currentBlockingSession == session &&
                                        currentConversationKey?.copy(thread = null) == key
                                },
                                onSharePeer = onSharePeer,
                                onOpenThread = { thread ->
                                    scope.launch {
                                        if (onContinueThread(thread)) showPeerProfile = false
                                    }
                                },
                                onRenameThread = onRenameThread,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                            )
                        } else {
                            val status = quietConnectionStatus(connectionStatus)
                            var actionsOpen by remember { mutableStateOf(false) }
                            if (conversationKey !in composerStates) {
                                composerStates += conversationKey to ComposerState(
                                    conversationKey,
                                    state.draft,
                                    0L,
                                    reply = state.draftReply,
                                    attachmentUrl = state.draftAttachmentUrl,
                                    attachmentName = state.draftAttachmentName,
                                    attachmentMime = state.draftAttachmentMime,
                                    attachmentSize = state.draftAttachmentSize,
                                )
                            }
                            val composer = composerStates.getValue(conversationKey)
                            fun setComposer(next: ComposerState) {
                                if (next.key == composer.key && next.revision != composer.revision) {
                                    failedSendIdentities = failedSendIdentities.filterNot { it.key == next.key }.toSet()
                                }
                                composerStates += conversationKey to next
                            }
                            val composerFocus = remember(conversationKey) { FocusRequester() }
                            val effectOwner = ConversationEffectOwner(conversationKey, state.routeOccurrence)
                            var latestFocusRequest by remember(effectOwner) { mutableStateOf(0L) }
                            LaunchedEffect(effectOwner, completedSendSnapshots) {
                                val completions = completedSendSnapshots.filterKeys { it.key == conversationKey }
                                if (completions.isEmpty()) return@LaunchedEffect
                                if (completions.keys.any { ownsSendViewport(composerOwner.sendEffectOwners[it], effectOwner) }) {
                                    latestFocusRequest += 1
                                }
                                completions.keys.forEach { composerOwner.sendEffectOwners.remove(it) }
                                setComposer(completions.values.fold(composer) { current, snapshot ->
                                    current.clearAfterSend(snapshot, venue)
                                })
                                completedSendSnapshots = completedSendSnapshots - completions.keys
                                pendingSendIdentities = pendingSendIdentities - completions.keys
                                failedSendIdentities = failedSendIdentities - completions.keys
                                onAcknowledgeCompletedSends(completions.keys)
                            }
                            val keyboard = LocalSoftwareKeyboardController.current
                            val resolver = LocalContext.current.contentResolver
                            fun pickAttachment() = composerOwner.launchFilePicker { uri ->
                                if (uri != null) composerOwner.scope.launch {
                                    val uploaded = try {
                                        val mime = resolver.getType(uri)
                                        val name = slotFilename(uri.lastPathSegment?.substringAfterLast('/') ?: "file", mime)
                                        val bytes = withContext(Dispatchers.IO) {
                                            resolver.openInputStream(uri)?.use { it.readBytes() }
                                        } ?: return@launch
                                        if (!isActive) return@launch
                                        onUploadFile(name, mime, bytes)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        null
                                    } ?: return@launch
                                    if (!isActive) return@launch
                                    val current = composerOwner.composerStates.value[conversationKey] ?: return@launch
                                    val next = if (current.correction == null) {
                                        current.copy(
                                            attachmentUrl = uploaded.url, attachmentName = uploaded.name,
                                            attachmentMime = uploaded.mime, attachmentSize = uploaded.size,
                                            revision = current.revision + 1,
                                            ordinaryRevision = current.ordinaryRevision + 1,
                                        )
                                    } else {
                                        current.copy(
                                            correctionBackup = requireNotNull(current.correctionBackup).copy(
                                                attachmentUrl = uploaded.url, attachmentName = uploaded.name,
                                                attachmentMime = uploaded.mime, attachmentSize = uploaded.size,
                                            ),
                                            ordinaryRevision = current.ordinaryRevision + 1,
                                        )
                                    }
                                    if (next.revision != current.revision) {
                                        failedSendIdentities = failedSendIdentities.filterNot { it.key == conversationKey }.toSet()
                                    }
                                    composerOwner.saveOrdinary(next, venue, onDraftChange)
                                }
                            }
                            var focusComposerWhenReady by remember { mutableStateOf(false) }
                            LaunchedEffect(conversationKey, focusComposerWhenReady) {
                                if (focusComposerWhenReady) {
                                    composerFocus.requestFocus()
                                    keyboard?.show()
                                    focusComposerWhenReady = false
                                }
                            }
                            fun updateComposer(next: ComposerState) {
                                val edited = if (next.correction == null) {
                                    next.copy(ordinaryRevision = next.ordinaryRevision + 1)
                                } else next
                                setComposer(edited)
                                if (edited.correction == null) composerOwner.saveOrdinary(edited, venue, onDraftChange)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val revealProgress = routeRevealProgress.value
                                        translationX = size.width * (
                                            backProgress + routeRevealStart.offsetFraction * (1f - revealProgress)
                                        )
                                        scaleX = routeRevealStart.scale + (1f - routeRevealStart.scale) * revealProgress
                                        scaleY = scaleX
                                    }
                                    .background(
                                        MaterialTheme.colorScheme.background.copy(
                                            alpha = if (backgroundUri == null) 1f else 0.88f,
                                        ),
                                    ),
                            ) {
                                TopAppBar(
                                    modifier = Modifier
                                        .height(64.dp)
                                        .testTag("conversation-top-bar"),
                                    title = {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(role = Role.Button) { showPeerProfile = true }
                                                .semantics { contentDescription = openInfoDescription },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            PeerAvatar(
                                                label = peerLabel,
                                                photoBytes = state.selectedPeerPhotoBytes,
                                                size = 36.dp,
                                                modifier = Modifier.padding(end = 8.dp),
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    peerLabel,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                when {
                                                    status != null -> Text(
                                                        requireNotNull(status),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    state.selectedThread == null && venue is ConversationVenue.Room -> Text(
                                                        roomSubtitle(venue.subject, venue.occupantCount),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    navigationIcon = {
                                        IconButton(
                                            onClick = if (state.selectedThread == null) {
                                                onCloseConversation
                                            } else {
                                                onCloseThread
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = if (state.selectedThread == null) {
                                                    "Back"
                                                } else {
                                                    "Back to conversation"
                                                },
                                            )
                                        }
                                    },
                                    actions = {
                                        IconButton(
                                            onClick = { onVideoCall?.invoke() },
                                            enabled = onVideoCall != null,
                                        ) {
                                            Icon(VideoCallIcon, contentDescription = "Video call")
                                        }
                                        IconButton(
                                            onClick = { onVoiceCall?.invoke() },
                                            enabled = onVoiceCall != null,
                                        ) {
                                            Icon(Icons.Filled.Call, contentDescription = "Voice call")
                                        }
                                        IconButton(onClick = { actionsOpen = true }) {
                                            Icon(Icons.Filled.MoreVert, contentDescription = "Conversation actions")
                                        }
                                        DropdownMenu(
                                            expanded = actionsOpen,
                                            onDismissRequest = { actionsOpen = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(infoTitle) },
                                                onClick = {
                                                    actionsOpen = false
                                                    showPeerProfile = true
                                                },
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    ),
                                    windowInsets = WindowInsets(0, 0, 0, 0),
                                )
                                ThreadSwitcher(
                                    occurrence = state.routeOccurrence,
                                    selected = state.selectedThread,
                                    threads = state.recentThreads,
                                    mainUnreadCount = state.mainUnreadCount,
                                    onSelect = onSelectThreadDestination,
                                    onCreate = onCreateNamedThread,
                                    onCreateShared = onCreateSharedNamedThread,
                                    onRename = onRenameNamedThread,
                                    directory = directoryView,
                                    onRefresh = onRefreshNamedThreads,
                                    onArchive = onArchiveNamedThread,
                                    onRetry = onRetryDirectoryChange,
                                    onKeepCurrent = onKeepCurrentDirectory,
                                )
                                key(conversationKey, state.routeOccurrence) {
                                    val editActionsEnabled = !composer.ordinarySaveUnconfirmed &&
                                        pendingSendIdentities.none { it.key == conversationKey }
                                    MessageTimeline(
                                        messages = state.messages,
                                        olderHistoryStatus = olderHistory.takeIf { it.occurrence == state.routeOccurrence }?.status
                                            ?: OlderHistoryStatus.Available,
                                        onLoadOlder = { onLoadOlder(state.routeOccurrence) },
                                        accountId = state.accountId,
                                        routeOccurrence = state.routeOccurrence,
                                        onMarkVisibleRead = onMarkVisibleRead,
                                        venue = venue,
                                        editActionsEnabled = editActionsEnabled,
                                        readReceiptsEnabled = readReceiptsEnabled,
                                        activityResumed = activityResumed,
                                        typingLabel = state.typingLabel,
                                        onMessageDisplayed = onMessageDisplayed,
                                        onReact = onReact,
                                        latestFocusRequest = latestFocusRequest,
                                        initialViewport = timelineViewports[conversationKey],
                                        onViewportChanged = { anchor ->
                                            timelineViewports[conversationKey] = anchor
                                        },
                                        onContinueThread = { thread -> scope.launch { onContinueThread(thread) } },
                                        onUseAttachment = onUseAttachment,
                                        isAttachmentCached = isAttachmentCached,
                                        onLoadInlineImage = onLoadInlineImage,
                                        onReply = { message ->
                                            val reference = requireNotNull(message.replyReferenceId)
                                            val base = composer.cancelCorrection()
                                            updateComposer(
                                                base.copy(
                                                    reply = DraftReply(
                                                        id = reference,
                                                        to = message.senderJid,
                                                        body = message.body,
                                                        senderLabel = message.senderLabel(),
                                                    ),
                                                    revision = base.revision + 1,
                                                ),
                                            )
                                            focusComposerWhenReady = true
                                        },
                                        onQuote = { message ->
                                            val base = composer.cancelCorrection()
                                            val quote = message.manualQuote()
                                            val answer = base.body.takeIf(String::isNotBlank)
                                            updateComposer(
                                                base.copy(
                                                    body = buildString {
                                                        append(quote).append("\n\n")
                                                        if (answer != null) append(answer)
                                                    },
                                                    revision = base.revision + 1,
                                                ),
                                            )
                                            focusComposerWhenReady = true
                                        },
                                        onEdit = edit@{ message ->
                                            if (!editActionsEnabled) return@edit
                                            val target = requireNotNull(
                                                message.correctionTargetOrNull(venue),
                                            )
                                            setComposer(composer.beginCorrection(target, message.body))
                                            focusComposerWhenReady = true
                                        },
                                        onReplyAsThread = { message ->
                                            setComposer(composer.cancelCorrection())
                                            scope.launch {
                                                val opened = onStartThreadFrom(message)
                                                if (opened) focusComposerWhenReady = true
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .imePadding()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = { pickAttachment() },
                                        enabled = composer.correction == null,
                                        modifier = Modifier.semantics { contentDescription = "Attach file" },
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = null)
                                    }
                                    fun sendWith(action: (DraftSnapshot) -> Deferred<Boolean>) {
                                        val snapshot = composer.toDraftSnapshot(venue)
                                        val identity = PendingSendIdentity(snapshot.key, snapshot.composerRevision)
                                        fun markFailed() {
                                            composerOwner.sendEffectOwners.remove(identity)
                                            val current = composerStates[snapshot.key]
                                                ?: composer.takeIf { it.key == snapshot.key }
                                            val retainedVenue = venueByConversation[snapshot.key] ?: return
                                            if (current?.toDraftSnapshot(retainedVenue) == snapshot) {
                                                failedSendIdentities += identity
                                            }
                                        }
                                        if (identity in pendingSendIdentities) return
                                        pendingSendIdentities += identity
                                        composerOwner.sendEffectOwners[identity] = effectOwner
                                        val send = try {
                                            action(snapshot)
                                        } catch (cancelled: CancellationException) {
                                            pendingSendIdentities -= identity
                                            composerOwner.sendEffectOwners.remove(identity)
                                            throw cancelled
                                        } catch (_: Exception) {
                                            pendingSendIdentities -= identity
                                            markFailed()
                                            return
                                        }
                                        composerOwner.scope.launch {
                                            val applied = try {
                                                send.await()
                                            } catch (cancelled: CancellationException) {
                                                pendingSendIdentities -= identity
                                                composerOwner.sendEffectOwners.remove(identity)
                                                throw cancelled
                                            } catch (_: Exception) {
                                                false
                                            }
                                            if (applied) {
                                                composerOwner.completeSend(snapshot, venue)
                                            } else {
                                                pendingSendIdentities -= identity
                                                markFailed()
                                            }
                                        }
                                    }
                                    val draftSaveIsError = composer.ordinarySaveUnconfirmed && conversationKey !in pendingDraftAttempts
                                    val sendIsError = PendingSendIdentity(conversationKey, composer.revision) in failedSendIdentities
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("message-composer-container")
                                            .semantics {
                                                if (sendIsError) error("Message not sent")
                                            },
                                        shape = RoundedCornerShape(22.dp),
                                        tonalElevation = 1.dp,
                                    ) {
                                        Column {
                                            composer.correction?.let {
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .testTag("composer-edit-preview"),
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(
                                                            "Editing message",
                                                            modifier = Modifier.weight(1f),
                                                            style = MaterialTheme.typography.labelMedium,
                                                        )
                                                        TextButton(onClick = { setComposer(composer.cancelCorrection()) }) {
                                                            Text("Cancel")
                                                        }
                                                    }
                                                }
                                            }
                                            composer.reply?.let { reply ->
                                                Surface(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .testTag("composer-reply-preview"),
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Column(Modifier.weight(1f)) {
                                                            Text(
                                                                "Reply to ${reply.senderLabel}",
                                                                style = MaterialTheme.typography.labelMedium,
                                                            )
                                                            Text(
                                                                reply.body,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        }
                                                        TextButton(
                                                            onClick = {
                                                                updateComposer(
                                                                    composer.copy(
                                                                        reply = null,
                                                                        revision = composer.revision + 1,
                                                                    ),
                                                                )
                                                            },
                                                        ) { Text("Cancel") }
                                                    }
                                                }
                                            }
                                            if (composer.attachmentUrl != null) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth()
                                                        .testTag("composer-attachment-preview")
                                                        .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                                                        Text(
                                                            composer.attachmentName?.takeIf(String::isNotBlank) ?: "Attached file",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        Text(
                                                            composer.attachmentMime?.takeIf(String::isNotBlank) ?: "File",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                    TextButton(
                                                        modifier = Modifier.heightIn(min = 48.dp)
                                                            .semantics { contentDescription = "Remove attachment" },
                                                        onClick = {
                                                            updateComposer(composer.copy(
                                                                attachmentUrl = null,
                                                                attachmentName = null,
                                                                attachmentMime = null,
                                                                attachmentSize = null,
                                                                revision = composer.revision + 1,
                                                            ))
                                                        },
                                                    ) { Text("Remove") }
                                                }
                                            }
                                            BasicTextField(
                                                value = composer.body,
                                                onValueChange = {
                                                    updateComposer(
                                                        composer.copy(
                                                            body = it,
                                                            revision = composer.revision + 1,
                                                        ),
                                                    )
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 44.dp)
                                                    .focusRequester(composerFocus)
                                                    .testTag("message-composer")
                                                    .semantics {
                                                        when {
                                                            sendIsError -> error("Message not sent")
                                                            draftSaveIsError -> error("Draft not saved")
                                                        }
                                                    },
                                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                ),
                                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                maxLines = 5,
                                                decorationBox = { innerTextField ->
                                                    Box(
                                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                                        contentAlignment = Alignment.CenterStart,
                                                    ) {
                                                        if (composer.body.isEmpty()) {
                                                            Text(
                                                                "Message ${destinationTitle(state.selectedThread, state.recentThreads)}",
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                style = MaterialTheme.typography.bodyLarge,
                                                            )
                                                        }
                                                        innerTextField()
                                                    }
                                                },
                                            )
                                            if (sendIsError) {
                                                Text(
                                                    "Message not sent",
                                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                            if (draftSaveIsError) {
                                                Text(
                                                    "Draft not saved",
                                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                                                    color = MaterialTheme.colorScheme.error,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                    Box {
                                        val sendEnabled =
                                            (composer.body.isNotBlank() || composer.attachmentUrl != null) &&
                                                (composer.correction?.let { composer.body != it.originalBody } ?: true) &&
                                                PendingSendIdentity(conversationKey, composer.revision) !in pendingSendIdentities
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clickable(
                                                    enabled = sendEnabled,
                                                    role = Role.Button,
                                                    onClick = { sendWith(onSend) },
                                                )
                                                .semantics { contentDescription = "Send" },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Surface(
                                                modifier = Modifier.size(40.dp),
                                                shape = CircleShape,
                                                color = if (sendEnabled) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                contentColor = if (sendEnabled) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            ) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                                        contentDescription = null,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val VideoCallIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Video call",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17f, 10.5f)
            verticalLineTo(7f)
            curveTo(17f, 6.45f, 16.55f, 6f, 16f, 6f)
            horizontalLineTo(5f)
            curveTo(4.45f, 6f, 4f, 6.45f, 4f, 7f)
            verticalLineTo(17f)
            curveTo(4f, 17.55f, 4.45f, 18f, 5f, 18f)
            horizontalLineTo(16f)
            curveTo(16.55f, 18f, 17f, 17.55f, 17f, 17f)
            verticalLineTo(13.5f)
            lineTo(21f, 17.5f)
            verticalLineTo(6.5f)
            close()
        }
    }.build()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationInfoContent(
    key: DirectConversationKey,
    blockingSession: SessionIdentity?,
    label: String,
    info: ConversationInfo,
    photoBytes: ByteArray?,
    recentThreads: List<RecentThread>,
    onBack: () -> Unit,
    onSaveNickname: suspend (DirectConversationKey, String) -> Boolean,
    onLoadBlocking: suspend (SessionIdentity, DirectConversationKey) -> PeerBlockingState,
    onSetBlocked: suspend (SessionIdentity, DirectConversationKey, Boolean) -> PeerBlockingMutationResult,
    isCurrentBlockingOwner: (SessionIdentity, DirectConversationKey) -> Boolean,
    onSharePeer: ((String) -> Unit)?,
    onOpenThread: (ThreadRef) -> Unit,
    onRenameThread: suspend (RecentThread, String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val directInfo = when (info) {
        is ConversationInfo.DirectContact -> info
        is ConversationInfo.Room -> null
    }
    val infoTagPrefix = when (info) {
        is ConversationInfo.DirectContact -> "contact"
        is ConversationInfo.Room -> "room"
    }

    var nicknameOpen by remember { mutableStateOf(false) }
    var nicknameDraft by remember(directInfo?.localNickname) {
        mutableStateOf(directInfo?.localNickname.orEmpty())
    }
    var nicknameSaving by remember { mutableStateOf(false) }
    var nicknameError by remember { mutableStateOf<String?>(null) }
    var blockingState by remember(key, blockingSession) { mutableStateOf<PeerBlockingState?>(null) }
    var blockingError by remember(key, blockingSession) { mutableStateOf<String?>(null) }
    var blockingMutation by remember { mutableStateOf<Boolean?>(null) }
    var blockingBusy by remember { mutableStateOf(false) }
    var renamingThread by remember(key) { mutableStateOf<RecentThread?>(null) }
    var threadNameDraft by remember(key) { mutableStateOf("") }
    var threadNameSaving by remember(key) { mutableStateOf(false) }
    var threadNameError by remember(key) { mutableStateOf<String?>(null) }

    when (info) {
        is ConversationInfo.DirectContact -> LaunchedEffect(key, blockingSession) {
            blockingState = null
            blockingError = null
            blockingMutation = null
            blockingBusy = false
            val session = blockingSession ?: return@LaunchedEffect
            val loaded = try {
                onLoadBlocking(session, key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentBlockingOwner(session, key)) blockingError = "Blocking unavailable"
                return@LaunchedEffect
            }
            if (isCurrentBlockingOwner(session, key)) blockingState = loaded
        }
        is ConversationInfo.Room -> Unit
    }

    SettingsProfileScreen(
        title = when (info) {
            is ConversationInfo.DirectContact -> "Contact info"
            is ConversationInfo.Room -> "Room info"
        },
        onBack = onBack,
        backContentDescription = "Back to conversation",
        modifier = modifier,
        listTag = "$infoTagPrefix-info-list",
        listModifier = Modifier.navigationBarsPadding(),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .testTag("$infoTagPrefix-profile-header"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PeerAvatar(
                    label = label,
                    photoBytes = photoBytes,
                    size = 80.dp,
                    modifier = Modifier.testTag("$infoTagPrefix-profile-avatar"),
                )
                Text(label, style = MaterialTheme.typography.headlineSmall)
            }
        }
        when (info) {
            is ConversationInfo.DirectContact -> item {
                SettingsRow(
                    title = "Nickname",
                    supportingText = info.localNickname ?: "Not set",
                    onClick = {
                        nicknameDraft = info.localNickname.orEmpty()
                        nicknameError = null
                        nicknameOpen = true
                    },
                    modifier = Modifier.testTag("settings-row-contact-nickname"),
                    role = Role.Button,
                )
            }
            is ConversationInfo.Room -> Unit
        }
        item {
            SettingsRow(
                title = "XMPP address",
                supportingText = info.address,
                modifier = Modifier.testTag("settings-row-$infoTagPrefix-address"),
            )
        }
        onSharePeer?.let { share ->
            item {
                SettingsRow(
                    title = "Share XMPP address",
                    supportingText = "xmpp:${info.address}",
                    onClick = { share(info.address) },
                    modifier = Modifier.testTag("settings-row-$infoTagPrefix-share"),
                    role = Role.Button,
                )
            }
        }
        when (info) {
            is ConversationInfo.DirectContact -> info.remoteProfileName
                ?.takeIf { it.isNotBlank() }
                ?.let { remoteName ->
                    item {
                        SettingsRow(
                            title = "Remote profile",
                            supportingText = remoteName,
                            modifier = Modifier.testTag("settings-row-contact-remote-profile"),
                        )
                    }
                }
            is ConversationInfo.Room -> {
                item {
                    SettingsRow(
                        title = "Subject",
                        supportingText = info.subject?.takeIf { it.isNotBlank() } ?: "Not set",
                        modifier = Modifier.testTag("settings-row-room-subject"),
                    )
                }
                item {
                    SettingsRow(
                        title = "Occupants",
                        supportingText = "${info.occupantCount} occupants",
                        modifier = Modifier.testTag("settings-row-room-occupants"),
                    )
                }
            }
        }
        item {
            SettingsRow(
                title = "Encryption",
                supportingText = "Plaintext",
                modifier = Modifier.testTag("settings-row-$infoTagPrefix-encryption"),
            )
        }
        if (recentThreads.isNotEmpty()) {
            item {
                SettingsSectionHeader(
                    title = "Recent threads",
                    modifier = Modifier.testTag("settings-section-$infoTagPrefix-recent-threads"),
                )
            }
            items(
                items = recentThreads,
                key = { it.thread.draftKey() },
            ) { recent ->
                RecentThreadProfileRow(
                    recent = recent,
                    tag = "settings-row-$infoTagPrefix-thread-${recent.thread.draftKey()}",
                    onOpen = { onOpenThread(recent.thread) },
                    onRename = {
                        threadNameDraft = recent.title
                        threadNameError = null
                        renamingThread = recent
                    },
                )
            }
        }
        when (info) {
            is ConversationInfo.DirectContact -> {
                blockingState?.takeIf(PeerBlockingState::supported)?.let { state ->
                    item {
                        SettingsRow(
                            title = if (state.blocked) "Unblock" else "Block",
                            supportingText = if (state.blocked) {
                                "Blocked by ${state.blockedAddresses.joinToString()}"
                            } else {
                                "Stop messages from this address"
                            },
                            enabled = !blockingBusy,
                            onClick = { blockingMutation = !state.blocked },
                            modifier = Modifier.testTag("settings-row-contact-block"),
                            tone = SettingsRowTone.Danger,
                            role = Role.Button,
                        )
                    }
                }
                blockingError?.let { message ->
                    item {
                        Text(
                            message,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            is ConversationInfo.Room -> Unit
        }
    }

    if (info is ConversationInfo.DirectContact && nicknameOpen) {
        AlertDialog(
            onDismissRequest = { if (!nicknameSaving) nicknameOpen = false },
            title = { Text("Nickname") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nicknameDraft,
                        onValueChange = {
                            nicknameDraft = it
                            nicknameError = null
                        },
                        modifier = Modifier.testTag("nickname-input"),
                        label = { Text("Nickname") },
                        singleLine = true,
                        isError = nicknameError != null,
                    )
                    nicknameError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !nicknameSaving,
                    onClick = {
                        nicknameSaving = true
                        scope.launch {
                            val saved = try {
                                onSaveNickname(key, nicknameDraft)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            nicknameSaving = false
                            if (saved) {
                                nicknameOpen = false
                            } else {
                                nicknameError = "Nickname was not saved"
                            }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    enabled = !nicknameSaving,
                    onClick = { nicknameOpen = false },
                ) { Text("Cancel") }
            },
        )
    }

    renamingThread?.let { recent ->
        AlertDialog(
            onDismissRequest = { if (!threadNameSaving) renamingThread = null },
            title = { Text("Thread name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = threadNameDraft,
                        onValueChange = {
                            threadNameDraft = it
                            threadNameError = null
                        },
                        modifier = Modifier.testTag("thread-name-input"),
                        label = { Text("Name") },
                        supportingText = { Text("Use a non-empty name. Names are saved on this device only.") },
                        singleLine = true,
                        isError = threadNameError != null,
                    )
                    threadNameError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !threadNameSaving,
                    onClick = {
                        threadNameSaving = true
                        scope.launch {
                            val saved = try {
                                onRenameThread(recent, threadNameDraft)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            threadNameSaving = false
                            if (saved) {
                                renamingThread = null
                            } else {
                                threadNameError = "Thread name was not saved"
                            }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(
                    enabled = !threadNameSaving,
                    onClick = { renamingThread = null },
                ) { Text("Cancel") }
            },
        )
    }

    blockingMutation?.takeIf { info is ConversationInfo.DirectContact }?.let { block ->
        val blockedAddresses = blockingState?.blockedAddresses.orEmpty()
        val unblocksDomain = !block && blockedAddresses.any { it != key.canonicalBarePeer }
        AlertDialog(
            onDismissRequest = { if (!blockingBusy) blockingMutation = null },
            title = {
                Text(
                    when {
                        block -> "Block contact?"
                        unblocksDomain -> "Unblock domain?"
                        else -> "Unblock contact?"
                    },
                )
            },
            text = {
                Text(
                    when {
                        block -> {
                            "Messages from ${key.canonicalBarePeer} will be blocked by your XMPP server."
                        }
                        unblocksDomain -> {
                            "Blocking rules for ${blockedAddresses.joinToString()} will be removed. " +
                                "This may allow messages from other addresses at the same domain."
                        }
                        else -> {
                            "Messages from ${key.canonicalBarePeer} will be allowed again."
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !blockingBusy,
                    modifier = Modifier.testTag("confirm-block"),
                    onClick = {
                        val operationSession = blockingSession ?: return@TextButton
                        val operationKey = key
                        blockingBusy = true
                        scope.launch {
                            val result = try {
                                onSetBlocked(operationSession, operationKey, block)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                PeerBlockingMutationResult.Uncertain
                            }
                            if (!isCurrentBlockingOwner(operationSession, operationKey)) return@launch
                            blockingBusy = false
                            blockingMutation = null
                            when (result) {
                                is PeerBlockingMutationResult.Confirmed -> {
                                    blockingState = result.state
                                    blockingError = null
                                }
                                PeerBlockingMutationResult.Rejected -> {
                                    blockingError = "XMPP server rejected the request"
                                }
                                PeerBlockingMutationResult.NotAttempted -> {
                                    blockingError = "Block request was not sent"
                                }
                                PeerBlockingMutationResult.Uncertain -> {
                                    blockingState = null
                                    blockingError = "Block outcome unknown. Reopen contact info to refresh."
                                }
                            }
                        }
                    },
                ) { Text(if (block) "Block" else "Unblock") }
            },
            dismissButton = {
                TextButton(
                    enabled = !blockingBusy,
                    onClick = { blockingMutation = null },
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RecentThreadProfileRow(
    recent: RecentThread,
    tag: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
) {
    SettingsRow(
        title = recent.title,
        supportingText = "Replies ${recent.replyCount}",
        onClick = onOpen,
        modifier = Modifier.testTag(tag),
        role = Role.Button,
        trailingContent = {
            TextButton(
                onClick = onRename,
                modifier = Modifier.testTag("rename-thread-${recent.thread.draftKey()}"),
            ) { Text("Rename") }
        },
    )
}

@Composable
private fun ChatBackground(uri: String?) {
    if (uri == null) return
    val resolver = LocalContext.current.contentResolver
    val image by produceState<ImageBitmap?>(initialValue = null, uri, resolver) {
        value = withContext(Dispatchers.IO) {
            try {
                decodeBackground(resolver, Uri.parse(uri))?.asImageBitmap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

private suspend fun decodeBackground(resolver: ContentResolver, uri: Uri) =
    BitmapFactory.Options().run {
        inJustDecodeBounds = true
        resolver.openInputStream(uri)?.useCancellable { BitmapFactory.decodeStream(it, null, this) }
        if (outWidth <= 0 || outHeight <= 0) return@run null
        inSampleSize = 1
        while (outWidth / inSampleSize > MAX_BACKGROUND_EDGE ||
            outHeight / inSampleSize > MAX_BACKGROUND_EDGE
        ) {
            inSampleSize *= 2
        }
        inJustDecodeBounds = false
        resolver.openInputStream(uri)?.useCancellable { BitmapFactory.decodeStream(it, null, this) }
    }

internal suspend fun <T> InputStream.useCancellable(block: (InputStream) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { close() } }
        try {
            val result = use(block)
            if (continuation.isActive) continuation.resume(result)
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }

private const val MAX_BACKGROUND_EDGE = 2048
private const val NEAR_LATEST_ITEM_THRESHOLD = 1

/** Account-owned provisional input; this owner must outlive primary destinations. */
class ComposerOwner internal constructor(internal val scope: kotlinx.coroutines.CoroutineScope) {
    internal lateinit var launchFilePicker: ((Uri?) -> Unit) -> Unit
    internal val composerStates = mutableStateOf(emptyMap<DirectConversationKey, ComposerState>())
    internal val pendingSendIdentities = mutableStateOf(emptySet<PendingSendIdentity>())
    internal val completedSendSnapshots = mutableStateOf(emptyMap<PendingSendIdentity, DraftSnapshot>())
    internal val failedSendIdentities = mutableStateOf(emptySet<PendingSendIdentity>())
    internal val pendingDraftAttempts = mutableStateOf(emptyMap<DirectConversationKey, Any>())
    internal val venueByConversation = mutableMapOf<DirectConversationKey, ConversationVenue>()
    internal val sendEffectOwners = mutableMapOf<PendingSendIdentity, ConversationEffectOwner>()

    internal fun completeSend(snapshot: DraftSnapshot, venue: ConversationVenue) {
        val identity = PendingSendIdentity(snapshot.key, snapshot.composerRevision)
        // Settle saveable input before queuing route-only focus/viewport effects.
        // Home may be absent until after this owner and the presenter are recreated.
        composerStates.value[snapshot.key]?.let { current ->
            composerStates.value += snapshot.key to current.clearAfterSend(snapshot, venue)
        }
        failedSendIdentities.value -= identity
        completedSendSnapshots.value += identity to snapshot
    }

    internal fun saveOrdinary(
        next: ComposerState,
        venue: ConversationVenue,
        onDraftChange: (DraftSnapshot) -> Deferred<Boolean>,
    ) {
        if (!scope.coroutineContext.isActive) return
        val token = Any()
        composerStates.value += next.key to next.copy(ordinarySaveUnconfirmed = true)
        pendingDraftAttempts.value += next.key to token
        fun settle(applied: Boolean) {
            if (!scope.coroutineContext.isActive || pendingDraftAttempts.value[next.key] !== token) return
            pendingDraftAttempts.value -= next.key
            val current = composerStates.value[next.key] ?: return
            if (current.ordinaryRevision != next.ordinaryRevision) return
            composerStates.value += next.key to current.copy(ordinarySaveUnconfirmed = !applied)
        }
        val action = try {
            onDraftChange(next.ordinarySnapshot(venue))
        } catch (_: Exception) {
            settle(false)
            return
        }
        scope.launch {
            val applied = try {
                action.await()
            } catch (cancelled: CancellationException) {
                settle(false)
                throw cancelled
            } catch (_: Exception) {
                false
            }
            settle(applied)
        }
    }
}

@Composable
fun rememberComposerOwner(accountId: String): ComposerOwner = key(accountId) {
    val scope = rememberCoroutineScope()
    val owner = rememberSaveable(accountId, saver = Saver<ComposerOwner, Any>(
        save = { owner ->
            owner.composerStates.value.values.map { composer ->
                with(composerStateSaver(composer.key)) { save(mutableStateOf(composer)) }
            }
        },
        restore = { saved ->
            val entries = (saved as? List<*>)?.mapNotNull { row ->
                row?.let { composerStateSaver(null).restore(it)?.value }
                    ?.takeIf { it.key.accountId == accountId }
            }.orEmpty()
            ComposerOwner(scope).also { owner ->
                owner.composerStates.value = entries.associateBy { it.key }
            }
        },
    )) { ComposerOwner(scope) }
    // A recreated owner gets a new registration: old results cannot target a new request.
    key(owner) {
        var pending by remember { mutableStateOf<((Uri?) -> Unit)?>(null) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val complete = pending
            pending = null
            if (scope.coroutineContext.isActive) complete?.invoke(uri)
        }
        owner.launchFilePicker = { complete ->
            if (scope.coroutineContext.isActive && pending == null) {
                pending = complete
                try {
                    picker.launch("*/*")
                } catch (_: Exception) {
                    pending = null
                }
            }
        }
    }
    owner
}

private const val COMPOSER_STATE_VERSION = 5

internal data class ComposerState(
    val key: DirectConversationKey,
    val body: String,
    val revision: Long,
    val ordinarySaveUnconfirmed: Boolean = false,
    val ordinaryRevision: Long = revision,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val reply: DraftReply? = null,
    val correction: DraftCorrection? = null,
    val correctionBackup: ComposerBackup? = null,
) {
    internal fun ordinarySnapshot(venue: ConversationVenue): DraftSnapshot =
        (if (correction == null) this else cancelCorrection()).toDraftSnapshot(venue)
            .copy(composerRevision = ordinaryRevision)

    fun toDraftSnapshot(venue: ConversationVenue): DraftSnapshot = DraftSnapshot(
        key = key,
        body = body,
        composerRevision = revision,
        groupChat = when (venue) {
            ConversationVenue.Direct -> false
            is ConversationVenue.Room -> true
        },
        attachmentUrl = attachmentUrl,
        attachmentName = attachmentName,
        attachmentMime = attachmentMime,
        attachmentSize = attachmentSize,
        reply = reply,
        correction = correction,
    )
}

internal data class ComposerBackup(
    val body: String,
    val attachmentUrl: String?,
    val attachmentName: String?,
    val attachmentMime: String?,
    val attachmentSize: Long?,
    val reply: DraftReply?,
)

internal fun ComposerState.beginCorrection(target: DraftCorrection, correctedBody: String): ComposerState {
    val backup = correctionBackup ?: ComposerBackup(
        body = body,
        attachmentUrl = attachmentUrl,
        attachmentName = attachmentName,
        attachmentMime = attachmentMime,
        attachmentSize = attachmentSize,
        reply = reply,
    )
    return copy(
        body = correctedBody,
        attachmentUrl = null,
        attachmentName = null,
        attachmentMime = null,
        attachmentSize = null,
        reply = null,
        correction = target,
        correctionBackup = backup,
        revision = revision + 1,
    )
}

internal fun ComposerState.cancelCorrection(): ComposerState {
    val backup = correctionBackup ?: return copy(correction = null, correctionBackup = null)
    return copy(
        body = backup.body,
        attachmentUrl = backup.attachmentUrl,
        attachmentName = backup.attachmentName,
        attachmentMime = backup.attachmentMime,
        attachmentSize = backup.attachmentSize,
        reply = backup.reply,
        correction = null,
        correctionBackup = null,
        revision = revision + 1,
    )
}

internal fun ComposerState.clearAfterSend(snapshot: DraftSnapshot, venue: ConversationVenue): ComposerState =
    if (toDraftSnapshot(venue) == snapshot) {
        if (snapshot.correction != null) cancelCorrection() else copy(
            body = "",
            attachmentUrl = null,
            attachmentName = null,
            attachmentMime = null,
            attachmentSize = null,
            reply = null,
            revision = revision + 1,
            ordinaryRevision = ordinaryRevision + 1,
            ordinarySaveUnconfirmed = false,
        )
    } else {
        this
    }

internal fun composerStateSaver(
    expectedKey: DirectConversationKey?,
): Saver<MutableState<ComposerState>, Any> = Saver(
    save = { holder ->
        val state = holder.value
        arrayListOf(
            COMPOSER_STATE_VERSION,
            state.key.accountId,
            state.key.canonicalBarePeer,
            state.key.thread?.id?.value,
            state.key.thread?.parentId?.value,
            state.body,
            state.revision,
            state.ordinarySaveUnconfirmed,
            state.attachmentUrl,
            state.attachmentName,
            state.attachmentMime,
            state.attachmentSize,
            state.reply?.id,
            state.reply?.to,
            state.reply?.body,
            state.reply?.senderLabel,
            state.correction?.localMessageId,
            state.correction?.referenceId,
            state.correction?.originalBody,
            state.correctionBackup?.body,
            state.correctionBackup?.attachmentUrl,
            state.correctionBackup?.attachmentName,
            state.correctionBackup?.attachmentMime,
            state.correctionBackup?.attachmentSize,
            state.correctionBackup?.reply?.id,
            state.correctionBackup?.reply?.to,
            state.correctionBackup?.reply?.body,
            state.correctionBackup?.reply?.senderLabel,
            state.ordinaryRevision,
        )
    },
    restore = restore@{ saved ->
        val values = saved as? List<*> ?: return@restore null
        val legacy = values.size == 28 && values[0] == 4
        if (!legacy && (values.size != 29 || values[0] != COMPOSER_STATE_VERSION)) return@restore null
        val accountId = values[1] as? String ?: return@restore null
        val peer = values[2] as? String ?: return@restore null
        if ((3..4).any { values[it] != null && values[it] !is String }) return@restore null
        val threadId = ThreadId.parse(values[3] as String?)
        val parentId = ThreadId.parse(values[4] as String?)
        if ((values[3] != null && threadId == null) ||
            (values[4] != null && parentId == null) ||
            (threadId == null && parentId != null) ||
            (threadId != null && threadId == parentId)
        ) {
            return@restore null
        }
        val body = values[5] as? String ?: return@restore null
        val revision = values[6] as? Long ?: return@restore null
        if (revision < 0) return@restore null
        val unconfirmed = if (legacy) {
            if (values[7] != null && values[7] != revision) return@restore null
            true
        } else values[7] as? Boolean ?: return@restore null
        val ordinaryRevision = if (legacy) revision else values[28] as? Long ?: return@restore null
        if (ordinaryRevision < 0) return@restore null
        if ((8..10).any { values[it] != null && values[it] !is String }) return@restore null
        if (values[11] != null && values[11] !is Long) return@restore null
        if ((12..15).any { values[it] != null && values[it] !is String }) return@restore null
        val replyId = values[12] as String?
        val replyTo = values[13] as String?
        val replyBody = values[14] as String?
        val replySender = values[15] as String?
        if (replyId == null && listOf(replyTo, replyBody, replySender).any { it != null }) return@restore null
        if (replyId != null && (replyBody == null || replySender == null)) return@restore null
        if ((16..18).any { values[it] != null && values[it] !is String }) return@restore null
        val correctionLocalId = values[16] as String?
        val correctionReferenceId = values[17] as String?
        val correctionOriginalBody = values[18] as String?
        if (listOf(correctionLocalId, correctionReferenceId, correctionOriginalBody).map { it == null }.distinct().size != 1) {
            return@restore null
        }
        if ((19..22).any { values[it] != null && values[it] !is String }) return@restore null
        if (values[23] != null && values[23] !is Long) return@restore null
        if ((24..27).any { values[it] != null && values[it] !is String }) return@restore null
        val backupBody = values[19] as String?
        val backupReplyId = values[24] as String?
        val backupReplyTo = values[25] as String?
        val backupReplyBody = values[26] as String?
        val backupReplySender = values[27] as String?
        if (backupReplyId == null && listOf(backupReplyTo, backupReplyBody, backupReplySender).any { it != null }) {
            return@restore null
        }
        if (backupReplyId != null && (backupReplyBody == null || backupReplySender == null)) return@restore null
        if ((correctionLocalId == null) != (backupBody == null)) return@restore null
        val key = DirectConversationKey(
            accountId = accountId,
            canonicalBarePeer = peer,
            thread = threadId?.let { ThreadRef(it, parentId) },
        )
        if (expectedKey != null && key != expectedKey) return@restore null
        mutableStateOf(
            ComposerState(
                key = key,
                body = body,
                revision = revision,
                ordinarySaveUnconfirmed = unconfirmed,
                ordinaryRevision = ordinaryRevision,
                attachmentUrl = values[8] as String?,
                attachmentName = values[9] as String?,
                attachmentMime = values[10] as String?,
                attachmentSize = values[11] as Long?,
                reply = replyId?.let {
                    DraftReply(it, replyTo, requireNotNull(replyBody), requireNotNull(replySender))
                },
                correction = correctionLocalId?.let {
                    DraftCorrection(it, requireNotNull(correctionReferenceId), requireNotNull(correctionOriginalBody))
                },
                correctionBackup = backupBody?.let {
                    ComposerBackup(
                        body = it,
                        attachmentUrl = values[20] as String?,
                        attachmentName = values[21] as String?,
                        attachmentMime = values[22] as String?,
                        attachmentSize = values[23] as Long?,
                        reply = backupReplyId?.let { id ->
                            DraftReply(
                                id,
                                backupReplyTo,
                                requireNotNull(backupReplyBody),
                                requireNotNull(backupReplySender),
                            )
                        },
                    )
                },
            ),
        )
    },
)

internal fun displayedMarkerCandidates(
    messages: List<TimelineMessage>,
    visibleMessageIds: Set<String>,
    enabled: Boolean,
    resumed: Boolean,
    venue: ConversationVenue,
): List<TimelineMessage> = TimelineReadIndex(messages).displayedMarkerCandidates(
    visibleMessageIds, enabled, resumed, venue,
)

internal fun TimelineMessage.correctionTargetOrNull(venue: ConversationVenue): DraftCorrection? {
    val referenceId = correctionReferenceId?.takeIf(String::isNotEmpty) ?: return null
    if (venue is ConversationVenue.Room || groupChat || !outgoing || body.isBlank()) return null
    if (attachmentUrl != null || attachmentName != null || attachmentMime != null || attachmentSize != null) return null
    if (replyToId != null || replyToJid != null || replyFallbackBody != null) return null
    if (delivery !in setOf(
            DeliveryPresentation.SENT,
            DeliveryPresentation.CONFIRMED,
            DeliveryPresentation.DELIVERED,
            DeliveryPresentation.READ,
        )
    ) return null
    return DraftCorrection(id, referenceId, body)
}

internal fun canReact(venue: ConversationVenue, message: TimelineMessage): Boolean = when (venue) {
    ConversationVenue.Direct -> !message.groupChat
    is ConversationVenue.Room -> message.groupChat && message.replyReferenceId != null
}

internal fun canReact(conversationIsGroupChat: Boolean, message: TimelineMessage): Boolean = canReact(
    venue = if (conversationIsGroupChat) ConversationVenue.Room(null, 0) else ConversationVenue.Direct,
    message = message,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageTimeline(
    messages: List<TimelineMessage>,
    olderHistoryStatus: OlderHistoryStatus? = null,
    onLoadOlder: suspend () -> Unit = {},
    accountId: String = "",
    routeOccurrence: ChatRouteOccurrence = ChatRouteOccurrence(null, 0),
    onMarkVisibleRead: suspend (VisibleReadRequest) -> Boolean = { true },
    venue: ConversationVenue = ConversationVenue.Direct,
    editActionsEnabled: Boolean = true,
    readReceiptsEnabled: Boolean = false,
    activityResumed: Boolean = false,
    onMessageDisplayed: suspend (TimelineMessage) -> Boolean = { false },
    onReact: suspend (TimelineMessage, String) -> Boolean = { _, _ -> false },
    latestFocusRequest: Long = 0L,
    initialViewport: TimelineViewportAnchor? = null,
    onViewportChanged: (TimelineViewportAnchor) -> Unit = {},
    onContinueThread: (ThreadRef) -> Unit = {},
    onReply: (TimelineMessage) -> Unit = {},
    onQuote: (TimelineMessage) -> Unit = {},
    onEdit: (TimelineMessage) -> Unit = {},
    onReplyAsThread: (TimelineMessage) -> Unit = {},
    onUseAttachment: suspend (String, String?, String?) -> Boolean = { _, _, _ -> false },
    isAttachmentCached: (String) -> Boolean = { false },
    onLoadInlineImage: suspend (String) -> ImageBitmap? = { null },
    typingLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var reactionPickerMessageId by remember { mutableStateOf<String?>(null) }
    var reactionPickerShown by remember { mutableStateOf(false) }
    val currentOnViewportChanged by rememberUpdatedState(onViewportChanged)
    val currentOnMessageDisplayed by rememberUpdatedState(onMessageDisplayed)
    val currentTypingPresent by rememberUpdatedState(typingLabel != null)
    val nearLatest by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex <= NEAR_LATEST_ITEM_THRESHOLD
        }
    }
    var previousLatestId by remember { mutableStateOf<String?>(null) }
    var newIncoming by remember { mutableIntStateOf(0) }
    var followLatest by remember { mutableStateOf(true) }
    var viewportRestored by remember { mutableStateOf(initialViewport == null) }
    var readObservation by remember(accountId, routeOccurrence) {
        mutableStateOf(emptySet<String>() to emptyList<String>())
    }
    val visibleMessageIds = readObservation.first
    val observedTimelineIds = readObservation.second
    var reportedMarkerTargets by remember { mutableStateOf(emptySet<String>()) }
    val currentMessages = remember {
        mutableStateOf(messages, referentialEqualityPolicy())
    }
    currentMessages.value = messages
    val readIndex by remember { derivedStateOf { TimelineReadIndex(currentMessages.value) } }
    val currentReadIndex by rememberUpdatedState(readIndex)
    val latestId = messages.lastOrNull()?.id
    LaunchedEffect(initialViewport, viewportRestored, messages.isNotEmpty()) {
        if (!viewportRestored) {
            val anchor = requireNotNull(initialViewport)
            // This composition exists only for Ready, including an authoritative empty result.
            if (currentMessages.value.isNotEmpty()) {
                val target = restoredTimelineTarget(currentMessages.value, anchor, typingLabel != null)
                listState.scrollToItem(target.index, target.offset)
            }
            viewportRestored = true
        }
    }
    LaunchedEffect(listState, viewportRestored, messages.isEmpty()) {
        if (!viewportRestored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                val reversed = currentMessages.value.asReversed()
                if (reversed.isEmpty()) return@collect
                val messageIndex = timelineMessageIndex(index, currentTypingPresent, reversed.size)
                currentOnViewportChanged(TimelineViewportAnchor(reversed[messageIndex].id, offset, messageIndex))
            }
    }
    LaunchedEffect(listState, viewportRestored, accountId, routeOccurrence) {
        if (!viewportRestored) return@LaunchedEffect
        withFrameNanos { }
        snapshotFlow {
            val layout = listState.layoutInfo
            val index = currentReadIndex
            val visible = layout.visibleItemsInfo.asSequence()
                .filter { it.offset + it.size > layout.viewportStartOffset && it.offset < layout.viewportEndOffset }
                .mapNotNull { it.key as? String }
                .filter { index.contains(it) }
                .toSet()
            visible to index.orderedIds
        }.distinctUntilChanged().collect { readObservation = it }
    }
    // History-only insertions do not admit another read until visibility or lifecycle changes.
    LaunchedEffect(accountId, routeOccurrence, visibleMessageIds, viewportRestored, activityResumed) {
        if (viewportRestored && activityResumed && visibleMessageIds.isNotEmpty()) {
            onMarkVisibleRead(VisibleReadRequest(accountId, routeOccurrence, visibleMessageIds, observedTimelineIds))
        }
    }
    LaunchedEffect(
        visibleMessageIds,
        viewportRestored,
        readReceiptsEnabled,
        activityResumed,
        venue.messageKind,
        latestId,
        readIndex.markerSignature,
    ) {
        if (!viewportRestored) return@LaunchedEffect
        currentReadIndex.displayedMarkerCandidates(
            visibleMessageIds = visibleMessageIds,
            enabled = readReceiptsEnabled,
            resumed = activityResumed,
            venue = venue,
        ).forEach { message ->
            val target = requireNotNull(message.markerTargetId)
            if (target !in reportedMarkerTargets) {
                val sent = try {
                    currentOnMessageDisplayed(message)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (sent) reportedMarkerTargets += target
            }
        }
    }
    LaunchedEffect(listState, viewportRestored) {
        if (!viewportRestored) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    followLatest = listState.firstVisibleItemIndex <= NEAR_LATEST_ITEM_THRESHOLD
                }
            }
    }
    LaunchedEffect(nearLatest) {
        if (nearLatest) newIncoming = 0
    }
    LaunchedEffect(latestFocusRequest) {
        if (latestFocusRequest > 0L && messages.isNotEmpty()) {
            listState.scrollToItem(0)
            newIncoming = 0
        }
    }
    LaunchedEffect(typingLabel) {
        if (viewportRestored && typingLabel != null && followLatest) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(latestId) {
        val previous = previousLatestId
        if (previous != null) {
            val appended = appendedMessages(previous, messages)
            if (followLatest) {
                if (appended.isNotEmpty()) listState.scrollToItem(0)
                newIncoming = 0
            } else {
                newIncoming += appended.count { !it.outgoing }
            }
        }
        previousLatestId = latestId
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .fillMaxSize()
                .testTag("message-timeline"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (typingLabel != null) {
                item(key = "typing-indicator", contentType = "typing") {
                    Text(
                        typingLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("typing-indicator"),
                    )
                }
            }
            items(
                items = messages.asReversed(),
                key = TimelineMessage::id,
                contentType = { message -> if (message.outgoing) 1 else 0 },
            ) { message ->
                var messageActionsOpen by remember(message.id) { mutableStateOf(false) }
                val reactable = canReact(venue, message)
                val (bubbleContainerColor, bubbleContentColor) = messageBubbleColors(message.outgoing)
                val visibleBody = remember(message.body, message.attachmentUrl) {
                    if (message.attachmentUrl == null) {
                        message.body
                    } else {
                        attachmentBodyCaption(message.body, message.attachmentUrl) ?: ""
                    }
                }
                val segments = remember(visibleBody) { parseQuotedBody(visibleBody) }
                val correctionTarget = message.correctionTargetOrNull(venue)
                    .takeIf { editActionsEnabled }
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .align(if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(
                                start = if (message.outgoing) 32.dp else 0.dp,
                                end = if (message.outgoing) 0.dp else 32.dp,
                            ),
                    ) {
                        Surface(
                            modifier = Modifier
                                .testTag("message-bubble-${message.id}")
                                .semantics {
                                    onClick(label = "Message actions") {
                                        messageActionsOpen = true
                                        true
                                    }
                                }
                                .combinedClickable(
                                    role = Role.Button,
                                    onLongClickLabel = "Message actions",
                                    onClick = {},
                                    onDoubleClick = { messageActionsOpen = true },
                                    onLongClick = { messageActionsOpen = true },
                                ),
                            color = bubbleContainerColor,
                            contentColor = bubbleContentColor,
                            shape = RoundedCornerShape(
                                topStart = 18.dp,
                                topEnd = 18.dp,
                                bottomStart = if (message.outgoing) 18.dp else 4.dp,
                                bottomEnd = if (message.outgoing) 4.dp else 18.dp,
                            ),
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                if (message.groupChat && !message.outgoing) {
                                    val nick = message.senderJid.substringAfterLast('/').ifEmpty { message.senderJid }
                                    val nickColor = remember(nick, bubbleContainerColor) {
                                        Color(mucNickColor(nick, bubbleContainerColor.toArgb()))
                                    }
                                    Text(
                                        nick,
                                        color = nickColor,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                message.attachmentUrl?.let { url ->
                                    val resolvedMime = resolvedAttachmentMime(
                                        message.attachmentMime,
                                        message.attachmentName,
                                        url,
                                    )
                                    MessageAttachment(
                                        url = url,
                                        name = message.attachmentName,
                                        mime = resolvedMime,
                                        groupChat = venue is ConversationVenue.Room || message.groupChat,
                                        isCached = { isAttachmentCached(url) },
                                        onUse = {
                                            onUseAttachment(url, message.attachmentName, resolvedMime)
                                        },
                                        onLoadInline = { onLoadInlineImage(url) },
                                    )
                                }
                                message.reply?.let { reply ->
                                    MessageReplyPreview(reply.senderLabel, reply.body)
                                }
                                if (visibleBody.isNotEmpty() && segments.none { it is BodySegment.Quote }) {
                                    LinkedMessageText(visibleBody, style = MaterialTheme.typography.bodyMedium)
                                } else if (visibleBody.isNotEmpty()) {
                                    segments.forEach { segment ->
                                        when (segment) {
                                            is BodySegment.Plain ->
                                                segment.text.takeIf(String::isNotEmpty)?.let {
                                                    LinkedMessageText(it, style = MaterialTheme.typography.bodyMedium)
                                                }
                                            is BodySegment.Quote ->
                                                ManualQuoteBlock(segment.text, segment.depth)
                                        }
                                    }
                                }
                                if (message.edited) {
                                    Text("Edited", style = MaterialTheme.typography.labelSmall)
                                }
                                if (venue is ConversationVenue.Direct && !message.groupChat) {
                                    message.threadSummaries.forEach { summary ->
                                        ThreadSummaryButton(
                                            summary = summary,
                                            onClick = { onContinueThread(summary.thread) },
                                        )
                                    }
                                }
                                message.delivery?.visibleLabel()?.let { label ->
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (message.delivery == DeliveryPresentation.UNCERTAIN) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            androidx.compose.ui.graphics.Color.Unspecified
                                        },
                                    )
                                }
                                val check = message.delivery?.receiptCheck(bubbleContainerColor.toArgb())
                                if (message.sentAtEpochMs != null || check != null) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.End)
                                            .testTag("message-status"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        check?.let { receipt ->
                                            Text(
                                                "\u2713",
                                                color = receipt.color,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.clearAndSetSemantics {
                                                    contentDescription = receipt.description
                                                },
                                            )
                                        }
                                        message.sentAtEpochMs?.let { sentAt ->
                                            Text(
                                                formatMessageTime(sentAt),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (message.reactions.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .testTag("reaction-row-${message.id}"),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                message.reactions.forEach { chip ->
                                    Text(
                                        "${chip.emoji} ${chip.count}",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier
                                            .testTag("reaction-chip-${message.id}-${chip.emoji}")
                                            .clickable(enabled = reactable) {
                                                scope.launch { onReact(message, chip.emoji) }
                                            },
                                    )
                                }
                            }
                        }
                        if (reactionPickerMessageId == message.id && reactable) {
                            Popup(
                                onDismissRequest = { reactionPickerShown = false },
                                properties = PopupProperties(
                                    focusable = true,
                                    dismissOnBackPress = true,
                                    dismissOnClickOutside = true,
                                ),
                            ) {
                                AnimatedVisibility(
                                    visible = reactionPickerShown,
                                    enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.88f, animationSpec = tween(180)),
                                    exit = fadeOut(tween(110)) + scaleOut(targetScale = 0.88f, animationSpec = tween(150)),
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        tonalElevation = 6.dp,
                                        modifier = Modifier.testTag("reaction-picker"),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            org.thanosapollo.nema.xmpp.reactions.DEFAULT_REACTION_CHOICES.forEach { emoji ->
                                                Text(
                                                    emoji,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    modifier = Modifier.clickable {
                                                        reactionPickerShown = false
                                                        reactionPickerMessageId = null
                                                        scope.launch { onReact(message, emoji) }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                LaunchedEffect(reactionPickerShown) {
                                    if (!reactionPickerShown) {
                                        delay(160)
                                        reactionPickerMessageId = null
                                    }
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = messageActionsOpen,
                            onDismissRequest = { messageActionsOpen = false },
                        ) {
                            if (message.replyReferenceId != null) {
                                DropdownMenuItem(
                                    text = { Text("Reply") },
                                    onClick = {
                                        messageActionsOpen = false
                                        onReply(message)
                                    },
                                )
                                if (venue is ConversationVenue.Direct && !message.groupChat) {
                                    DropdownMenuItem(
                                        text = { Text("Reply as a thread") },
                                        onClick = {
                                            messageActionsOpen = false
                                            onReplyAsThread(message)
                                        },
                                    )
                                }
                            }
                            if (correctionTarget != null) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = {
                                        messageActionsOpen = false
                                        onEdit(message)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Quote") },
                                onClick = {
                                    messageActionsOpen = false
                                    onQuote(message)
                                },
                            )
                            if (reactable) {
                                DropdownMenuItem(
                                    text = { Text("Reactions") },
                                    onClick = {
                                        messageActionsOpen = false
                                        reactionPickerMessageId = message.id
                                        reactionPickerShown = true
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (olderHistoryStatus != null) {
                item(key = "older-history", contentType = "older-history") {
                    TextButton(
                        onClick = { scope.launch { onLoadOlder() } },
                        enabled = olderHistoryStatus != OlderHistoryStatus.Pending &&
                            olderHistoryStatus != OlderHistoryStatus.Exhausted,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("load-older-history"),
                    ) {
                        Text(when (olderHistoryStatus) {
                            OlderHistoryStatus.Pending -> "Loading older messages…"
                            OlderHistoryStatus.Error -> "Could not load older messages · Retry"
                            OlderHistoryStatus.Exhausted -> "Beginning of archive"
                            else -> "Load older messages"
                        })
                    }
                }
            }
        }
        if (reactionPickerMessageId != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("reaction-picker-dismiss")
                    .clickable { reactionPickerShown = false },
            )
        }
        if (!nearLatest) {
            SmallFloatingActionButton(
                onClick = {
                    newIncoming = 0
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            ) {
                Box {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Jump to latest messages",
                    )
                    if (newIncoming > 0) {
                        Badge(modifier = Modifier.align(Alignment.TopEnd)) {
                            Text("+$newIncoming")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadSummaryButton(
    summary: ThreadSummary,
    onClick: () -> Unit,
) {
    val replies = threadSummaryLabel(summary.replyCount)
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val accent = readableOn(
        MaterialTheme.colorScheme.primary.toArgb(),
        fill.toArgb(),
    ).toComposeColor()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = 5.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription =
                    "Open thread with ${summary.replyCount} replies. Latest: ${summary.latestPreview}"
            }
            .testTag("thread-summary-${summary.thread.draftKey()}"),
        color = fill,
        border = BorderStroke(1.dp, accent),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    replies,
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    summary.latestPreview,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = accent,
            )
        }
    }
}

@Composable
private fun MessageReplyPreview(senderLabel: String, body: String) {
    Surface(
        modifier = Modifier
            .widthIn(min = 160.dp, max = 280.dp)
            .padding(bottom = 4.dp)
            .testTag("message-reply-preview"),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                "Reply to $senderLabel",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ManualQuoteBlock(body: String, depth: Int = 1) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ((depth - 1).coerceAtLeast(0) * 8).dp, bottom = 4.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("message-quote-block"),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .heightIn(min = 28.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
        LinkedMessageText(
            body,
            modifier = Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LinkedMessageText(
    body: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val annotated = remember(body) { linkedMessageBody(body) }
    Text(text = annotated, modifier = modifier, color = color, style = style)
}

internal fun linkedMessageBody(body: String) = buildAnnotatedString {
    parseHttpLinks(body).forEach { run ->
        when (run) {
            is TextRun.Plain -> append(run.text)
            is TextRun.Url -> {
                val start = length
                append(run.text)
                addLink(LinkAnnotation.Url(run.text), start, length)
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, length)
            }
        }
    }
}

private fun TimelineMessage.senderLabel(): String =
    senderJid.substringAfterLast('/').takeUnless { it == senderJid } ?: senderJid.substringBefore('@')

private fun TimelineMessage.manualQuote(): String = buildString {
    append("> ").append(senderLabel()).append(" wrote:\n")
    body.lineSequence().forEach { append("> ").append(it).append('\n') }
}.trimEnd()

internal fun countAppendedIncoming(
    previousLatestId: String,
    messages: List<TimelineMessage>,
): Int = appendedMessages(previousLatestId, messages).count { !it.outgoing }

private fun appendedMessages(
    previousLatestId: String,
    messages: List<TimelineMessage>,
): List<TimelineMessage> {
    val previousIndex = messages.indexOfLast { it.id == previousLatestId }
    if (previousIndex < 0) return emptyList()
    return messages.subList(previousIndex + 1, messages.size)
}

internal fun formatMessageTime(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = MESSAGE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

private val MESSAGE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun DeliveryPresentation.visibleLabel(): String? = when (this) {
    DeliveryPresentation.QUEUED -> "Queued"
    DeliveryPresentation.SENDING -> "Sending"
    DeliveryPresentation.FAILED -> "Failed"
    DeliveryPresentation.SENT,
    DeliveryPresentation.CONFIRMED,
    -> "Sent"
    DeliveryPresentation.UNCERTAIN -> "Delivery unknown"
    DeliveryPresentation.DELIVERED,
    DeliveryPresentation.READ -> null
}

private data class ReceiptCheck(val description: String, val color: Color)

private fun DeliveryPresentation.receiptCheck(bubbleArgb: Int): ReceiptCheck? =
    when (this) {
        DeliveryPresentation.DELIVERED -> ReceiptCheck(
            "Delivered",
            Color(receiptTickColor(read = false, bubbleArgb = bubbleArgb)),
        )
        DeliveryPresentation.READ -> ReceiptCheck(
            "Read",
            Color(receiptTickColor(read = true, bubbleArgb = bubbleArgb)),
        )
        else -> null
    }

@Composable
private fun MessageAttachment(
    url: String,
    name: String?,
    mime: String?,
    groupChat: Boolean,
    isCached: () -> Boolean,
    onUse: suspend () -> Boolean,
    onLoadInline: suspend () -> ImageBitmap?,
) {
    val scope = rememberCoroutineScope()
    val image = isInlineImage(mime, name, url)
    var downloaded by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        val cached = try {
            withContext(Dispatchers.IO) { isCached() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        // A slow negative lookup must not overwrite a completed download.
        downloaded = downloaded || cached
    }
    var preview by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    val inline = shouldRenderInlineImage(groupChat, mime, name, url)
    LaunchedEffect(url, inline) {
        if (!inline) return@LaunchedEffect
        preview = runCatching { onLoadInline() }.getOrNull()
    }
    val open: () -> Unit = {
        scope.launch {
            val ok = runCatching { onUse() }.getOrDefault(false)
            if (ok) downloaded = true
        }
        Unit
    }
    val bitmap = preview
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = name ?: "Image",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clickable(role = Role.Button, onClick = open)
                .testTag("message-inline-image"),
            contentScale = ContentScale.Fit,
        )
    } else {
        OutlinedButton(onClick = open, modifier = Modifier.testTag("message-attachment")) {
            Text(attachmentActionLabel(image, downloaded, name))
        }
    }
}
