package org.thanosapollo.nema.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Activity-owned palette state. Preview is deliberately absent from saved state and preferences. */
class AppPaletteAuthority(
    private val repository: AppearanceRepository,
) {
    private var persistedId = repository.appPaletteId()
    private var previewId by mutableStateOf<String?>(null)

    val palette: PaletteDefinition
        get() = PaletteCatalog.resolve(previewId ?: persistedId)

    val hasPreview: Boolean
        get() = previewId != null

    fun preview(id: String) {
        previewId = id
    }

    fun cancelPreview() {
        previewId = null
    }

    suspend fun applyPreview(): Boolean {
        val selected = previewId ?: return true
        if (!repository.saveAppPaletteId(selected)) return false
        persistedId = repository.appPaletteId()
        previewId = null
        return true
    }
}
