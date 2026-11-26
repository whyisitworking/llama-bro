package com.suhel.llamabro.demo.ui.settings

import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.GlassPress
import com.suhel.llamabro.demo.designsystem.theme.GlassTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** The glass tuned in Settings › Appearance › Glass, as it is saved and read back (D152, D158). */
class SavedGlassTest {
    @Test fun aTunedGlassReadsBackAsItWasSaved() {
        val tuned =
            GlassTheme.Default.let {
                it.copy(
                    panel = it.panel.copy(bezel = 15.dp, refraction = 22.dp, dispersion = 1.2f, blur = 7.dp),
                    bar = it.bar.copy(blur = 30.dp, hold = 0.4f, softness = 0.8f),
                    sheet = it.sheet.copy(veil = 0.7f, blur = 24.dp),
                )
            }
        assertEquals(tuned, SavedGlass.decode(SavedGlass.encode(tuned)))
    }

    @Test fun theGlassOnTheBarsReadsBackAsChosenOrLeftToThePhone() {
        for (choice in listOf(null, false, true)) {
            val glass = GlassTheme.Default.copy(mergedLens = choice)
            assertEquals(choice, SavedGlass.decode(SavedGlass.encode(glass)).mergedLens)
        }
    }

    @Test fun aTunedPressReadsBackAndAnOlderSaveHasTheDefaultPress() {
        val tuned =
            GlassTheme.Default.copy(
                press =
                    GlassPress(
                        swell = 1.08f,
                        light = 0.4f,
                        reach = 50.dp,
                        bend = 0.6f,
                        pull = 14.dp,
                        stretch = 0.12f,
                    ),
            )
        assertEquals(tuned, SavedGlass.decode(SavedGlass.encode(tuned)))
        // saved before D158, with no press in it
        assertEquals(GlassPress.Default, SavedGlass.decode("""{"chip":{"bezel":9.0}}""").press)
    }

    @Test fun nothingSavedIsTheDefault() {
        assertEquals(GlassTheme.Default, SavedGlass.decode(null))
    }

    @Test fun aValueMissingFromTheSavedTextIsTheDefaults() {
        val read = SavedGlass.decode("""{"panel":{"refraction":20.0}}""")
        assertEquals(20.dp, read.panel.refraction)
        assertEquals(GlassTheme.Default.panel.bezel, read.panel.bezel)
        assertEquals(GlassTheme.Default.chip, read.chip)
        assertEquals(GlassTheme.Default.bar, read.bar)
    }

    @Test fun textThatCantBeReadIsTheDefault() {
        assertEquals(GlassTheme.Default, SavedGlass.decode("not json"))
        assertEquals(GlassTheme.Default, SavedGlass.decode("""{"chip":{"bezel":"wide"}}"""))
    }
}
