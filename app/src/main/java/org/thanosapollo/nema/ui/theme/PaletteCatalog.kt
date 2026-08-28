package org.thanosapollo.nema.ui.theme

import java.util.Collections

data class PaletteSource(
    val background: Int,
    val darkBackground: Int,
    val lighterBackground: Int,
    val foreground: Int,
    val lightForeground: Int,
    val accent: Int,
    val selection: Int,
    val red: Int,
    val brightRed: Int,
)

data class PaletteDefinition(
    val id: String,
    val displayName: String,
    val mode: ThemeMode,
    val source: PaletteSource,
) {
    val colors: SemanticPalette = source.toSemanticPalette()
}

object PaletteCatalog {
    val entries: List<PaletteDefinition> = Collections.unmodifiableList(listOf(
        p("rum", "Default", "dark", "000000", "0a0a0a", "111111", "d4c5a0", "c8b890", "d48a20", "3a2914", "ff5f59", "ff6b55"),
        p("catppuccin-latte", "Catppuccin Latte", "light", "eff1f5", "e3e4e8", "dce0e8", "4c4f69", "5c5f77", "1e66f5", "ccd0da", "d20f39", "d20f39"),
        p("catppuccin", "Catppuccin", "dark", "1e1e2e", "161622", "313244", "cdd6f4", "bac2de", "89b4fa", "45475a", "f38ba8", "f38ba8"),
        p("ethereal", "Ethereal", "dark", "060b1e", "040816", "131a3a", "ffcead", "c9b8a6", "7d82d9", "252e56", "ed5b5a", "faaaa9"),
        p("everforest", "Everforest", "dark", "2d353b", "21272c", "343f44", "d3c6aa", "9da9a0", "7fbbb3", "3d484d", "e67e80", "e67e80"),
        p("flexoki-light", "Flexoki Light", "light", "fffcf0", "f2efe4", "e6e4d9", "100f0f", "403e3c", "205ea6", "cecdc3", "d14d41", "d14d41"),
        p("gruvbox", "Gruvbox", "dark", "282828", "1e1e1e", "3c3836", "d4be98", "bdae93", "7daea3", "504945", "ea6962", "ea6962"),
        p("hackerman", "Hackerman", "dark", "0b0c16", "080910", "151828", "ddf7ff", "b5c5db", "82fb9c", "1f253a", "50f872", "85ff9d"),
        p("kanagawa", "Kanagawa", "dark", "1f1f28", "17171e", "223249", "dcd7ba", "c8c093", "dcd7ba", "363646", "c34043", "e82424"),
        p("last-horizon", "Last Horizon", "dark", "0c0b0c", "090809", "0c0b0c", "fafcfb", "cfd3cd", "b59790", "584e51", "c38b7b", "c38b7b"),
        p("lumon", "Lumon", "dark", "16242d", "101b21", "1b2d40", "d6e2ee", "d6e2ee", "8bc9eb", "243d56", "4d86b0", "73a6cb"),
        p("lupine", "Lupine", "light", "fafafa", "ececec", "f5f5f5", "212121", "424242", "3264eb", "d0d0d0", "c900c4", "f930fb"),
        p("matte-black", "Matte Black", "dark", "121212", "0d0d0d", "1e1e1e", "bebebe", "8a8a8d", "e68e0d", "2a2a2a", "d35f5f", "b91c1c"),
        p("miasma", "Miasma", "dark", "222222", "191919", "2c2c2c", "c2c2b0", "8a8a7e", "78824b", "383838", "685742", "685742"),
        p("nord", "Nord", "dark", "2e3440", "222730", "3b4252", "d8dee9", "adb5c4", "81a1c1", "434c5e", "bf616a", "bf616a"),
        p("osaka-jade", "Osaka Jade", "dark", "111c18", "0c1512", "23372b", "c1c497", "d6d5bc", "509475", "32473b", "ff5345", "db9f9c"),
        p("retro-82", "Retro 82", "dark", "05182e", "031222", "0a2540", "f6dcac", "a7c9c6", "faa968", "134e5a", "f85525", "f85525"),
        p("ristretto", "Ristretto", "dark", "2c2525", "211b1b", "3d2f2a", "e6d9db", "c3b7b8", "f38d70", "403e41", "fd6883", "ff8297"),
        p("rose-pine", "Rose Pine", "light", "faf4ed", "ede7e1", "f2e9e1", "575279", "6e6a86", "56949f", "dfdad9", "b4637a", "b4637a"),
        p("solitude", "Solitude", "dark", "101315", "0c0e10", "101315", "cacccc", "cbc2be", "798186", "343d41", "565d60", "de6145"),
        p("tokyo-night", "Tokyo Night", "dark", "1a1b26", "13141c", "24283b", "a9b1d6", "b4bee6", "7aa2f7", "292e42", "f7768e", "ff7a93"),
        p("vantablack", "Vantablack", "dark", "000000", "090909", "1a1a1a", "ffffff", "ececec", "8d8d8d", "1a1a1a", "a4a4a4", "a4a4a4"),
        p("white", "White", "light", "ffffff", "f5f5f5", "c0c0c0", "000000", "000000", "6e6e6e", "c0c0c0", "2a2a2a", "2a2a2a"),
    ))
    val default: PaletteDefinition = entries.first()
    private val byId = entries.associateBy(PaletteDefinition::id)

    fun resolve(id: String?): PaletteDefinition = byId[id] ?: default
}

private fun PaletteSource.toSemanticPalette(): SemanticPalette {
    fun preferred(candidate: Int, container: Int): Int =
        if (contrastRatio(candidate, container) >= MIN_TEXT_CONTRAST) candidate else foreground
    val error = listOf(red, brightRed, foreground)
        .first { contrastRatio(it, background) >= MIN_TEXT_CONTRAST }
    return SemanticPalette(
        background, darkBackground, lighterBackground, foreground,
        preferred(lightForeground, lighterBackground), accent, readableForeground(accent),
        selection, readableOn(foreground, selection), darkBackground,
        readableOn(foreground, darkBackground), error, readableForeground(error),
    )
}

@Suppress("LongParameterList")
private fun p(id: String, name: String, mode: String, vararg colors: String): PaletteDefinition {
    require(mode == "dark" || mode == "light")
    require(colors.size == 9)
    val values = colors.map { (0xff000000L or it.toLong(16)).toInt() }
    return PaletteDefinition(
        id, name, if (mode == "dark") ThemeMode.DARK else ThemeMode.LIGHT,
        PaletteSource(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8]),
    )
}
