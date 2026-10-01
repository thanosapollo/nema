package org.thanosapollo.nema.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import org.thanosapollo.nema.account.AccountConfiguration
import org.thanosapollo.nema.ui.theme.AppearanceScope
import org.thanosapollo.nema.ui.theme.AppearanceSpec
import org.thanosapollo.nema.ui.theme.PaletteChoice
import org.thanosapollo.nema.ui.theme.ThemeMode
import org.thanosapollo.nema.update.UpdateUiModel
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
    onSelectPalette: (String, (Boolean) -> Unit) -> Unit = { _, complete -> complete(false) },
    readReceiptsEnabled: Boolean = false,
    onSetReadReceiptsEnabled: (Boolean) -> Unit = {},
    accounts: List<AccountConfiguration> = emptyList(),
    switchingAccount: Boolean = false,
    onSelectAccount: (AccountId) -> Unit = {},
    onAddAccount: () -> Unit = {},
    onStop: () -> Unit = {},
    onSignOut: () -> Unit = {},
    torRequired: Boolean = false,
    onStartOrbot: () -> Unit = {},
    onRetryTor: () -> Unit = {},
    update: UpdateUiModel? = null,
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    onCancelUpdate: () -> Unit = {},
    updateMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    ThemeSettingsContent(
        currentPaletteId = currentPaletteId,
        onSelect = onSelectPalette,
        modifier = modifier,
    ) { openThemes ->
        SettingsProfileScreen(
            title = "Settings",
            listTag = "settings-list",
        ) {
            connectionStatus?.let { status ->
                quietConnectionStatus(status)?.let { quietStatus ->
                    item {
                        Text(
                            quietStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (torRequired) {
                item {
                    SettingsRow(
                        title = "Open Orbot",
                        supportingText = "Messages and attachments require trusted Orbot at 127.0.0.1:9050. A verified same-onion message connection can use Tor protection without XMPP TLS when the server offers none. Offered TLS must validate; attachments always use HTTPS. App update checks use direct HTTPS; browser links follow the browser’s routing.",
                        onClick = onStartOrbot,
                        modifier = Modifier.testTag("start-orbot"),
                        role = Role.Button,
                    )
                }
                item {
                    SettingsRow(
                        title = "Retry Tor connection",
                        supportingText = "Start Orbot first. Nema never retries over a direct connection.",
                        onClick = onRetryTor,
                        modifier = Modifier.testTag("retry-tor"),
                        role = Role.Button,
                    )
                }
            }
            item {
                SettingsRow(
                    title = "Themes",
                    onClick = openThemes,
                    modifier = Modifier.testTag("settings-row-themes"),
                    role = Role.Button,
                )
            }
            item {
                AccountsContent(
                    accounts = accounts,
                    activeAccountId = activeAccountId,
                    switching = switchingAccount,
                    onSelectAccount = onSelectAccount,
                    onAddAccount = onAddAccount,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SettingsSectionHeader(
                    title = "Privacy",
                    modifier = Modifier.testTag("settings-section-privacy"),
                )
            }
            item {
                SettingsRow(
                    title = "Send read receipts",
                    supportingText = "Tell direct-chat contacts when a visible message has been read.",
                    modifier = Modifier.testTag("settings-row-read-receipts"),
                    trailingContent = {
                        Switch(
                            checked = readReceiptsEnabled,
                            onCheckedChange = onSetReadReceiptsEnabled,
                            modifier = Modifier.testTag("read-receipts-toggle"),
                        )
                    },
                )
            }
            item {
                SettingsSectionHeader(
                    title = "Session",
                    modifier = Modifier.testTag("settings-section-session"),
                )
            }
            item {
                SettingsRow(
                    title = "Stop",
                    onClick = onStop,
                    modifier = Modifier.testTag("settings-row-stop"),
                    role = Role.Button,
                )
            }
            item {
                SettingsRow(
                    title = "Sign out",
                    onClick = onSignOut,
                    modifier = Modifier.testTag("settings-row-sign-out"),
                    tone = SettingsRowTone.Danger,
                    role = Role.Button,
                )
            }
            update?.let { model ->
                item {
                    SettingsSectionHeader(
                        title = "Updates",
                        modifier = Modifier.testTag("settings-section-updates"),
                    )
                }
                item {
                    org.thanosapollo.nema.update.UpdateControls(
                        model, onCheckForUpdates, onDownloadUpdate, onInstallUpdate,
                        onCancelUpdate, updateMessage,
                    )
                }
            }
        }
    }
}
