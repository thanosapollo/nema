package org.thanosapollo.nema.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SettingsSectionHeader(
            title = "Accounts",
            modifier = Modifier.testTag("settings-section-accounts"),
        )
        if (switching) {
            Text(
                "Switching account",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        accounts.forEach { account ->
            val active = account.id == activeAccountId
            val label = account.bareJid.value
            SettingsRow(
                title = if (active) "Active: $label" else "Switch to $label",
                enabled = !switching && !active,
                onClick = if (active) null else ({ onSelectAccount(account.id) }),
                modifier = Modifier.testTag("settings-row-account-${account.id.value}"),
                leadingContent = {
                    PeerAvatar(
                        label = label,
                        photoBytes = null,
                        size = 36.dp,
                        modifier = Modifier.testTag("account-avatar-${account.id.value}"),
                    )
                },
                selected = active,
            )
        }
        SettingsRow(
            title = "Add account",
            enabled = !switching,
            onClick = onAddAccount,
            modifier = Modifier.testTag("settings-row-add-account"),
            role = Role.Button,
        )
    }
}
