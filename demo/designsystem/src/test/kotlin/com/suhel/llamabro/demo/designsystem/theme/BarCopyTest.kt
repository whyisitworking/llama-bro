package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** How much smaller a bar's copy of the page is than the screen, as its blur says (D162). */
class BarCopyTest {
    private fun at(blur: Dp, tier: GlassTier = GlassTier.Full): Float {
        val bar = GlassTheme.Default.bar
        return bar.copy(blur = blur).reduction(tier)
    }

    @Test fun aLightBlurReadsThePageItself() {
        assertEquals(1f, at(0.dp))
        assertEquals(1f, at(6.dp))
        assertEquals(1f, at(12.dp))
    }

    @Test fun theCopyShrinksAsTheBlurGrowsUpToHalf() {
        assertEquals(1.5f, at(18.dp), 0.001f)
        assertEquals(2f, at(GlassTheme.Default.bar.blur))
        assertEquals(2f, at(48.dp))
    }

    @Test fun aLightPhoneCopiesTwiceAsSmallUpToAQuarter() {
        assertEquals(1f, at(6.dp, GlassTier.Light))
        assertEquals(2f, at(12.dp, GlassTier.Light))
        assertEquals(4f, at(24.dp, GlassTier.Light))
        assertEquals(4f, at(48.dp, GlassTier.Light))
    }
}
