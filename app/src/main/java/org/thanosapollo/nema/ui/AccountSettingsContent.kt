package org.thanosapollo.nema.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    update: UpdateUiModel? = null,
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
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
            item {
                SettingsRow(
                    title = "Themes",
                    onClick = openThemes,
                    modifier = Modifier.testTag("settings-row-themes"),
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
                )
            }
            item {
                SettingsRow(
                    title = "Sign out",
                    onClick = onSignOut,
                    modifier = Modifier.testTag("settings-row-sign-out"),
                    tone = SettingsRowTone.Danger,
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
                    SettingsRow(
                        title = model.currentVersion,
                        supportingText = model.status,
                        modifier = Modifier.testTag("settings-row-update-status"),
                    )
                }
                item {
                    SettingsRow(
                        title = "Check for updates",
                        enabled = model.canCheck,
                        onClick = onCheckForUpdates,
                        modifier = Modifier.testTag("settings-row-check-update"),
                    )
                }
                if (model.canDownload) {
                    item {
                        SettingsRow(
                            title = "Download update",
                            onClick = onDownloadUpdate,
                            modifier = Modifier.testTag("settings-row-download-update"),
                        )
                    }
                }
                if (model.canInstall) {
                    item {
                        SettingsRow(
                            title = "Install update",
                            onClick = onInstallUpdate,
                            modifier = Modifier.testTag("settings-row-install-update"),
                        )
                    }
                }
            }
        }
    }
}
