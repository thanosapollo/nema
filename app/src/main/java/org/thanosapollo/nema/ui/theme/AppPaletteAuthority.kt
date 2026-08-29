package org.thanosapollo.nema.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Activity-owned palette state with one transient value while a durable save is in flight. */
class AppPaletteAuthority(
    private val repository: AppearanceRepository,
) {
    private var persistedId = repository.appPaletteId()
    private var selectingId by mutableStateOf<String?>(null)

    val palette: PaletteDefinition
        get() = PaletteCatalog.resolve(selectingId ?: persistedId)

    suspend fun select(id: String): Boolean {
        selectingId = id
        return try {
            val saved = repository.saveAppPaletteId(id)
            if (saved) persistedId = repository.appPaletteId()
            saved
        } finally {
            selectingId = null
        }
    }
}
