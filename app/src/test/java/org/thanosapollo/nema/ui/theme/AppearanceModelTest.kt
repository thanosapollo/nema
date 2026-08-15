package org.thanosapollo.nema.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceModelTest {
    @Test
    fun defaultIsNeutralSystemAndExplicitModesResolveDeterministically() {
        assertEquals(AppearanceSpec(), AppearanceSpec.DEFAULT)
        assertEquals(false, AppearanceSpec.DEFAULT.isDark(systemDark = false))
        assertEquals(true, AppearanceSpec.DEFAULT.isDark(systemDark = true))
        assertEquals(1f, AppearanceSpec.DEFAULT.textScale)
        assertEquals(1f, AppearanceSpec.DEFAULT.uiScale)
        assertEquals(1.15f, appliedTextScale(1f), 0.0001f)
        assertEquals(1.15f * 1.15f, appliedTextScale(1.15f), 0.0001f)
        assertEquals(1.0f, nearestScaleChoice(1.07f, TEXT_SCALE_CHOICES))
        assertEquals(MAX_TEXT_SCALE, sanitizeScale(9f, MIN_TEXT_SCALE, MAX_TEXT_SCALE))
        assertEquals(MIN_UI_SCALE, sanitizeScale(0.1f, MIN_UI_SCALE, MAX_UI_SCALE))
        assertEquals(false, AppearanceSpec(themeMode = ThemeMode.LIGHT).isDark(systemDark = true))
        assertEquals(true, AppearanceSpec(themeMode = ThemeMode.DARK).isDark(systemDark = false))
    }

    @Test
    fun conversationWinsAccountWhichWinsAppAndThreadsNeedNoAppearanceIdentity() {
        val app = AppearanceSpec(themeMode = ThemeMode.LIGHT)
        val account = AppearanceSpec(themeMode = ThemeMode.DARK)
        val conversation = AppearanceSpec(
            themeMode = ThemeMode.SYSTEM,
            palette = PaletteChoice.Custom.require("#315DA8", "#F7F8FA"),
        )
        assertEquals(app, resolveAppearance(app, account = null, conversation = null))
        assertEquals(account, resolveAppearance(app, account, conversation = null))
        assertEquals(conversation, resolveAppearance(app, account, conversation))
    }

    @Test
    fun customPaletteRejectsBelowTextContrastBoundaryAndAcceptsAtOrAboveIt() {
        assertNull(PaletteChoice.Custom.create("#777777", "#FFFFFF"))
        val accepted = PaletteChoice.Custom.create("#767676", "#FFFFFF")
        assertTrue(accepted != null)
        assertTrue(
            contrastRatio(0xFF767676.toInt(), 0xFFFFFFFF.toInt()) >= MIN_ACCENT_CONTRAST,
        )
        assertNull(PaletteChoice.Custom.create("#000000", "#777777"))
    }

    @Test
    fun defaultIncomingBubbleIsBlackWithWhiteText() {
        val light = semanticPalette(AppearanceSpec.DEFAULT, dark = false)
        val dark = semanticPalette(AppearanceSpec.DEFAULT, dark = true)
        assertEquals(0xFF000000.toInt(), light.incomingBubble)
        assertEquals(0xFFFFFFFF.toInt(), light.onIncomingBubble)
        assertEquals(0xFF000000.toInt(), dark.incomingBubble)
        assertEquals(0xFFFFFFFF.toInt(), dark.onIncomingBubble)
    }

    @Test
    fun everyBuiltInAndCustomTextRoleMeetsReadableContrast() {
        val specs = listOf(
            AppearanceSpec.DEFAULT,
            AppearanceSpec(themeMode = ThemeMode.DARK),
            AppearanceSpec(palette = PaletteChoice.Custom.require("#315DA8", "#F7F8FA")),
            AppearanceSpec(palette = PaletteChoice.Custom.require("#FFFFFF", "#595959")),
            AppearanceSpec(
                themeMode = ThemeMode.DARK,
                palette = PaletteChoice.Custom.require("#000000", "#FFFFFF"),
            ),
            AppearanceSpec(
                themeMode = ThemeMode.DARK,
                palette = PaletteChoice.Custom.require("#B7C8FF", "#111318"),
            ),
        )

        specs.forEach { spec ->
            val colors = semanticPalette(spec, spec.isDark(systemDark = false))
            assertReadable(colors)
        }
    }

    @Test
    fun acceptedCustomPaletteAlwaysProducesReadableRenderedRoles() {
        val colors = listOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF595959.toInt(),
            0xFF315DA8.toInt(),
            0xFFB7C8FF.toInt(),
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FFFF.toInt(),
            0xFFFF00FF.toInt(),
        )

        colors.forEach { primary ->
            colors.forEach { background ->
                val custom = PaletteChoice.Custom.create(primary, background) ?: return@forEach
                listOf(false, true).forEach { dark ->
                    assertReadable(semanticPalette(AppearanceSpec(palette = custom), dark))
                }
            }
        }
    }

    private fun assertReadable(colors: SemanticPalette) {
        assertTrue(contrastRatio(colors.content, colors.background) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(colors.content, colors.surface) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(colors.onAccent, colors.accent) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(colors.onOutgoingBubble, colors.outgoingBubble) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(colors.onIncomingBubble, colors.incomingBubble) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(colors.mutedContent, colors.surfaceElevated) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(colors.error, colors.background) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(colors.onError, colors.error) >= MIN_TEXT_CONTRAST)
    }
}
