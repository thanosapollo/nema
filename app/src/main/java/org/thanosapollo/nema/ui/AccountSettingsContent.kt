package org.thanosapollo.nema.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.ui.theme.AppearanceScope
import org.thanosapollo.nema.ui.theme.AppearanceSpec
import org.thanosapollo.nema.ui.theme.PaletteChoice
import org.thanosapollo.nema.ui.theme.ThemeMode
import org.thanosapollo.nema.xmpp.transport.AccountId

@Suppress("UNUSED_PARAMETER")
@Composable
fun AccountSettingsContent(
    activeAccountId: AccountId,
    connectionStatus: String? = null,
    appearanceScope: AppearanceScope = AppearanceScope.App,
    appearance: AppearanceSpec = AppearanceSpec.DEFAULT,
    appearanceInherited: Boolean = false,
    conversationPeer: String? = null,
    appearanceMessage: String? = null,
    onSelectAppearanceScope: (AppearanceScope) -> Unit = {},
    onSetThemeMode: (ThemeMode) -> Unit = {},
    onSetPalette: (PaletteChoice) -> Unit = {},
    onSetTextScale: (Float) -> Unit = {},
    onSetUiScale: (Float) -> Unit = {},
    onChooseBackground: () -> Unit = {},
    onClearBackground: () -> Unit = {},
    onUseInheritedAppearance: () -> Unit = {},
    currentPaletteId: String = "rum",
    onPreviewPalette: (String) -> Unit = {},
    onApplyPalette: ((Boolean) -> Unit) -> Unit = { it(false) },
    onCancelPalette: () -> Unit = {},
    readReceiptsEnabled: Boolean = false,
    onSetReadReceiptsEnabled: (Boolean) -> Unit = {},
    accounts: List<AccountConfiguration> = emptyList(),
    switchingAccount: Boolean = false,
    onSelectAccount: (AccountId) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onStop: () -> Unit = {},
    onSignOut: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    ThemeSettingsContent(
        currentPaletteId = currentPaletteId,
        onPreview = onPreviewPalette,
        onApply = onApplyPalette,
        onCancel = onCancelPalette,
    ) { openThemes -> Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        connectionStatus?.let { status ->
            quietConnectionStatus(status)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = openThemes, modifier = Modifier.fillMaxWidth()) { Text("Themes") }
        AccountsContent(
            accounts = accounts,
            activeAccountId = activeAccountId,
            switching = switchingAccount,
            onSelectAccount = onSelectAccount,
            onAddAccount = onAddAccount,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Send read receipts")
                Text(
                    "Tell direct-chat contacts when a visible message has been read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = readReceiptsEnabled,
                onCheckedChange = onSetReadReceiptsEnabled,
                modifier = Modifier.testTag("read-receipts-toggle"),
            )
        }
        Text("Session", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Stop")
        }
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
    } }
}
