package org.thanosapollo.nema.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.thanosapollo.nema.chat.ConversationSummary
import org.thanosapollo.nema.ui.chat.PeerAvatar
import org.thanosapollo.nema.ui.chat.mucNickColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    conversations: List<ConversationSummary>,
    conversationsReady: Boolean,
    connectionStatus: String,
    ownLabel: String,
    onSelectPeer: suspend (String) -> Boolean,
    onJoinRoom: suspend (String) -> Boolean = onSelectPeer,
    onOpenOwnProfile: () -> Unit = {},
    ownPhotoBytes: ByteArray? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var newChatOpen by rememberSaveable { mutableStateOf(false) }
    val visible = remember(conversations, query) {
        conversations.filter { conversationMatches(it, query) }
    }
    val listState = rememberLazyListState()
    var followNewest by remember { mutableStateOf(true) }
    val newestPeer = visible.firstOrNull()?.peerJid
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    followNewest = homeFollowsNewestAfterUserScroll(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                    )
                }
            }
    }
    LaunchedEffect(newestPeer, followNewest) {
        if (followNewest) listState.scrollToItem(0)
    }
    val status = quietConnectionStatus(connectionStatus)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search conversations") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                    } else {
                        Column {
                            Text("Nema", style = MaterialTheme.typography.titleLarge)
                            status?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onOpenOwnProfile,
                        modifier = Modifier.semantics { contentDescription = "Own profile" },
                    ) {
                        PeerAvatar(
                            label = ownLabel,
                            photoBytes = ownPhotoBytes,
                            size = 32.dp,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
            if (visible.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    Text(
                        when {
                            !conversationsReady -> "Loading conversations"
                            conversations.isEmpty() -> "No conversations"
                            else -> "No matches"
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FloatingActionButton(
                        onClick = { newChatOpen = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "New chat")
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("home-conversations"),
                    ) {
                        items(visible, key = ConversationSummary::peerJid) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                onClick = { scope.launch { onSelectPeer(conversation.peerJid) } },
                            )
                        }
                    }
                    FloatingActionButton(
                        onClick = { newChatOpen = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "New chat")
                    }
                }
            }
            SessionBottomBar(
                selected = PrimaryDestination.HOME,
                onSelect = { destination ->
                    if (destination == PrimaryDestination.SETTINGS) onOpenOwnProfile()
                },
            )
        }
        if (newChatOpen) {
            NewChatDialog(
                onDismiss = { newChatOpen = false },
                onOpen = onSelectPeer,
                onJoinRoom = onJoinRoom,
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("conversation-row-${conversation.peerJid}")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeerAvatar(
            label = conversation.displayLabel,
            photoBytes = conversation.photoBytes,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                conversation.displayLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                conversationPreviewText(conversation),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (conversation.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        unreadBadgeLabel(conversation.unreadCount)?.let { label ->
            Badge(
                modifier = Modifier
                    .testTag("conversation-unread")
                    .semantics { contentDescription = "$label unread" },
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun NewChatDialog(
    onDismiss: () -> Unit,
    onOpen: suspend (String) -> Boolean,
    onJoinRoom: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var peer by rememberSaveable { mutableStateOf("") }
    var invalidPeer by rememberSaveable { mutableStateOf(false) }
    var groupChat by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (groupChat) "Join groupchat" else "New chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !groupChat,
                        onClick = { groupChat = false },
                        label = { Text("Direct") },
                    )
                    FilterChip(
                        selected = groupChat,
                        onClick = { groupChat = true },
                        label = { Text("Group") },
                    )
                }
                OutlinedTextField(
                    value = peer,
                    onValueChange = {
                        peer = it
                        invalidPeer = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (groupChat) "Groupchat JID" else "Direct message JID") },
                    supportingText = if (invalidPeer) {
                        { Text(if (groupChat) "Enter a valid room JID" else "Enter a valid person JID") }
                    } else {
                        null
                    },
                    isError = invalidPeer,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        invalidPeer = if (groupChat) !onJoinRoom(peer) else !onOpen(peer)
                        if (!invalidPeer) onDismiss()
                    }
                },
                enabled = peer.isNotBlank(),
            ) {
                Text(if (groupChat) "Join groupchat" else "Open conversation")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun conversationPreviewText(conversation: ConversationSummary) = buildAnnotatedString {
    val body = conversationPreview(conversation.preview)
    val sender = conversation.previewSender
    val unread = conversation.unreadCount > 0
    if (sender != null) {
        val occupant = conversation.groupChat && sender != "You"
        val nickColor = if (occupant) {
            Color(mucNickColor(sender, MaterialTheme.colorScheme.surface.toArgb()))
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        withStyle(
            SpanStyle(
                color = nickColor,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
            ),
        ) {
            append(sender)
        }
        if (body.isNotEmpty()) append(": ")
    }
    when {
        body.isNotEmpty() -> append(body)
        sender == null && conversation.groupChat -> append("Groupchat")
    }
}
