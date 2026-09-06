package org.thanosapollo.nema.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.thanosapollo.nema.chat.ArchiveFailureKind
import org.thanosapollo.nema.chat.ArchiveSyncState
import org.thanosapollo.nema.session.ConnectionState
import org.thanosapollo.nema.session.SessionIdentity
import org.thanosapollo.nema.xmpp.transport.AccountId

internal data class ArchiveNotice(val text: String, val action: String? = null)

internal fun archiveNotice(state: ArchiveSyncState, accountId: AccountId, connection: ConnectionState): ArchiveNotice? {
    val connected = connection as? ConnectionState.Connected ?: return null
    if (connected.accountId != accountId) return null
    val identity = when (state) {
        ArchiveSyncState.Idle -> return null
        is ArchiveSyncState.Discovering -> state.identity
        is ArchiveSyncState.Unsupported -> state.identity
        is ArchiveSyncState.Syncing -> state.identity
        is ArchiveSyncState.Ready -> return null
        is ArchiveSyncState.RetryableError -> return null // Internal result, not manual admission.
        is ArchiveSyncState.WaitingToRetry -> state.identity
        is ArchiveSyncState.Incomplete -> state.error.identity
        is ArchiveSyncState.ContinuationRequired -> state.identity
    }
    if (identity != SessionIdentity(connected.accountId, connected.generation)) return null
    return when (state) {
        is ArchiveSyncState.Discovering, is ArchiveSyncState.Syncing -> ArchiveNotice("Checking server history…")
        is ArchiveSyncState.Unsupported -> ArchiveNotice("This server does not support message history.")
        is ArchiveSyncState.WaitingToRetry -> ArchiveNotice("History may be incomplete. Retrying shortly (attempt ${state.attempt}).")
        is ArchiveSyncState.Incomplete -> ArchiveNotice(
            when (state.error.kind) {
                ArchiveFailureKind.TRANSIENT -> "Server history is unavailable. History may be incomplete."
                ArchiveFailureKind.PROTOCOL -> "Server history could not be processed. History may be incomplete."
            },
            "Retry history",
        )
        is ArchiveSyncState.ContinuationRequired -> ArchiveNotice("More server history remains to sync.", "Continue sync")
        else -> null
    }
}

/** The session owns work; this surface sends only the exact displayed recovery token. */
@Composable
internal fun ArchiveStatusBanner(
    state: ArchiveSyncState,
    accountId: AccountId,
    connection: ConnectionState,
    onRetry: (ArchiveSyncState) -> Unit,
) {
    val notice = archiveNotice(state, accountId, connection) ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().testTag("archive-status")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(notice.text, style = MaterialTheme.typography.bodySmall)
            notice.action?.let { action ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onRetry(state) }) { Text(action) }
                }
            }
        }
    }
}
