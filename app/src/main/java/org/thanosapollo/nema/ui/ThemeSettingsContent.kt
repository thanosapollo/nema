package org.thanosapollo.nema.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.thanosapollo.nema.ui.theme.PaletteCatalog
import org.thanosapollo.nema.ui.theme.PaletteDefinition

private enum class ThemeSettingsPage { SETTINGS, THEMES }

@Composable
internal fun ThemeSettingsContent(
    currentPaletteId: String,
    onSelect: (String, (Boolean) -> Unit) -> Unit = { _, complete -> complete(false) },
    palettes: List<PaletteDefinition> = PaletteCatalog.entries,
    modifier: Modifier = Modifier,
    settingsContent: @Composable ((() -> Unit) -> Unit),
) {
    var page by remember { mutableStateOf(ThemeSettingsPage.SETTINGS) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val closeThemes = { page = ThemeSettingsPage.SETTINGS }
    BackHandler(enabled = page == ThemeSettingsPage.THEMES, onBack = closeThemes)
    Box(modifier.fillMaxSize()) {
        when (page) {
            ThemeSettingsPage.SETTINGS -> settingsContent { page = ThemeSettingsPage.THEMES }
            ThemeSettingsPage.THEMES -> ThemePicker(
                palettes = palettes,
                currentPaletteId = currentPaletteId,
                saving = saving,
                saveFailed = saveFailed,
                onBack = closeThemes,
                onSelect = { id ->
                    saving = true
                    saveFailed = false
                    onSelect(id) { saved ->
                        saving = false
                        saveFailed = !saved
                    }
                },
            )
        }
    }
}

@Composable
private fun ThemePicker(
    palettes: List<PaletteDefinition>,
    currentPaletteId: String,
    saving: Boolean,
    saveFailed: Boolean,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsProfileScreen(
            title = "Themes",
            onBack = onBack,
            modifier = Modifier
                .weight(1f)
                .testTag("theme-list"),
            listTag = "theme-scroll-list",
            listModifier = Modifier.selectableGroup(),
        ) {
            if (palettes.isEmpty()) {
                item { SettingsRow(title = "No themes available") }
            } else {
                items(palettes, key = PaletteDefinition::id) { palette ->
                    SettingsRow(
                        title = palette.displayName,
                        selected = palette.id == currentPaletteId,
                        enabled = !saving,
                        onClick = { onSelect(palette.id) },
                        role = Role.RadioButton,
                        modifier = Modifier.testTag("settings-row-theme-${palette.id}"),
                        trailingContent = {
                            Row(Modifier.testTag("theme-swatches-${palette.id}")) {
                                listOf(
                                    palette.source.background,
                                    palette.source.foreground,
                                    palette.source.accent,
                                    palette.source.selection,
                                ).forEach { color ->
                                    Box(Modifier.size(20.dp).background(Color(color)))
                                }
                            }
                        },
                    )
                }
            }
        }
        if (saveFailed) {
            Text(
                "Theme was not saved",
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
