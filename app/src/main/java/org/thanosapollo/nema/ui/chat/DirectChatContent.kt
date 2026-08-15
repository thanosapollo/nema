package org.thanosapollo.nema.ui.chat

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.thanosapollo.nema.chat.DeliveryPresentation
import org.thanosapollo.nema.chat.DirectChatState
import org.thanosapollo.nema.chat.DirectConversationKey
import org.thanosapollo.nema.chat.DraftReply
import org.thanosapollo.nema.chat.DraftSnapshot
import org.thanosapollo.nema.chat.TimelineMessage
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.thread.ThreadId
import org.thanosapollo.nema.thread.ThreadRef
import org.thanosapollo.nema.ui.HomeContent
import org.thanosapollo.nema.ui.quietConnectionStatus
import org.thanosapollo.nema.ui.theme.LocalChatBackgroundUri
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingState
import org.thanosapollo.nema.xmpp.blocking.PeerBlockingMutationResult
import org.thanosapollo.nema.xmpp.httpupload.attachmentPreview
import org.thanosapollo.nema.xmpp.muc.roomSubtitle

@Composable
internal fun messageBubbleColors(outgoing: Boolean): Pair<Color, Color> = if (outgoing) {
    MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
} else {
    MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DirectChatContent(
    state: DirectChatState,
    connectionStatus: String,
    onSelectPeer: suspend (String) -> Boolean,
    onJoinRoom: suspend (String) -> Boolean = onSelectPeer,
    onCloseConversation: () -> Unit,
    onDraftChange: (DraftSnapshot) -> Deferred<Boolean>,
    onSend: (DraftSnapshot) -> Deferred<Boolean>,
    onSendAsNewThread: (DraftSnapshot) -> Deferred<Boolean> = { CompletableDeferred(false) },
    onStartNewThread: suspend () -> Boolean = { false },
    onContinueThread: suspend (ThreadRef) -> Boolean = { false },
    onStartChildThread: suspend () -> Boolean = { false },
    onStartThreadFrom: suspend (TimelineMessage) -> Boolean = { false },
    onCloseThread: () -> Unit = {},
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
    modifier: Modifier = Modifier,
) {
    key(state.accountId) {
        val scope = rememberCoroutineScope()
        var pendingSendIdentities by remember(state.accountId) {
            mutableStateOf(emptySet<PendingSendIdentity>())
        }
        var completedSendSnapshots by remember(state.accountId) {
            mutableStateOf(emptyMap<PendingSendIdentity, DraftSnapshot>())
        }
        var composerStates by remember(state.accountId) {
            mutableStateOf(emptyMap<DirectConversationKey, ComposerState>())
        }
        val currentConversationKey by rememberUpdatedState(
            state.selectedPeer?.let { DirectConversationKey(state.accountId, it, state.selectedThread) },
        )
        val currentBlockingSession by rememberUpdatedState(blockingSession)
        val backgroundUri = LocalChatBackgroundUri.current
        Box(modifier.fillMaxSize()) {
            ChatBackground(backgroundUri)
            if (state.selectedPeer == null) {
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
                        ),
                )
            } else {
                val conversationKey = DirectConversationKey(
                    state.accountId,
                    state.selectedPeer,
                    state.selectedThread,
                )
                val peerKey = conversationKey.copy(thread = null)
                val peerLabel = state.selectedPeerLabel ?: state.selectedPeer.orEmpty()
                var showPeerProfile by rememberSaveable(
                    peerKey.accountId,
                    peerKey.canonicalBarePeer,
                ) { mutableStateOf(false) }
                BackHandler(enabled = showPeerProfile) { showPeerProfile = false }
                BackHandler(enabled = !showPeerProfile) {
                    if (state.selectedThread == null) onCloseConversation() else onCloseThread()
                }
                if (showPeerProfile) {
                    PeerProfileContent(
                        key = peerKey,
                        blockingSession = blockingSession,
                        label = peerLabel,
                        remoteDisplayName = state.selectedPeerDisplayName,
                        localNickname = state.selectedPeerLocalNickname,
                        photoBytes = state.selectedPeerPhotoBytes,
                        onBack = { showPeerProfile = false },
                        onSaveNickname = onSavePeerNickname,
                        onLoadBlocking = onLoadPeerBlocking,
                        onSetBlocked = onSetPeerBlocked,
                        isCurrentBlockingOwner = { session, key ->
                            currentBlockingSession == session &&
                                currentConversationKey?.copy(thread = null) == key
                        },
                        onSharePeer = onSharePeer,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    )
                } else {
                val status = quietConnectionStatus(connectionStatus)
                var actionsOpen by remember { mutableStateOf(false) }
                var composer by rememberSaveable(
                    conversationKey,
                    saver = composerStateSaver(conversationKey),
                ) {
                    mutableStateOf(
                        composerStates[conversationKey] ?: ComposerState(
                            conversationKey,
                            state.draft,
                            0L,
                            null,
                            reply = state.draftReply,
                        ),
                    )
                }
                fun setComposer(next: ComposerState) {
                    composer = next
                    composerStates += conversationKey to next
                }
                LaunchedEffect(conversationKey, composerStates[conversationKey]) {
                    val retained = composerStates[conversationKey]
                    if (retained == null) {
                        composerStates += conversationKey to composer
                    } else if (retained != composer) {
                        composer = retained
                    }
                }
                val composerFocus = remember(conversationKey) { FocusRequester() }
                var latestFocusRequest by remember(conversationKey) { mutableStateOf(0L) }
                LaunchedEffect(conversationKey, completedSendSnapshots) {
                    val completions = completedSendSnapshots.filterKeys { it.key == conversationKey }
                    if (completions.isEmpty()) return@LaunchedEffect
                    latestFocusRequest += 1
                    setComposer(completions.values.fold(composer, ComposerState::clearAfterSend))
                    completedSendSnapshots = completedSendSnapshots - completions.keys
                    pendingSendIdentities = pendingSendIdentities - completions.keys
                }
                val keyboard = LocalSoftwareKeyboardController.current
                val resolver = LocalContext.current.contentResolver
                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                        val mime = resolver.getType(uri)
                        val bytes = withContext(Dispatchers.IO) {
                            resolver.openInputStream(uri)?.use { it.readBytes() }
                        } ?: return@launch
                        val uploaded = try {
                            onUploadFile(name, mime, bytes)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        if (uploaded != null) {
                            val current = composerStates[conversationKey] ?: composer
                            setComposer(
                                current.copy(
                                    body = if (current.body.isBlank()) uploaded.url else current.body,
                                    attachmentUrl = uploaded.url,
                                    attachmentName = uploaded.name,
                                    attachmentMime = uploaded.mime,
                                    attachmentSize = uploaded.size,
                                    revision = current.revision + 1,
                                    failureRevision = null,
                                ),
                            )
                        }
                    }
                }
                LaunchedEffect(state.selectedPeer, state.selectedPeerGroupChat) {
                    if (state.selectedPeerGroupChat) {
                        onJoinRoom(state.selectedPeer)
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
                    setComposer(next)
                    val snapshot = next.toDraftSnapshot(state.selectedPeerGroupChat)
                    val action = onDraftChange(snapshot)
                    scope.launch {
                        val applied = try {
                            action.await()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                        val current = composerStates[snapshot.key]
                        if (current?.matches(snapshot) == true) {
                            composerStates += snapshot.key to current.copy(
                                failureRevision = snapshot.composerRevision.takeUnless { applied },
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
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
                                    .semantics { contentDescription = "Open contact info" },
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
                                    status?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } ?: if (state.selectedPeerGroupChat) {
                                        Text(
                                            roomSubtitle(state.selectedRoomSubject, state.selectedRoomOccupantCount),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else {
                                        Unit
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
                                    text = { Text("Contact info") },
                                    onClick = {
                                        actionsOpen = false
                                        showPeerProfile = true
                                    },
                                )
                                if (state.selectedThread == null) {
                                    DropdownMenuItem(
                                        text = { Text("New thread") },
                                        onClick = {
                                            actionsOpen = false
                                            scope.launch { onStartNewThread() }
                                        },
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Child thread") },
                                        onClick = {
                                            actionsOpen = false
                                            scope.launch { onStartChildThread() }
                                        },
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    )
                    key(conversationKey) {
                        MessageTimeline(
                            messages = state.messages,
                            conversationGroupChat = state.selectedPeerGroupChat,
                            latestFocusRequest = latestFocusRequest,
                            onContinueThread = { thread -> scope.launch { onContinueThread(thread) } },
                            onReply = { message ->
                                val reference = requireNotNull(message.replyReferenceId)
                                updateComposer(
                                    composer.copy(
                                        reply = DraftReply(
                                            id = reference,
                                            to = message.senderJid,
                                            body = message.body,
                                            senderLabel = message.senderLabel(),
                                        ),
                                        revision = composer.revision + 1,
                                        failureRevision = null,
                                    ),
                                )
                                focusComposerWhenReady = true
                            },
                            onQuote = { message ->
                                val quote = message.manualQuote()
                                val answer = composer.body.takeIf(String::isNotBlank)
                                updateComposer(
                                    composer.copy(
                                        body = buildString {
                                            append(quote).append("\n\n")
                                            if (answer != null) append(answer)
                                        },
                                        revision = composer.revision + 1,
                                        failureRevision = null,
                                    ),
                                )
                                focusComposerWhenReady = true
                            },
                            onReplyAsThread = { message ->
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
                            onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.semantics { contentDescription = "Attach file" },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                        fun sendWith(action: (DraftSnapshot) -> Deferred<Boolean>) {
                            val snapshot = composer.toDraftSnapshot(state.selectedPeerGroupChat)
                            val identity = PendingSendIdentity(snapshot.key, snapshot.composerRevision)
                            if (identity in pendingSendIdentities) return
                            pendingSendIdentities += identity
                            val send = try {
                                action(snapshot)
                            } catch (cancelled: CancellationException) {
                                pendingSendIdentities -= identity
                                throw cancelled
                            } catch (_: Exception) {
                                pendingSendIdentities -= identity
                                return
                            }
                            scope.launch {
                                val applied = try {
                                    send.await()
                                } catch (cancelled: CancellationException) {
                                    pendingSendIdentities -= identity
                                    throw cancelled
                                } catch (_: Exception) {
                                    false
                                }
                                if (applied) {
                                    completedSendSnapshots += identity to snapshot
                                } else {
                                    pendingSendIdentities -= identity
                                }
                            }
                        }
                        val composerIsError = composer.failureRevision == composer.revision
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("message-composer-container"),
                            shape = RoundedCornerShape(22.dp),
                            tonalElevation = 1.dp,
                        ) {
                            Column {
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
                                                            failureRevision = null,
                                                        ),
                                                    )
                                                },
                                            ) { Text("Cancel") }
                                        }
                                    }
                                }
                                BasicTextField(
                                    value = composer.body,
                                    onValueChange = {
                                        updateComposer(
                                            composer.copy(
                                                body = it,
                                                revision = composer.revision + 1,
                                                failureRevision = null,
                                            ),
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .focusRequester(composerFocus)
                                        .testTag("message-composer")
                                        .semantics {
                                            if (composerIsError) error("Draft not saved")
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
                                                    "Message",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                            }
                                            innerTextField()
                                        }
                                    },
                                )
                                if (composerIsError) {
                                    Text(
                                        "Draft not saved",
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        var sendActionsOpen by remember { mutableStateOf(false) }
                        Box {
                            val sendEnabled =
                                (composer.body.isNotBlank() || composer.attachmentUrl != null) &&
                                    PendingSendIdentity(conversationKey, composer.revision) !in pendingSendIdentities
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .combinedClickable(
                                        enabled = sendEnabled,
                                        role = Role.Button,
                                        onLongClickLabel = "Send as thread",
                                        onClick = { sendWith(onSend) },
                                        onLongClick = if (conversationKey.thread == null) {
                                            { sendActionsOpen = true }
                                        } else {
                                            null
                                        },
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
                            DropdownMenu(
                                expanded = sendActionsOpen,
                                onDismissRequest = { sendActionsOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Send as thread") },
                                    onClick = {
                                        sendActionsOpen = false
                                        sendWith(onSendAsNewThread)
                                    },
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
private fun PeerProfileContent(
    key: DirectConversationKey,
    blockingSession: SessionIdentity?,
    label: String,
    remoteDisplayName: String?,
    localNickname: String?,
    photoBytes: ByteArray?,
    onBack: () -> Unit,
    onSaveNickname: suspend (DirectConversationKey, String) -> Boolean,
    onLoadBlocking: suspend (SessionIdentity, DirectConversationKey) -> PeerBlockingState,
    onSetBlocked: suspend (SessionIdentity, DirectConversationKey, Boolean) -> PeerBlockingMutationResult,
    isCurrentBlockingOwner: (SessionIdentity, DirectConversationKey) -> Boolean,
    onSharePeer: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    var nicknameOpen by remember { mutableStateOf(false) }
    var nicknameDraft by remember(localNickname) { mutableStateOf(localNickname.orEmpty()) }
    var nicknameSaving by remember { mutableStateOf(false) }
    var nicknameError by remember { mutableStateOf<String?>(null) }
    var blockingState by remember(key, blockingSession) { mutableStateOf<PeerBlockingState?>(null) }
    var blockingError by remember(key, blockingSession) { mutableStateOf<String?>(null) }
    var blockingMutation by remember { mutableStateOf<Boolean?>(null) }
    var blockingBusy by remember { mutableStateOf(false) }

    LaunchedEffect(key, blockingSession) {
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

    Column(modifier) {
        TopAppBar(
            title = { Text("Contact info") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to conversation")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        LazyColumn(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .testTag("contact-info-list"),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PeerAvatar(label = label, photoBytes = photoBytes, size = 80.dp)
                    Text(label, style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                ProfileRow(
                    title = "Nickname",
                    value = localNickname ?: "Not set",
                    onClick = {
                        nicknameDraft = localNickname.orEmpty()
                        nicknameError = null
                        nicknameOpen = true
                    },
                    modifier = Modifier.testTag("nickname-action"),
                )
            }
            item {
                ProfileRow(
                    title = "XMPP address",
                    value = key.canonicalBarePeer,
                )
            }
            onSharePeer?.let { share ->
                item {
                    ProfileRow(
                        title = "Share XMPP address",
                        value = "xmpp:${key.canonicalBarePeer}",
                        onClick = { share(key.canonicalBarePeer) },
                        modifier = Modifier.testTag("share-xmpp-action"),
                    )
                }
            }
            remoteDisplayName?.takeIf { it.isNotBlank() }?.let { remoteName ->
                item {
                    ProfileRow(
                        title = "Remote profile",
                        value = remoteName,
                    )
                }
            }
            item {
                ProfileRow(
                    title = "Encryption",
                    value = "Plaintext",
                    modifier = Modifier.testTag("encryption-info"),
                )
            }
            blockingState?.takeIf(PeerBlockingState::supported)?.let { state ->
                item {
                    ProfileRow(
                        title = if (state.blocked) "Unblock" else "Block",
                        value = if (state.blocked) {
                            "Blocked by ${state.blockedAddresses.joinToString()}"
                        } else {
                            "Stop messages from this address"
                        },
                        enabled = !blockingBusy,
                        onClick = { blockingMutation = !state.blocked },
                        modifier = Modifier.testTag("block-action"),
                        titleColor = MaterialTheme.colorScheme.error,
                    )
                }
            }
            blockingError?.let { message ->
                item {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (nicknameOpen) {
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

    blockingMutation?.let { block ->
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
private fun ProfileRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val rowModifier = if (onClick == null) {
        modifier
    } else {
        modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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

private const val COMPOSER_STATE_VERSION = 3

private data class PendingSendIdentity(
    val key: DirectConversationKey,
    val composerRevision: Long,
)

private data class ComposerState(
    val key: DirectConversationKey,
    val body: String,
    val revision: Long,
    val failureRevision: Long?,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long? = null,
    val reply: DraftReply? = null,
) {
    fun toDraftSnapshot(groupChat: Boolean): DraftSnapshot = DraftSnapshot(
        key = key,
        body = body,
        composerRevision = revision,
        groupChat = groupChat,
        attachmentUrl = attachmentUrl,
        attachmentName = attachmentName,
        attachmentMime = attachmentMime,
        attachmentSize = attachmentSize,
        reply = reply,
    )
}

private fun ComposerState.clearAfterSend(snapshot: DraftSnapshot): ComposerState =
    if (matches(snapshot)) {
        copy(
            body = "",
            attachmentUrl = null,
            attachmentName = null,
            attachmentMime = null,
            attachmentSize = null,
            reply = null,
            revision = revision + 1,
            failureRevision = null,
        )
    } else {
        this
    }

private fun ComposerState.matches(snapshot: DraftSnapshot): Boolean =
    key == snapshot.key &&
        revision == snapshot.composerRevision &&
        body == snapshot.body &&
        reply == snapshot.reply

private fun composerStateSaver(
    expectedKey: DirectConversationKey,
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
            state.failureRevision,
            state.attachmentUrl,
            state.attachmentName,
            state.attachmentMime,
            state.attachmentSize,
            state.reply?.id,
            state.reply?.to,
            state.reply?.body,
            state.reply?.senderLabel,
        )
    },
    restore = restore@{ saved ->
        val values = saved as? List<*> ?: return@restore null
        if (values.size != 16 || values[0] != COMPOSER_STATE_VERSION) return@restore null
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
        if (values[7] != null && values[7] !is Long) return@restore null
        val failureRevision = values[7] as Long?
        if (revision < 0 || (failureRevision != null && failureRevision != revision)) {
            return@restore null
        }
        if ((8..10).any { values[it] != null && values[it] !is String }) return@restore null
        if (values[11] != null && values[11] !is Long) return@restore null
        if ((12..15).any { values[it] != null && values[it] !is String }) return@restore null
        val replyId = values[12] as String?
        val replyTo = values[13] as String?
        val replyBody = values[14] as String?
        val replySender = values[15] as String?
        if (replyId == null && listOf(replyTo, replyBody, replySender).any { it != null }) return@restore null
        if (replyId != null && (replyBody == null || replySender == null)) return@restore null
        val key = DirectConversationKey(
            accountId = accountId,
            canonicalBarePeer = peer,
            thread = threadId?.let { ThreadRef(it, parentId) },
        )
        if (key != expectedKey) return@restore null
        mutableStateOf(
            ComposerState(
                key = key,
                body = body,
                revision = revision,
                failureRevision = failureRevision,
                attachmentUrl = values[8] as String?,
                attachmentName = values[9] as String?,
                attachmentMime = values[10] as String?,
                attachmentSize = values[11] as Long?,
                reply = replyId?.let {
                    DraftReply(it, replyTo, requireNotNull(replyBody), requireNotNull(replySender))
                },
            ),
        )
    },
)

@Composable
fun MessageTimeline(
    messages: List<TimelineMessage>,
    conversationGroupChat: Boolean = false,
    latestFocusRequest: Long = 0L,
    onContinueThread: (ThreadRef) -> Unit = {},
    onReply: (TimelineMessage) -> Unit = {},
    onQuote: (TimelineMessage) -> Unit = {},
    onReplyAsThread: (TimelineMessage) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atLatest by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    var previousLatestId by remember { mutableStateOf<String?>(null) }
    var newIncoming by remember { mutableIntStateOf(0) }
    val latestId = messages.lastOrNull()?.id
    LaunchedEffect(atLatest) {
        if (atLatest) newIncoming = 0
    }
    LaunchedEffect(latestFocusRequest) {
        if (latestFocusRequest > 0L && messages.isNotEmpty()) {
            listState.scrollToItem(0)
            newIncoming = 0
        }
    }
    LaunchedEffect(latestId) {
        val previous = previousLatestId
        if (previous != null) {
            val appended = appendedMessages(previous, messages)
            if (atLatest) {
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
            items(messages.asReversed(), key = TimelineMessage::id) { message ->
                var messageActionsOpen by remember(message.id) { mutableStateOf(false) }
                val (bubbleContainerColor, bubbleContentColor) = messageBubbleColors(message.outgoing)
                val quote = remember(message.body) { message.body.leadingManualQuote() }
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .align(if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                            .padding(
                                start = if (message.outgoing) 32.dp else 0.dp,
                                end = if (message.outgoing) 0.dp else 32.dp,
                            ),
                    ) {
                        Surface(
                            modifier = Modifier
                                .combinedClickable(
                                    role = Role.Button,
                                    onLongClickLabel = "Message actions",
                                    onClick = {},
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
                                    Text(
                                        message.senderJid.substringAfterLast('/').ifEmpty { message.senderJid },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                message.attachmentUrl?.let {
                                    Text(
                                        attachmentPreview(message.attachmentName, it),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                message.reply?.let { reply ->
                                    MessageReplyPreview(reply.senderLabel, reply.body)
                                }
                                if (quote == null) {
                                    Text(message.body, style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    ManualQuoteBlock(quote.quoted)
                                    quote.remainder.takeIf(String::isNotEmpty)?.let {
                                        Text(it, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                message.delivery?.visibleLabel()?.let { label ->
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                                message.sentAtEpochMs?.let { sentAt ->
                                    Text(
                                        formatMessageTime(sentAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.align(Alignment.End),
                                    )
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
                                if (!conversationGroupChat && !message.groupChat) {
                                    DropdownMenuItem(
                                        text = { Text("Reply as a thread") },
                                        onClick = {
                                            messageActionsOpen = false
                                            onReplyAsThread(message)
                                        },
                                    )
                                }
                            }
                            DropdownMenuItem(
                                text = { Text("Quote") },
                                onClick = {
                                    messageActionsOpen = false
                                    onQuote(message)
                                },
                            )

                            message.thread?.let { thread ->
                                DropdownMenuItem(
                                    text = { Text("Open thread") },
                                    onClick = {
                                        messageActionsOpen = false
                                        onContinueThread(thread)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (!atLatest) {
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
private fun ManualQuoteBlock(body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
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
        Text(
            body,
            modifier = Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private data class ManualQuote(val quoted: String, val remainder: String)

private fun String.leadingManualQuote(): ManualQuote? {
    val lines = split('\n')
    if (lines.firstOrNull()?.startsWith('>') != true) return null
    val quotedLines = lines.takeWhile { it.startsWith('>') }
    val remainder = lines.drop(quotedLines.size).dropWhile(String::isEmpty).joinToString("\n")
    return ManualQuote(
        quoted = quotedLines.joinToString("\n") { it.removePrefix(">").removePrefix(" ") },
        remainder = remainder,
    )
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
    DeliveryPresentation.UNCERTAIN,
    -> null
}
