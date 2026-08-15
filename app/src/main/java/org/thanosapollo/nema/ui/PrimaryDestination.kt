package org.thanosapollo.nema.ui

/** Top-level session destinations for the persistent bottom bar. */
enum class PrimaryDestination {
    HOME,
    SETTINGS,
    ;

    companion object {
        fun fromSaved(name: String?): PrimaryDestination =
            entries.firstOrNull { it.name == name } ?: HOME
    }
}
