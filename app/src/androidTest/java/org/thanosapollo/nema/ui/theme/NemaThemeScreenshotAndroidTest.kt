package org.thanosapollo.nema.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NemaThemeScreenshotAndroidTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun neutralLightAndDarkRenderExactSemanticBackgrounds() {
        lateinit var show: (AppearanceSpec) -> Unit
        composeRule.setContent {
            var appearance by remember {
                mutableStateOf(AppearanceSpec(themeMode = ThemeMode.LIGHT))
            }
            show = { appearance = it }
            NemaTheme(appearance) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(NemaTheme.colors.background.toComposeColor())
                        .testTag("semantic-screenshot"),
                )
            }
        }
        assertRenderedBackground(AppearanceSpec(themeMode = ThemeMode.LIGHT), dark = false)

        composeRule.runOnIdle { show(AppearanceSpec(themeMode = ThemeMode.DARK)) }
        composeRule.waitForIdle()
        assertRenderedBackground(AppearanceSpec(themeMode = ThemeMode.DARK), dark = true)
    }

    private fun assertRenderedBackground(appearance: AppearanceSpec, dark: Boolean) {
        val image = composeRule.onNodeWithTag("semantic-screenshot").captureToImage()
        assertEquals(
            semanticPalette(appearance, dark).background,
            image.toPixelMap()[image.width / 2, image.height / 2].toArgb(),
        )
    }
}
