package com.suhel.llamabro.demo.designsystem.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a bar's blur eases out toward the sharp page, as its softness says (D163). */
class BarFadeTest {
    private fun softness(of: Float) = GlassTheme.Default.bar.copy(softness = of)

    @Test fun noSoftnessFollowsTheFadeAsBefore() {
        for (strength in listOf(0f, 0.25f, 0.5f, 1f)) assertEquals(strength, softness(0f).blurAt(strength), 0.0001f)
    }

    @Test fun theEndsStayFullAndNone() {
        for (s in listOf(0f, 0.5f, 1f)) {
            assertEquals(1f, softness(s).blurAt(1f), 0.0001f)
            assertEquals(0f, softness(s).blurAt(0f), 0.0001f)
        }
    }

    @Test fun softerLingersLongerAsALightBlur() {
        // halfway through the fade, a softer blur is already smaller, and it stays small (but not none) longer
        assertTrue(softness(0.5f).blurAt(0.5f) < softness(0f).blurAt(0.5f))
        assertTrue(softness(1f).blurAt(0.5f) < softness(0.5f).blurAt(0.5f))
        assertEquals(0.125f, softness(1f).blurAt(0.5f), 0.0001f)
    }

    @Test fun softnessOutsideItsRangeIsHeldToIt() {
        assertEquals(softness(1f).blurAt(0.5f), softness(3f).blurAt(0.5f), 0.0001f)
        assertEquals(softness(0f).blurAt(0.5f), softness(-1f).blurAt(0.5f), 0.0001f)
    }
}
