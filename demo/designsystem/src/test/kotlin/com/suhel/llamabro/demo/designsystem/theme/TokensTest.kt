package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** pd-design 5.2's table, value by value, and WCAG AA for body text (Reference C). */
class TokensTest {
    @Test
    fun lightMatchesTheSpecTable() {
        assertEquals(Color(0xFFF7F6F3), Light.canvas)
        assertEquals(Color(0xFFFFFFFF), Light.surface)
        assertEquals(Color(0xFF17181C), Light.ink)
        assertEquals(Color(0xFF55565C), Light.muted)
        assertEquals(Color(0xFFE6E4DF), Light.hairline)
        assertEquals(Color(0xFFE9E7E1), Light.bubble)
        assertEquals(Color(0xFF25304D), Light.accent)
        assertEquals(Color(0xFFE4E7EF), Light.accentSoft)
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
        assertEquals(Color(0xFF121316), Dark.canvas)
        assertEquals(Color(0xFF1B1C20), Dark.surface)
        assertEquals(Color(0xFFECEBE8), Dark.ink)
        assertEquals(Color(0xFFA5A6AC), Dark.muted)
        assertEquals(Color(0xFF26272C), Dark.hairline)
        assertEquals(Color(0xFF2A2C31), Dark.bubble)
        assertEquals(Color(0xFFB9C4DE), Dark.accent)
        assertEquals(Color(0xFF252A38), Dark.accentSoft)
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
            assertTrue("$name text on a primary button", contrast(c.canvas, c.accent) >= 4.5)
            assertTrue("$name label of a disabled button", contrast(c.muted, c.hairline) >= 4.5)
            assertTrue("$name accent on its soft fill", contrast(c.accent, c.accentSoft) >= 4.5)
            assertTrue("$name ink on the user's bubble", contrast(c.ink, c.bubble) >= 4.5)
            assertTrue("$name muted (the time) on the user's bubble", contrast(c.muted, c.bubble) >= 4.5)
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
