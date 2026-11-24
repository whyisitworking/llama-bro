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
        assertEquals(Color(0xFFF2F2F5), LightDemoColors.canvas)
        assertEquals(Color(0xFFFBFBFC), LightDemoColors.surface)
        assertEquals(Color(0xFF1D1D1F), LightDemoColors.ink)
        assertEquals(Color(0xFF2C2C2E), LightDemoColors.reply)
        assertEquals(Color(0xFF65656A), LightDemoColors.muted)
        assertEquals(Color(0xFF6E6E73), LightDemoColors.soft)
        assertEquals(Color(0xFFE3E3E8), LightDemoColors.hairline)
        assertEquals(Color(0xFFE6E6EB), LightDemoColors.bubble)
        assertEquals(Color(0xFF3A3A3D), LightDemoColors.accent)
        assertEquals(Color(0xFFFBFBFC), LightDemoColors.onAccent)
        assertEquals(Color(0xFFE8E8ED), LightDemoColors.accentSoft)
        assertEquals(Color(0xFF3E5641), LightDemoColors.greatInk)
        assertEquals(Color(0xFF73572F), LightDemoColors.slowInk)
        assertEquals(Color(0xFF5A5A5F), LightDemoColors.tooBigInk)
        assertEquals(Color(0xFFA23B2F), LightDemoColors.danger)
    }

    @Test
    fun darkMatchesTheSpecTable() {
        assertEquals(Color(0xFF0B0B0C), DarkDemoColors.canvas)
        assertEquals(Color(0xFF161618), DarkDemoColors.surface)
        assertEquals(Color(0xFFE3E3E6), DarkDemoColors.ink)
        assertEquals(Color(0xFFCFCFD4), DarkDemoColors.reply)
        assertEquals(Color(0xFF98989D), DarkDemoColors.muted)
        assertEquals(Color(0xFF808085), DarkDemoColors.soft)
        assertEquals(Color(0xFF242426), DarkDemoColors.hairline)
        assertEquals(Color(0xFF222224), DarkDemoColors.bubble)
        assertEquals(Color(0xFFC8C8CD), DarkDemoColors.accent)
        assertEquals(Color(0xFF161618), DarkDemoColors.onAccent)
        assertEquals(Color(0xFF28282B), DarkDemoColors.accentSoft)
        assertEquals(Color(0xFFA9C3AA), DarkDemoColors.greatInk)
        assertEquals(Color(0xFFD3B588), DarkDemoColors.slowInk)
        assertEquals(Color(0xFFAAAAB0), DarkDemoColors.tooBigInk)
        assertEquals(Color(0xFFE59A90), DarkDemoColors.danger)
    }

    @Test
    fun bodyTextMeetsWcagAaInBothThemes() {
        for ((name, c) in listOf("light" to LightDemoColors, "dark" to DarkDemoColors)) {
            assertTrue("$name ink on canvas", contrast(c.ink, c.canvas) >= 4.5)
            assertTrue("$name muted on surface", contrast(c.muted, c.surface) >= 4.5)
        }
    }

    @Test
    fun everyTextPairTheAppDrawsMeetsItsContrast() {
        // normal text 4.5:1; the fit words are 14 sp medium and the primary button's text 16 sp medium, so they are
        // held to 4.5:1 too; a surface is the canvas or the surface token
        for ((name, c) in listOf("light" to LightDemoColors, "dark" to DarkDemoColors)) {
            for (ground in listOf(c.canvas, c.surface)) {
                assertTrue("$name ink on $ground", contrast(c.ink, ground) >= 4.5)
                assertTrue("$name reply on $ground", contrast(c.reply, ground) >= 4.5)
                assertTrue("$name muted on $ground", contrast(c.muted, ground) >= 4.5)
                // D123: the soft grey of the least important text (a section label, a row's mark) is still body text
                assertTrue("$name soft on $ground", contrast(c.soft, ground) >= 4.5)
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
            assertTrue("$name reply on a bubble", contrast(c.reply, c.bubble) >= 4.5)
            assertTrue("$name muted (the time) on the user's bubble", contrast(c.muted, c.bubble) >= 4.5)
        }
    }

    @Test
    fun theAccentIsAQuietToneOffTheInk() {
        // D125: the one primary tone fills the send and voice circles too (D73's indigo is gone), so a filled circle is
        // a control's boundary, at least 3:1 on the bar's surface and on the canvas; and it is a step softer than the
        // ink, so the Get button is neither too bright in dark nor too stark in light
        for ((name, c) in listOf("light" to LightDemoColors, "dark" to DarkDemoColors)) {
            for (ground in listOf(c.canvas, c.surface)) {
                assertTrue("$name accent on $ground", contrast(c.accent, ground) >= 3.0)
                assertTrue(
                    "$name accent under the ink on $ground",
                    contrast(c.accent, ground) < contrast(c.ink, ground),
                )
            }
        }
    }

    @Test
    fun tooBigInkIsNoDimmerThanMuted() {
        // R9: the too-big figure was the faintest mark on a card; it is a fit, so it reads at least as well as muted
        // text
        for ((name, c) in listOf("light" to LightDemoColors, "dark" to DarkDemoColors)) {
            for (ground in listOf(c.canvas, c.surface)) {
                assertTrue("$name too big on $ground", contrast(c.tooBigInk, ground) >= 4.5)
                assertTrue(
                    "$name too big is dimmer than muted on $ground",
                    contrast(c.tooBigInk, ground) >= contrast(c.muted, ground),
                )
            }
        }
    }

    @Test
    fun aDownloadRingsTrackStandsOffTheSurface() {
        // the soft accent track was 1.2:1 or less on the dark sheet; a frozen ring vanished
        for ((name, c) in listOf("light" to LightDemoColors, "dark" to DarkDemoColors)) {
            assertTrue("$name ring track on the surface", contrast(c.ringTrack, c.surface) >= 1.5)
            assertTrue("$name ring track on the canvas", contrast(c.ringTrack, c.canvas) >= 1.5)
        }
    }

    @Test
    fun theReplyIsAStepSofterThanTheInk() {
        // a reply's body reads calmer than its headings and bold, which stay the ink
        for ((name, c) in listOf("light" to LightDemoColors, "dark" to DarkDemoColors)) {
            for (ground in listOf(c.canvas, c.surface)) {
                assertTrue("$name reply under the ink on $ground", contrast(c.reply, ground) < contrast(c.ink, ground))
                assertTrue("$name reply over muted on $ground", contrast(c.reply, ground) > contrast(c.muted, ground))
            }
        }
    }

    @Test
    fun softIsAStepFainterThanMuted() {
        // D123: three inks, ink over muted over soft, so the least important text reads last
        for ((name, c) in listOf("light" to LightDemoColors, "dark" to DarkDemoColors)) {
            for (ground in listOf(c.canvas, c.surface)) {
                assertTrue(
                    "$name soft is no fainter step on $ground",
                    contrast(c.soft, ground) < contrast(c.muted, ground),
                )
            }
        }
    }

    @Test
    fun onlyTheDarkSetIsDark() {
        assertEquals(false, LightDemoColors.isDark)
        assertEquals(true, DarkDemoColors.isDark)
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
