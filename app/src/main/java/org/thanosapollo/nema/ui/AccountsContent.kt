package org.thanosapollo.nema.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.ui.chat.PeerAvatar
import org.thanosapollo.nema.xmpp.transport.AccountId

@Composable
fun AccountsContent(
    accounts: List<AccountConfiguration>,
    activeAccountId: AccountId,
    switching: Boolean,
    onSelectAccount: (AccountId) -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Accounts", style = MaterialTheme.typography.titleLarge)
        if (switching) {
            Text(
                "Switching account",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        accounts.forEach { account ->
            val active = account.id == activeAccountId
            val label = account.bareJid.value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { selected = active }
                    .clickable(enabled = !switching && !active) { onSelectAccount(account.id) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PeerAvatar(label = label, photoBytes = null, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (active) "Active: $label" else "Switch to $label",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        TextButton(
            onClick = onAddAccount,
            modifier = Modifier.fillMaxWidth(),
            enabled = !switching,
        ) {
            Text("Add account")
        }
    }
}
