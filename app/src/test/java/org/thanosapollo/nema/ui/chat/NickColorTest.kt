package org.thanosapollo.nema.ui.chat

import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.ui.theme.contrastRatio

class NickColorTest {
    @Test
    fun hueMatchesXep0392Vectors() {
        val vectors = listOf(
            "Romeo" to 327.255249,
            "juliet@capulet.lit" to 209.410400,
            "😺" to 331.199341,
            "council" to 359.994507,
            "Board" to 171.430664,
        )
        for ((nick, expected) in vectors) {
            assertTrue(abs(nickHue(nick) - expected) < 0.0001)
        }
    }

    @Test
    fun hsluvMatchesXep0392RgbVectors() {
        val vectors = listOf(
            "Romeo" to Triple(0.865, 0.000, 0.686),
            "juliet@capulet.lit" to Triple(0.000, 0.515, 0.573),
            "😺" to Triple(0.872, 0.000, 0.659),
            "council" to Triple(0.918, 0.000, 0.394),
            "Board" to Triple(0.000, 0.527, 0.457),
        )
        for ((nick, expected) in vectors) {
            val actual = hsluvRgb(nickHue(nick), 100.0, 50.0)
            assertTrue(abs(actual.first - expected.first) < 0.001)
            assertTrue(abs(actual.second - expected.second) < 0.001)
            assertTrue(abs(actual.third - expected.third) < 0.001)
        }
    }

    @Test
    fun displayHueStaysNearXepHue() {
        for (nick in listOf("wanderer000", "wanderer017", "wanderer178", "wanderer001", "wanderer209", "willow205")) {
            val parts = nickColorComponents(nick)
            val distance = min((parts.hue - parts.displayHue).mod(360.0), (parts.displayHue - parts.hue).mod(360.0))
            assertTrue(distance <= 18.0)
        }
    }

    @Test
    fun colorComponentsAreStable() {
        val parts = nickColorComponents("wanderer000")
        assertEquals(21.5386962890625, parts.hue, 0.000001)
        assertEquals(36.36425671633333, parts.displayHue, 0.000001)
        assertEquals(96.86274509803921, parts.saturation, 0.000001)
        assertEquals(0.01568627450980392, parts.variation, 0.000001)
    }

    @Test
    fun nickColorsStayReadableOnCommonBackgrounds() {
        val backgrounds = listOf(0xFF000000.toInt(), 0xFF202020.toInt(), 0xFFEEEEEE.toInt(), 0xFFFFFFFF.toInt())
        for (background in backgrounds) {
            for (hue in listOf(15.0, 60.0, 105.0, 150.0, 195.0, 240.0, 285.0, 330.0)) {
                val color = nickColor(hue, 100.0, 0.5, background)
                assertTrue(contrastRatio(color, background) >= 4.5)
            }
        }
    }

    @Test
    fun sameNickKeepsTheSameColorOnTheSameBackground() {
        val background = 0xFF202020.toInt()
        assertEquals(mucNickColor("alice", background), mucNickColor("alice", background))
    }
}
