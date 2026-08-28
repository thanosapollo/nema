package org.thanosapollo.nema.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.thanosapollo.nema.storage.PeerEntity
import org.thanosapollo.nema.ui.chat.PeerAvatar

internal fun rosterLabel(peer: PeerEntity): String =
    peer.localNickname ?: peer.rosterName ?: peer.displayName ?: peer.jid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RosterContent(
    peers: Flow<List<PeerEntity>>,
    onSelectPeer: suspend (String) -> Boolean,
    onAccepted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roster by peers.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Roster") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        if (roster.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    "No roster contacts",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("roster-list")) {
                items(roster.sortedBy(PeerEntity::jid), key = PeerEntity::jid) { peer ->
                    val label = rosterLabel(peer)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                scope.launch { if (onSelectPeer(peer.jid)) onAccepted() }
                            }
                            .testTag("roster-row-${peer.jid}")
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PeerAvatar(label = label, photoBytes = peer.photoBytes)
                        Column(Modifier.weight(1f)) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (label != peer.jid) {
                                Text(
                                    peer.jid,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
