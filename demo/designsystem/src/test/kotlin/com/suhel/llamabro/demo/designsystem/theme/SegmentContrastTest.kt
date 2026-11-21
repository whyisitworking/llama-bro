package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.graphics.Color
import com.suhel.llamabro.demo.designsystem.components.segmentColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** S23: the segmented switch's chosen segment is told from its track at 3:1, a control's boundary (WCAG 1.4.11). */
class SegmentContrastTest {
    @Test
    fun theChosenSegmentStandsOffItsTrackInBothThemesOnAScreenAndOnASheet() {
        for ((name, c) in listOf("light" to Light, "dark" to Dark)) {
            for (onSheet in listOf(false, true)) {
                val s = segmentColors(c, onSheet)
                assertTrue("$name sheet=$onSheet edge on track", contrast(s.edge, s.track) >= 3.0)
                assertTrue("$name sheet=$onSheet edge on the segment", contrast(s.edge, s.raised) >= 3.0)
            }
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double =
            v.toDouble().let {
                if (it <=
                    0.03928
                ) {
                    it / 12.92
                } else {
                    ((it + 0.055) / 1.055).pow(2.4)
                }
            }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
}
