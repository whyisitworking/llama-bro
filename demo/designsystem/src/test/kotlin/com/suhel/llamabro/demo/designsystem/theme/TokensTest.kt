package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** demo design §8.6's table, value by value, and WCAG AA for body text. */
class TokensTest {
    @Test
    fun lightMatchesTheSpecTable() {
        assertEquals(Color(0xFFFAFAFA), Light.canvas)
        assertEquals(Color(0xFFFFFFFF), Light.surface)
        assertEquals(Color(0xFF0D0D0D), Light.ink)
        assertEquals(Color(0xFF5D5D5D), Light.muted)
        assertEquals(Color(0xFFE5E5E5), Light.hairline)
        assertEquals(Color(0xFFEFEFEF), Light.bubble)
        assertEquals(Color(0xFF0D0D0D), Light.accent)
        assertEquals(Color(0xFFFFFFFF), Light.onAccent)
        assertEquals(Color(0xFFEBEBEB), Light.accentSoft)
        assertEquals(Color(0xFF4F5BD5), Light.sendAccent)
        assertEquals(Color(0xFFFFFFFF), Light.onSendAccent)
        assertEquals(Color(0xFFEDF0EC), Light.greatTint)
        assertEquals(Color(0xFF3E5641), Light.greatInk)
        assertEquals(Color(0xFFF3EEE5), Light.slowTint)
        assertEquals(Color(0xFF73572F), Light.slowInk)
        assertEquals(Color(0xFFEFEFEF), Light.tooBigTint)
        assertEquals(Color(0xFF6E6E73), Light.tooBigInk)
        assertEquals(Color(0xFFA23B2F), Light.danger)
    }

    @Test
    fun darkMatchesTheSpecTable() {
        assertEquals(Color(0xFF0A0A0A), Dark.canvas)
        assertEquals(Color(0xFF141414), Dark.surface)
        assertEquals(Color(0xFFECECEC), Dark.ink)
        assertEquals(Color(0xFFA8A8A8), Dark.muted)
        assertEquals(Color(0xFF222222), Dark.hairline)
        assertEquals(Color(0xFF1E1E1E), Dark.bubble)
        assertEquals(Color(0xFFECECEC), Dark.accent)
        assertEquals(Color(0xFF0A0A0A), Dark.onAccent)
        assertEquals(Color(0xFF262626), Dark.accentSoft)
        assertEquals(Color(0xFF8E9BFF), Dark.sendAccent)
        assertEquals(Color(0xFF0A0A0A), Dark.onSendAccent)
        assertEquals(Color(0xFF1A1F1B), Dark.greatTint)
        assertEquals(Color(0xFFA9C3AA), Dark.greatInk)
        assertEquals(Color(0xFF221E18), Dark.slowTint)
        assertEquals(Color(0xFFD3B588), Dark.slowInk)
        assertEquals(Color(0xFF1E1E21), Dark.tooBigTint)
        assertEquals(Color(0xFF8C8C92), Dark.tooBigInk)
        assertEquals(Color(0xFFE59A90), Dark.danger)
    }

    @Test
    fun bodyTextMeetsWcagAaInBothThemes() {
        for ((name, c) in listOf("light" to Light, "dark" to Dark)) {
            assertTrue("$name ink on canvas", contrast(c.ink, c.canvas) >= 4.5)
            assertTrue("$name muted on surface", contrast(c.muted, c.surface) >= 4.5)
        }
    }

    @Test
    fun everyTextPairTheAppDrawsMeetsItsContrast() {
        // normal text 4.5:1; the fit words are 14 sp medium and the primary button's text 16 sp medium, so they are held
        // to 4.5:1 too; a surface is the canvas or the surface token
        for ((name, c) in listOf("light" to Light, "dark" to Dark)) {
            for (ground in listOf(c.canvas, c.surface)) {
                assertTrue("$name ink on $ground", contrast(c.ink, ground) >= 4.5)
                assertTrue("$name muted on $ground", contrast(c.muted, ground) >= 4.5)
                assertTrue("$name accent (link, selected) on $ground", contrast(c.accent, ground) >= 4.5)
                assertTrue("$name 'runs great' on $ground", contrast(c.greatInk, ground) >= 4.5)
                assertTrue("$name 'slow here' on $ground", contrast(c.slowInk, ground) >= 4.5)
                assertTrue("$name 'too big' on $ground", contrast(c.tooBigInk, ground) >= 4.5)
                assertTrue("$name danger on $ground", contrast(c.danger, ground) >= 4.5)
            }
            assertTrue("$name text on a primary button", contrast(c.onAccent, c.accent) >= 4.5)
            assertTrue("$name label of a disabled button", contrast(c.muted, c.hairline) >= 4.5)
            assertTrue("$name accent on its soft fill", contrast(c.accent, c.accentSoft) >= 4.5)
            assertTrue("$name ink on the user's bubble", contrast(c.ink, c.bubble) >= 4.5)
            assertTrue("$name muted (the time) on the user's bubble", contrast(c.muted, c.bubble) >= 4.5)
        }
    }

    @Test
    fun theSendAccentHoldsItsGlyphAndStandsOnTheBar() {
        // D73: the send and Think-on glyph on the indigo is 5.5:1 in light and 7.8:1 in dark, above text's 4.5:1 though
        // only an icon's 3:1 is required; the circle itself, a control's boundary, is at least 3:1 on the bar's surface
        // and on the canvas
        val glyph = mapOf("light" to 5.54, "dark" to 7.81)
        for ((name, c) in listOf("light" to Light, "dark" to Dark)) {
            val onIndigo = contrast(c.onSendAccent, c.sendAccent)
            assertEquals("$name glyph on the send accent", glyph.getValue(name), onIndigo, 0.01)
            assertTrue("$name glyph on the send accent", onIndigo >= 4.5)
            for (ground in listOf(c.canvas, c.surface)) {
                assertTrue("$name send accent on $ground", contrast(c.sendAccent, ground) >= 3.0)
            }
        }
    }

    @Test
    fun onlyTheDarkSetIsDark() {
        assertEquals(false, Light.isDark)
        assertEquals(true, Dark.isDark)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double =
            v.toDouble().let {
                if (it <= 0.03928) {
                    it / 12.92
                } else {
                    ((it + 0.055) / 1.055).pow(2.4)
                }
            }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }
}
