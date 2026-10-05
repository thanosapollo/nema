package org.thanosapollo.nema.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.thanosapollo.nema.xmpp.transport.AccountId

/** The rendered action carries the account displayed, not a late-bound active account. */
data class ConnectionRecovery(val accountId: AccountId, val enabled: Boolean = true)

@Composable
fun ConnectionStatusRow(
    status: String,
    recovery: ConnectionRecovery? = null,
    onReconnect: (AccountId) -> Unit = {},
) {
    val quiet = quietConnectionStatus(status) ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("connection-status"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(quiet, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        recovery?.let { action ->
            TextButton(onClick = { onReconnect(action.accountId) }, enabled = action.enabled,
                modifier = Modifier.testTag("connection-reconnect")) { Text("Reconnect") }
        }
    }
}
