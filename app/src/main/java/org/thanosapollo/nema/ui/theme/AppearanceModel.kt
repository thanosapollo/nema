package org.thanosapollo.nema.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

const val MIN_TEXT_CONTRAST = 4.5
const val MIN_ACCENT_CONTRAST = 4.5
const val MIN_BACKGROUND_CONTRAST = 7.0
const val MIN_TEXT_SCALE = 0.85f
const val MAX_TEXT_SCALE = 1.60f
const val MIN_UI_SCALE = 0.90f
const val MAX_UI_SCALE = 1.30f
const val BASE_TEXT_SCALE = 1.15f

val TEXT_SCALE_CHOICES = listOf(0.85f, 1.0f, 1.15f, 1.30f)
val UI_SCALE_CHOICES = listOf(0.90f, 1.0f, 1.10f, 1.20f)

const val DEFAULT_CUSTOM_PRIMARY = "#315DA8"
const val DEFAULT_CUSTOM_BACKGROUND = "#F7F8FA"

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

sealed interface PaletteChoice {
    data object Neutral : PaletteChoice

    class Custom private constructor(
        val primary: Int,
        val background: Int,
    ) : PaletteChoice {
        fun primaryHex(): String = primary.toHexColor()

        fun backgroundHex(): String = background.toHexColor()

        override fun equals(other: Any?): Boolean =
            other is Custom && primary == other.primary && background == other.background

        override fun hashCode(): Int = 31 * primary + background

        override fun toString(): String = "Custom(primary=${primaryHex()}, background=${backgroundHex()})"

        companion object {
            fun create(primary: String, background: String): Custom? {
                val parsedPrimary = parseHexColor(primary) ?: return null
                val parsedBackground = parseHexColor(background) ?: return null
                return create(parsedPrimary, parsedBackground)
            }

            fun require(primary: String, background: String): Custom =
                requireNotNull(create(primary, background)) { "Custom palette needs readable contrast" }

            internal fun create(primary: Int, background: Int): Custom? {
                if (contrastRatio(primary, background) < MIN_ACCENT_CONTRAST) return null
                if (contrastRatio(readableForeground(background), background) < MIN_BACKGROUND_CONTRAST) {
                    return null
                }
                return Custom(primary, background)
            }
        }
    }
}

data class AppearanceSpec(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: PaletteChoice = PaletteChoice.Neutral,
    val backgroundUri: String? = null,
    val textScale: Float = 1f,
    val uiScale: Float = 1f,
) {
    fun isDark(systemDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    fun sanitized(): AppearanceSpec = copy(
        textScale = sanitizeScale(textScale, MIN_TEXT_SCALE, MAX_TEXT_SCALE),
        uiScale = sanitizeScale(uiScale, MIN_UI_SCALE, MAX_UI_SCALE),
    )

    companion object {
        val DEFAULT = AppearanceSpec()
    }
}

fun sanitizeScale(value: Float, minimum: Float, maximum: Float): Float =
    if (value.isFinite()) value.coerceIn(minimum, maximum) else 1f

fun appliedTextScale(stored: Float): Float =
    sanitizeScale(stored, MIN_TEXT_SCALE, MAX_TEXT_SCALE) * BASE_TEXT_SCALE

fun nearestScaleChoice(value: Float, choices: List<Float>): Float =
    choices.minBy { kotlin.math.abs(it - sanitizeScale(value, choices.first(), choices.last())) }

fun resolveAppearance(
    app: AppearanceSpec,
    account: AppearanceSpec?,
    conversation: AppearanceSpec?,
): AppearanceSpec = conversation ?: account ?: app

sealed interface AppearanceScope {
    data object App : AppearanceScope

    data class Account(val accountId: String) : AppearanceScope {
        init {
            require(accountId.isNotBlank()) { "Account ID must not be blank" }
        }
    }

    data class Conversation(
        val accountId: String,
        val canonicalBarePeer: String,
    ) : AppearanceScope {
        init {
            require(accountId.isNotBlank()) { "Account ID must not be blank" }
            require(canonicalBarePeer.isNotBlank()) { "Conversation peer must not be blank" }
        }
    }
}

data class SemanticPalette(
    val background: Int,
    val surface: Int,
    val surfaceElevated: Int,
    val content: Int,
    val mutedContent: Int,
    val accent: Int,
    val onAccent: Int,
    val outgoingBubble: Int,
    val onOutgoingBubble: Int,
    val incomingBubble: Int,
    val onIncomingBubble: Int,
    val error: Int,
    val onError: Int,
)

fun semanticPalette(spec: AppearanceSpec, dark: Boolean): SemanticPalette {
    val custom = spec.palette as? PaletteChoice.Custom
    if (custom != null) {
        val background = custom.background
        val content = readableForeground(background)
        val surface = blend(background, content, 0.04)
        val elevated = blend(background, content, 0.10)
        val outgoing = blend(background, custom.primary, 0.28)
        val incoming = blend(background, content, 0.12)
        val error = readableRole(
            candidate = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt(),
            container = background,
        )
        return SemanticPalette(
            background = background,
            surface = surface,
            surfaceElevated = elevated,
            content = content,
            mutedContent = readableRole(blend(content, background, 0.30), elevated),
            accent = custom.primary,
            onAccent = readableForeground(custom.primary),
            outgoingBubble = outgoing,
            onOutgoingBubble = readableForeground(outgoing),
            incomingBubble = incoming,
            onIncomingBubble = readableForeground(incoming),
            error = error,
            onError = readableForeground(error),
        )
    }
    return if (dark) DARK_NEUTRAL else LIGHT_NEUTRAL
}

fun contrastRatio(foreground: Int, background: Int): Double {
    val lighter = max(relativeLuminance(foreground), relativeLuminance(background))
    val darker = min(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val value = ((argb shr shift) and 0xFF) / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

internal fun readableForeground(background: Int): Int {
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    return if (contrastRatio(black, background) >= contrastRatio(white, background)) black else white
}

private fun readableRole(candidate: Int, container: Int): Int =
    if (contrastRatio(candidate, container) >= MIN_TEXT_CONTRAST) candidate else readableForeground(container)

private fun blend(base: Int, overlay: Int, overlayFraction: Double): Int {
    fun channel(shift: Int): Int {
        val baseChannel = (base shr shift) and 0xFF
        val overlayChannel = (overlay shr shift) and 0xFF
        return (baseChannel * (1.0 - overlayFraction) + overlayChannel * overlayFraction).toInt()
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private fun parseHexColor(value: String): Int? {
    if (!value.matches(Regex("#[0-9A-Fa-f]{6}"))) return null
    return (0xFF000000L or value.drop(1).toLong(16)).toInt()
}

private fun Int.toHexColor(): String = "#%06X".format(this and 0xFFFFFF)

private val LIGHT_NEUTRAL = SemanticPalette(
    background = 0xFFF7F7F8.toInt(),
    surface = 0xFFFFFFFF.toInt(),
    surfaceElevated = 0xFFEEEFF2.toInt(),
    content = 0xFF19191B.toInt(),
    mutedContent = 0xFF5E5E66.toInt(),
    accent = 0xFF315DA8.toInt(),
    onAccent = 0xFFFFFFFF.toInt(),
    outgoingBubble = 0xFFDCE7FB.toInt(),
    onOutgoingBubble = 0xFF14233D.toInt(),
    incomingBubble = 0xFF000000.toInt(),
    onIncomingBubble = 0xFFFFFFFF.toInt(),
    error = 0xFFB3261E.toInt(),
    onError = 0xFFFFFFFF.toInt(),
)

private val DARK_NEUTRAL = SemanticPalette(
    background = 0xFF000000.toInt(),
    surface = 0xFF000000.toInt(),
    surfaceElevated = 0xFF1C1C1E.toInt(),
    content = 0xFFF5F5F7.toInt(),
    mutedContent = 0xFFB8B8C0.toInt(),
    accent = 0xFF2C6BED.toInt(),
    onAccent = 0xFFFFFFFF.toInt(),
    outgoingBubble = 0xFF2C6BED.toInt(),
    onOutgoingBubble = 0xFFFFFFFF.toInt(),
    incomingBubble = 0xFF000000.toInt(),
    onIncomingBubble = 0xFFFFFFFF.toInt(),
    error = 0xFFF2B8B5.toInt(),
    onError = 0xFF601410.toInt(),
)
