package org.thanosapollo.nema.ui.theme

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density

private val LocalSemanticPalette = staticCompositionLocalOf {
    semanticPalette(AppearanceSpec.DEFAULT, dark = false)
}

val LocalChatBackgroundUri = staticCompositionLocalOf<String?> { null }

object NemaTheme {
    val colors: SemanticPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalSemanticPalette.current
}

@Composable
fun NemaTheme(
    appearance: AppearanceSpec = AppearanceSpec.DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = appearance.isDark(isSystemInDarkTheme())
    val colors = semanticPalette(appearance, dark)
    val parentDensity = LocalDensity.current
    val scaledDensity = remember(parentDensity, appearance.textScale, appearance.uiScale) {
        val spec = appearance.sanitized()
        Density(
            density = parentDensity.density * spec.uiScale,
            fontScale = parentDensity.fontScale * appliedTextScale(spec.textScale),
        )
    }
    val darkBarIcons = systemBarUsesDarkIcons(colors.background)
    val view = LocalView.current
    SideEffect {
        (view.context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (darkBarIcons) {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            },
            navigationBarStyle = if (darkBarIcons) {
                SystemBarStyle.light(colors.background, colors.background)
            } else {
                SystemBarStyle.dark(colors.background)
            },
        )
    }
    CompositionLocalProvider(
        LocalSemanticPalette provides colors,
        LocalChatBackgroundUri provides appearance.backgroundUri,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(dark),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.background.toComposeColor(),
                content = content,
            )
        }
    }
}

fun Int.toComposeColor(): Color = Color(this)

internal fun systemBarUsesDarkIcons(background: Int): Boolean =
    readableForeground(background) == 0xFF000000.toInt()

private fun SemanticPalette.toMaterialColorScheme(dark: Boolean): ColorScheme {
    val common = if (dark) darkColorScheme() else lightColorScheme()
    return common.copy(
        primary = accent.toComposeColor(),
        onPrimary = onAccent.toComposeColor(),
        primaryContainer = outgoingBubble.toComposeColor(),
        onPrimaryContainer = onOutgoingBubble.toComposeColor(),
        secondary = accent.toComposeColor(),
        onSecondary = onAccent.toComposeColor(),
        secondaryContainer = incomingBubble.toComposeColor(),
        onSecondaryContainer = onIncomingBubble.toComposeColor(),
        background = background.toComposeColor(),
        onBackground = content.toComposeColor(),
        surface = surface.toComposeColor(),
        onSurface = content.toComposeColor(),
        surfaceVariant = surfaceElevated.toComposeColor(),
        onSurfaceVariant = mutedContent.toComposeColor(),
        surfaceTint = accent.toComposeColor(),
        inversePrimary = accent.toComposeColor(),
        inverseSurface = content.toComposeColor(),
        inverseOnSurface = background.toComposeColor(),
        outline = mutedContent.toComposeColor(),
        outlineVariant = mutedContent.toComposeColor(),
        scrim = Color.Black,
        surfaceBright = surface.toComposeColor(),
        surfaceDim = surface.toComposeColor(),
        surfaceContainerLowest = surface.toComposeColor(),
        surfaceContainerLow = surface.toComposeColor(),
        surfaceContainer = surface.toComposeColor(),
        surfaceContainerHigh = surface.toComposeColor(),
        surfaceContainerHighest = surface.toComposeColor(),
        error = error.toComposeColor(),
        onError = onError.toComposeColor(),
        errorContainer = error.toComposeColor(),
        onErrorContainer = onError.toComposeColor(),
    )
}

@Preview(name = "Neutral light", showBackground = true)
@Composable
private fun NeutralLightPreview() {
    AppearancePreview(AppearanceSpec(themeMode = ThemeMode.LIGHT))
}

@Preview(name = "Neutral dark", showBackground = true)
@Composable
private fun NeutralDarkPreview() {
    AppearancePreview(AppearanceSpec(themeMode = ThemeMode.DARK))
}

@Composable
private fun AppearancePreview(appearance: AppearanceSpec) {
    NemaTheme(appearance) {
        Text("Readable Nema appearance")
    }
}
