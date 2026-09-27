package org.thanosapollo.nema.ui.theme

import android.app.Activity
import android.app.Application
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import org.thanosapollo.nema.createRobolectricComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NemaThemeTest {
    @get:Rule
    val composeRule = createRobolectricComposeRule()

    @Test
    fun darkNeutralPaletteUsesTrueBlackAndSiteGold() {
        val colors = semanticPalette(AppearanceSpec.DEFAULT, dark = true)

        assertEquals(0xFF000000.toInt(), colors.background)
        assertEquals(0xFF0A0A0A.toInt(), colors.surface)
        assertEquals(0xFFD48A20.toInt(), colors.accent)
    }

    @Test
    fun darkNeutralMaterialChromeUsesTrueBlack() {
        lateinit var observed: ColorScheme
        composeRule.setContent {
            NemaTheme(AppearanceSpec(themeMode = ThemeMode.DARK)) {
                val colors = MaterialTheme.colorScheme
                SideEffect { observed = colors }
            }
        }

        composeRule.runOnIdle {
            val surface = 0xFF0A0A0A.toInt()
            assertEquals(surface, observed.surfaceBright.toArgb())
            assertEquals(surface, observed.surfaceDim.toArgb())
            assertEquals(surface, observed.surfaceContainerLowest.toArgb())
            assertEquals(surface, observed.surfaceContainerLow.toArgb())
            assertEquals(surface, observed.surfaceContainer.toArgb())
            assertEquals(surface, observed.surfaceContainerHigh.toArgb())
            assertEquals(surface, observed.surfaceContainerHighest.toArgb())
        }
    }

    @Test
    fun semanticPaletteUpdatesWithoutActivityRecreation() {
        lateinit var show: (AppearanceSpec) -> Unit
        var observed = 0
        var observedAccent = 0
        composeRule.setContent {
            var appearance by remember { mutableStateOf(AppearanceSpec(themeMode = ThemeMode.LIGHT)) }
            show = { appearance = it }
            NemaTheme(appearance = appearance) {
                val colors = NemaTheme.colors
                val accent = MaterialTheme.colorScheme.primary.toArgb()
                SideEffect {
                    observed = colors.background
                    observedAccent = accent
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(semanticPalette(AppearanceSpec.DEFAULT, dark = false).background, observed)
            assertEquals(semanticPalette(AppearanceSpec.DEFAULT, dark = false).accent, observedAccent)
        }

        composeRule.runOnIdle { show(AppearanceSpec(themeMode = ThemeMode.DARK)) }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(semanticPalette(AppearanceSpec.DEFAULT, dark = true).background, observed)
            assertEquals(semanticPalette(AppearanceSpec.DEFAULT, dark = true).accent, observedAccent)
        }
    }

    @Test
    @Config(sdk = [26], qualifiers = "notnight")
    fun darkAppearanceOwnsLegacyNavigationBarContrast() {
        assertLegacyNavigationBar(AppearanceSpec(themeMode = ThemeMode.DARK), dark = true)
    }

    @Test
    @Config(sdk = [28], qualifiers = "night")
    fun lightAppearanceOwnsLegacyNavigationBarContrast() {
        assertLegacyNavigationBar(AppearanceSpec(themeMode = ThemeMode.LIGHT), dark = false)
    }

    @Test
    @Config(sdk = [26], qualifiers = "notnight")
    fun systemBarIconsFollowWhiteCustomBackgroundInsteadOfDarkMode() {
        assertLegacyNavigationBar(
            AppearanceSpec(
                themeMode = ThemeMode.DARK,
                palette = PaletteChoice.Custom.require("#000000", "#FFFFFF"),
            ),
            dark = true,
        )
    }

    @Test
    @Config(sdk = [26], qualifiers = "notnight")
    fun systemBarIconsFollowBlackCustomBackgroundInsteadOfLightMode() {
        assertLegacyNavigationBar(
            AppearanceSpec(
                themeMode = ThemeMode.LIGHT,
                palette = PaletteChoice.Custom.require("#FFFFFF", "#000000"),
            ),
            dark = false,
        )
    }

    @Test
    fun acceptedCustomColorsKeepActualMaterialRolePairsReadable() {
        lateinit var show: (AppearanceSpec) -> Unit
        lateinit var observed: ColorScheme
        composeRule.setContent {
            var appearance by remember {
                mutableStateOf(
                    AppearanceSpec(palette = PaletteChoice.Custom.require("#FFFFFF", "#595959")),
                )
            }
            show = { appearance = it }
            NemaTheme(appearance) {
                val colors = MaterialTheme.colorScheme
                SideEffect { observed = colors }
            }
        }

        composeRule.runOnIdle { assertReadable(observed) }
        composeRule.runOnIdle {
            show(
                AppearanceSpec(
                    themeMode = ThemeMode.DARK,
                    palette = PaletteChoice.Custom.require("#000000", "#FFFFFF"),
                ),
            )
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertReadable(observed) }
    }

    @Suppress("DEPRECATION")
    private fun assertLegacyNavigationBar(appearance: AppearanceSpec, dark: Boolean) {
        lateinit var observed: Pair<Int, Boolean>
        composeRule.setContent {
            NemaTheme(appearance) {
                val view = LocalView.current
                SideEffect {
                    val activity = view.context as Activity
                    observed = activity.window.navigationBarColor to
                        WindowCompat.getInsetsController(activity.window, view)
                            .isAppearanceLightNavigationBars
                }
            }
        }

        composeRule.runOnIdle {
            val background = semanticPalette(appearance, dark).background
            assertEquals(background, observed.first)
            assertEquals(systemBarUsesDarkIcons(background), observed.second)
        }
    }

    private fun assertReadable(colors: ColorScheme) {
        fun assertPair(foreground: Int, background: Int) {
            assertTrue(contrastRatio(foreground, background) >= MIN_TEXT_CONTRAST)
        }
        assertPair(colors.onPrimary.toArgb(), colors.primary.toArgb())
        assertPair(colors.onPrimaryContainer.toArgb(), colors.primaryContainer.toArgb())
        assertPair(colors.onSecondary.toArgb(), colors.secondary.toArgb())
        assertPair(colors.onSecondaryContainer.toArgb(), colors.secondaryContainer.toArgb())
        assertPair(colors.onBackground.toArgb(), colors.background.toArgb())
        assertPair(colors.onSurface.toArgb(), colors.surface.toArgb())
        assertPair(colors.onSurfaceVariant.toArgb(), colors.surfaceVariant.toArgb())
        assertPair(colors.onSurface.toArgb(), colors.surfaceBright.toArgb())
        assertPair(colors.onSurface.toArgb(), colors.surfaceDim.toArgb())
        assertPair(colors.onSurface.toArgb(), colors.surfaceContainerLowest.toArgb())
        assertPair(colors.onSurface.toArgb(), colors.surfaceContainerLow.toArgb())
        assertPair(colors.onSurface.toArgb(), colors.surfaceContainer.toArgb())
        assertPair(colors.onSurface.toArgb(), colors.surfaceContainerHigh.toArgb())
        assertPair(colors.onSurface.toArgb(), colors.surfaceContainerHighest.toArgb())
        assertPair(colors.inverseOnSurface.toArgb(), colors.inverseSurface.toArgb())
        assertPair(colors.error.toArgb(), colors.background.toArgb())
        assertPair(colors.onError.toArgb(), colors.error.toArgb())
        assertPair(colors.onErrorContainer.toArgb(), colors.errorContainer.toArgb())
    }
}
