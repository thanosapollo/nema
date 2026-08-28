package org.thanosapollo.nema.ui.theme

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PaletteCatalogTest {
    @Test
    fun `catalog exactly matches pinned color sources`() {
        val expected = EXPECTED.lines().associate { line ->
            val fields = line.split('|')
            fields.first() to fields.drop(1)
        }

        assertEquals(expected.keys, PaletteCatalog.entries.map { it.id }.toSet())
        PaletteCatalog.entries.forEach { palette ->
            val source = palette.source
            assertEquals(
                listOf(
                    palette.displayName,
                    palette.mode.name.lowercase(),
                    source.background.hex(), source.darkBackground.hex(),
                    source.lighterBackground.hex(), source.foreground.hex(),
                    source.lightForeground.hex(), source.accent.hex(), source.selection.hex(),
                    source.red.hex(), source.brightRed.hex(),
                ),
                expected.getValue(palette.id),
            )
        }
    }

    @Test
    fun `Rum is the unique stable Default and unknown ids fail closed`() {
        assertEquals(23, PaletteCatalog.entries.size)
        assertEquals(23, PaletteCatalog.entries.map { it.id }.distinct().size)
        assertEquals(23, PaletteCatalog.entries.map { it.displayName }.distinct().size)
        assertEquals(
            setOf("background", "darkBackground", "lighterBackground", "foreground", "lightForeground", "accent", "selection", "red", "brightRed"),
            PaletteSource::class.java.declaredFields.filterNot { it.isSynthetic || it.name.startsWith("$") }.map { it.name }.toSet(),
        )
        assertEquals("rum", PaletteCatalog.default.id)
        assertEquals("Default", PaletteCatalog.default.displayName)
        assertSame(PaletteCatalog.default, PaletteCatalog.resolve(null))
        assertSame(PaletteCatalog.default, PaletteCatalog.resolve("removed-theme"))
    }

    @Test
    fun `every rendered semantic role pair is readable`() {
        PaletteCatalog.entries.forEach { palette ->
            val colors = palette.colors
            val source = palette.source
            assertEquals(source.background, colors.background)
            assertEquals(source.darkBackground, colors.surface)
            assertEquals(source.lighterBackground, colors.surfaceElevated)
            assertEquals(source.foreground, colors.content)
            assertEquals(source.accent, colors.accent)
            assertEquals(if (contrastRatio(source.lightForeground, source.lighterBackground) >= 4.5) source.lightForeground else source.foreground, colors.mutedContent)
            assertEquals(readableForeground(source.accent), colors.onAccent)
            assertEquals(source.selection, colors.outgoingBubble)
            assertEquals(readableOn(source.foreground, source.selection), colors.onOutgoingBubble)
            assertEquals(source.darkBackground, colors.incomingBubble)
            assertEquals(readableOn(source.foreground, source.darkBackground), colors.onIncomingBubble)
            assertEquals(
                listOf(source.red, source.brightRed, source.foreground)
                    .first { contrastRatio(it, source.background) >= 4.5 },
                colors.error,
            )
            listOf(
                colors.content to colors.background,
                colors.content to colors.surface,
                colors.mutedContent to colors.surfaceElevated,
                colors.onAccent to colors.accent,
                colors.onOutgoingBubble to colors.outgoingBubble,
                colors.onIncomingBubble to colors.incomingBubble,
                colors.error to colors.background,
                colors.onError to colors.error,
            ).forEach { (foreground, container) ->
                assertTrue("${palette.id}: ${foreground.hex()} on ${container.hex()}", contrastRatio(foreground, container) >= 4.5)
            }
        }
    }

    @Test
    fun `packaged notice pins Omarchy and keeps Rum provenance separate`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val notice = context.assets.open("third-party/Omarchy-MIT-NOTICE.txt")
            .bufferedReader().use { it.readText() }
        assertTrue(notice.contains("Copyright (c) David Heinemeier Hansson"))
        assertTrue(notice.contains("https://github.com/thanosapollo/omarchy"))
        assertTrue(notice.contains("b7771e607b9f374891b97e03d10f69abc322535f"))
        assertTrue(notice.contains("22 stock palettes"))
        assertTrue(notice.contains("Rum") && notice.contains("Thanos Apollo"))
    }

    private fun Int.hex(): String = "#%06x".format(this and 0xffffff)

    private companion object {
        val EXPECTED = """
rum|Default|dark|#000000|#0a0a0a|#111111|#d4c5a0|#c8b890|#d48a20|#3a2914|#ff5f59|#ff6b55
catppuccin-latte|Catppuccin Latte|light|#eff1f5|#e3e4e8|#dce0e8|#4c4f69|#5c5f77|#1e66f5|#ccd0da|#d20f39|#d20f39
catppuccin|Catppuccin|dark|#1e1e2e|#161622|#313244|#cdd6f4|#bac2de|#89b4fa|#45475a|#f38ba8|#f38ba8
ethereal|Ethereal|dark|#060b1e|#040816|#131a3a|#ffcead|#c9b8a6|#7d82d9|#252e56|#ed5b5a|#faaaa9
everforest|Everforest|dark|#2d353b|#21272c|#343f44|#d3c6aa|#9da9a0|#7fbbb3|#3d484d|#e67e80|#e67e80
flexoki-light|Flexoki Light|light|#fffcf0|#f2efe4|#e6e4d9|#100f0f|#403e3c|#205ea6|#cecdc3|#d14d41|#d14d41
gruvbox|Gruvbox|dark|#282828|#1e1e1e|#3c3836|#d4be98|#bdae93|#7daea3|#504945|#ea6962|#ea6962
hackerman|Hackerman|dark|#0b0c16|#080910|#151828|#ddf7ff|#b5c5db|#82fb9c|#1f253a|#50f872|#85ff9d
kanagawa|Kanagawa|dark|#1f1f28|#17171e|#223249|#dcd7ba|#c8c093|#dcd7ba|#363646|#c34043|#e82424
last-horizon|Last Horizon|dark|#0c0b0c|#090809|#0c0b0c|#fafcfb|#cfd3cd|#b59790|#584e51|#c38b7b|#c38b7b
lumon|Lumon|dark|#16242d|#101b21|#1b2d40|#d6e2ee|#d6e2ee|#8bc9eb|#243d56|#4d86b0|#73a6cb
lupine|Lupine|light|#fafafa|#ececec|#f5f5f5|#212121|#424242|#3264eb|#d0d0d0|#c900c4|#f930fb
matte-black|Matte Black|dark|#121212|#0d0d0d|#1e1e1e|#bebebe|#8a8a8d|#e68e0d|#2a2a2a|#d35f5f|#b91c1c
miasma|Miasma|dark|#222222|#191919|#2c2c2c|#c2c2b0|#8a8a7e|#78824b|#383838|#685742|#685742
nord|Nord|dark|#2e3440|#222730|#3b4252|#d8dee9|#adb5c4|#81a1c1|#434c5e|#bf616a|#bf616a
osaka-jade|Osaka Jade|dark|#111c18|#0c1512|#23372b|#c1c497|#d6d5bc|#509475|#32473b|#ff5345|#db9f9c
retro-82|Retro 82|dark|#05182e|#031222|#0a2540|#f6dcac|#a7c9c6|#faa968|#134e5a|#f85525|#f85525
ristretto|Ristretto|dark|#2c2525|#211b1b|#3d2f2a|#e6d9db|#c3b7b8|#f38d70|#403e41|#fd6883|#ff8297
rose-pine|Rose Pine|light|#faf4ed|#ede7e1|#f2e9e1|#575279|#6e6a86|#56949f|#dfdad9|#b4637a|#b4637a
solitude|Solitude|dark|#101315|#0c0e10|#101315|#cacccc|#cbc2be|#798186|#343d41|#565d60|#de6145
tokyo-night|Tokyo Night|dark|#1a1b26|#13141c|#24283b|#a9b1d6|#b4bee6|#7aa2f7|#292e42|#f7768e|#ff7a93
vantablack|Vantablack|dark|#000000|#090909|#1a1a1a|#ffffff|#ececec|#8d8d8d|#1a1a1a|#a4a4a4|#a4a4a4
white|White|light|#ffffff|#f5f5f5|#c0c0c0|#000000|#000000|#6e6e6e|#c0c0c0|#2a2a2a|#2a2a2a
        """.trimIndent()
    }
}
