package org.thanosapollo.nema.ui.chat

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thanosapollo.nema.ui.theme.AppearanceSpec
import org.thanosapollo.nema.ui.theme.MIN_TEXT_CONTRAST
import org.thanosapollo.nema.ui.theme.PaletteChoice
import org.thanosapollo.nema.ui.theme.contrastRatio
import org.thanosapollo.nema.ui.theme.semanticPalette

class ReceiptTickColorTest {
    @Test
    fun deliveredAndReadTicksDifferAndMeetContrastOnOutgoingBubble() {
        val palettes = listOf(
            semanticPalette(AppearanceSpec.DEFAULT, dark = false),
            semanticPalette(AppearanceSpec.DEFAULT, dark = true),
            semanticPalette(
                AppearanceSpec(palette = PaletteChoice.Custom.require("#315DA8", "#F7F8FA")),
                dark = false,
            ),
            semanticPalette(
                AppearanceSpec(palette = PaletteChoice.Custom.require("#B7C8FF", "#111318")),
                dark = true,
            ),
        )
        palettes.forEach { palette ->
            val background = palette.outgoingBubble
            val delivered = receiptTickColor(read = false, bubbleArgb = background)
            val read = receiptTickColor(read = true, bubbleArgb = background)
            assertNotEquals("ticks must stay grey vs green on $background", delivered, read)
            assertTrue(
                "delivered contrast ${contrastRatio(delivered, background)} on $background",
                contrastRatio(delivered, background) >= MIN_TEXT_CONTRAST,
            )
            assertTrue(
                "read contrast ${contrastRatio(read, background)} on $background",
                contrastRatio(read, background) >= MIN_TEXT_CONTRAST,
            )
        }
    }
}
