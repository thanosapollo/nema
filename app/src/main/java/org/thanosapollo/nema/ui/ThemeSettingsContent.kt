package org.thanosapollo.nema.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
private enum class ThemeSettingsPage { SETTINGS, THEMES, PICKER }
@Composable
internal fun ThemeSettingsContent(currentPaletteId: String, onPreview: (String) -> Unit = {},
    onApply: (((Boolean) -> Unit) -> Unit) = { it(false) }, onCancel: () -> Unit = {},
    palettes: List<PaletteDefinition> = PaletteCatalog.entries,
    settingsContent: @Composable ((() -> Unit) -> Unit)) {
    var page by remember { mutableStateOf(ThemeSettingsPage.SETTINGS) }
    var applying by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    fun cancelPicker() { onCancel(); page = ThemeSettingsPage.THEMES }
    BackHandler(enabled = page == ThemeSettingsPage.THEMES) { page = ThemeSettingsPage.SETTINGS }
    BackHandler(enabled = page == ThemeSettingsPage.PICKER) { if (!applying) cancelPicker() }
    when (page) {
        ThemeSettingsPage.SETTINGS -> settingsContent { page = ThemeSettingsPage.THEMES }
        ThemeSettingsPage.THEMES -> Page("Themes") {
            TextButton(
                onClick = { page = ThemeSettingsPage.PICKER },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Select theme") }
        }
        ThemeSettingsPage.PICKER -> ThemePicker(
            palettes = palettes,
            currentPaletteId = currentPaletteId,
            applying = applying,
            saveFailed = saveFailed,
            onPreview = { saveFailed = false; onPreview(it) },
            onApply = { applying = true; saveFailed = false
                onApply { saved -> applying = false
                    if (saved) page = ThemeSettingsPage.THEMES else saveFailed = true }
            },
            onCancel = ::cancelPicker,
        )
    }
}

@Composable
private fun Page(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp))
    { Text(title, style = MaterialTheme.typography.titleLarge); content() }
}

@Composable
private fun ThemePicker(palettes: List<PaletteDefinition>, currentPaletteId: String, applying: Boolean,
    saveFailed: Boolean, onPreview: (String) -> Unit, onApply: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Select theme", style = MaterialTheme.typography.titleLarge)
        if (palettes.isEmpty()) {
            Text("No themes available")
        } else {
            LazyColumn(
                Modifier.weight(1f).testTag("theme-list").selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(palettes, key = PaletteDefinition::id) { palette ->
                    PaletteCard(palette, palette.id == currentPaletteId, !applying) { onPreview(palette.id) }
                }
            }
        }
        if (saveFailed) Text("Theme was not saved", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, enabled = !applying, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            Button(onClick = onApply, enabled = palettes.isNotEmpty() && !applying, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Apply") }
        }
    }
}

@Composable
private fun PaletteCard(palette: PaletteDefinition, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = selected,
        enabled = enabled, onClick = onClick, role = Role.RadioButton)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(palette.displayName)
            Row(Modifier.testTag("theme-swatches-${palette.id}")) {
                listOf(palette.source.background, palette.source.foreground, palette.source.accent,
                    palette.source.selection).forEach { Box(Modifier.size(20.dp).background(Color(it))) }
            }
        }
    }
}
