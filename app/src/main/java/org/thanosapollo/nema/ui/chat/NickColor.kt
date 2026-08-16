package org.thanosapollo.nema.ui.chat

import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.thanosapollo.nema.ui.theme.MIN_TEXT_CONTRAST
import org.thanosapollo.nema.ui.theme.contrastRatio

data class NickColorComponents(
    val hue: Double,
    val displayHue: Double,
    val saturation: Double,
    val variation: Double,
)

fun nickColorComponents(nickname: String): NickColorComponents {
    val hash = MessageDigest.getInstance("SHA-1").digest(nickname.toByteArray(Charsets.UTF_8))
    val value = (hash[0].toInt() and 0xFF) + ((hash[1].toInt() and 0xFF) shl 8)
    val hue = 360.0 * value / 65536.0
    val offsetValue = (hash[4].toLong() and 0xFF) +
        ((hash[5].toLong() and 0xFF) shl 8) +
        ((hash[6].toLong() and 0xFF) shl 16) +
        ((hash[7].toLong() and 0xFF) shl 24)
    val offset = 36.0 * offsetValue / 4_294_967_296.0 - 18.0
    return NickColorComponents(
        hue = hue,
        displayHue = (hue + offset).mod(360.0),
        saturation = 60.0 + 40.0 * (hash[2].toInt() and 0xFF) / 255.0,
        variation = (hash[3].toInt() and 0xFF) / 255.0,
    )
}

fun nickHue(nickname: String): Double = nickColorComponents(nickname).hue

fun hsluvRgb(hue: Double, saturation: Double, lightness: Double): Triple<Double, Double, Double> {
    if (lightness < 0.00000001) return Triple(0.0, 0.0, 0.0)
    if (lightness > 99.9999999) return Triple(1.0, 1.0, 1.0)
    val angle = PI * hue / 180.0
    val chroma = hsluvMaxChroma(lightness, hue) * (saturation / 100.0)
    val u = cos(angle) * chroma
    val v = sin(angle) * chroma
    val y = if (lightness <= 8.0) lightness / 903.2962962 else ((lightness + 16.0) / 116.0).pow(3)
    val varU = u / (13.0 * lightness) + 0.19783000664283
    val varV = v / (13.0 * lightness) + 0.46831999493879
    val x = 9.0 * y * varU / (4.0 * varV)
    val z = (9.0 * y - 15.0 * varV * y - varV * x) / (3.0 * varV)
    return Triple(
        fromLinear(3.240969941904521 * x - 1.537383177570093 * y - 0.498610760293 * z),
        fromLinear(-0.96924363628087 * x + 1.87596750150772 * y + 0.041555057407175 * z),
        fromLinear(0.055630079696993 * x - 0.20397695888897 * y + 1.056971514242878 * z),
    )
}

fun mucNickColor(nickname: String, backgroundArgb: Int): Int {
    val parts = nickColorComponents(nickname)
    return nickColor(parts.displayHue, parts.saturation, parts.variation, backgroundArgb)
}

internal fun nickColor(
    hue: Double,
    saturation: Double,
    variation: Double,
    backgroundArgb: Int,
): Int {
    val white = 0xFFFFFFFF.toInt()
    val black = 0xFF000000.toInt()
    val lighterBackground = contrastRatio(white, backgroundArgb) > contrastRatio(black, backgroundArgb)
    val preferred = if (lighterBackground) 64.0 + 18.0 * variation else 46.0 - 18.0 * variation
    val lightnesses = buildList {
        add(preferred)
        if (lighterBackground) {
            var step = 5.0 * kotlin.math.ceil(preferred / 5.0)
            while (step <= 95.0) {
                add(step)
                step += 5.0
            }
        } else {
            var step = 5.0 * kotlin.math.floor(preferred / 5.0)
            while (step >= 5.0) {
                add(step)
                step -= 5.0
            }
        }
    }
    return lightnesses.firstNotNullOfOrNull { lightness ->
        val argb = rgbToArgb(hsluvRgb(hue, saturation, lightness))
        argb.takeIf { contrastRatio(it, backgroundArgb) >= MIN_TEXT_CONTRAST }
    } ?: if (lighterBackground) white else black
}

private val hsluvMatrix = arrayOf(
    doubleArrayOf(3.240969941904521, -1.537383177570093, -0.498610760293),
    doubleArrayOf(-0.96924363628087, 1.87596750150772, 0.041555057407175),
    doubleArrayOf(0.055630079696993, -0.20397695888897, 1.056971514242878),
)

private fun hsluvMaxChroma(lightness: Double, hue: Double): Double {
    val angle = PI * hue / 180.0
    var minimum = Double.POSITIVE_INFINITY
    for ((slope, intercept) in hsluvBounds(lightness)) {
        val divisor = sin(angle) - slope * cos(angle)
        if (divisor == 0.0) continue
        val length = intercept / divisor
        if (length >= 0.0 && length < minimum) minimum = length
    }
    return minimum
}

private fun hsluvBounds(lightness: Double): List<Pair<Double, Double>> {
    val sub1 = (lightness + 16.0).pow(3) / 1_560_896.0
    val sub2 = if (sub1 > 0.0088564516) sub1 else lightness / 903.2962962
    val bounds = ArrayList<Pair<Double, Double>>(6)
    for (row in hsluvMatrix) {
        val m1 = row[0]
        val m2 = row[1]
        val m3 = row[2]
        for (channel in 0..1) {
            val top1 = (284517.0 * m1 - 94839.0 * m3) * sub2
            val top2 = ((838422.0 * m3 + 769860.0 * m2 + 731718.0 * m1) * lightness * sub2) -
                (769860.0 * channel * lightness)
            val bottom = ((632260.0 * m3 - 126452.0 * m2) * sub2) + (126452.0 * channel)
            bounds += top1 / bottom to top2 / bottom
        }
    }
    return bounds
}

private fun fromLinear(channel: Double): Double =
    if (channel <= 0.0031308) 12.92 * channel else 1.055 * channel.pow(1.0 / 2.4) - 0.055

private fun rgbToArgb(rgb: Triple<Double, Double, Double>): Int {
    fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    return (0xFF shl 24) or
        (channel(rgb.first) shl 16) or
        (channel(rgb.second) shl 8) or
        channel(rgb.third)
}
