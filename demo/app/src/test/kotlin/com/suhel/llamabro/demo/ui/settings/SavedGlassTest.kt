package com.suhel.llamabro.demo.ui.settings

import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.GlassTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** The glass tuned in Settings › Appearance › Glass, as it is saved and read back (D152). */
class SavedGlassTest {
    @Test fun aTunedGlassReadsBackAsItWasSaved() {
        val tuned =
            GlassTheme.Default.let {
                it.copy(
                    panel = it.panel.copy(bezel = 15.dp, refraction = 22.dp, dispersion = 1.2f, blur = 7.dp),
                    bar = it.bar.copy(blur = 30.dp, hold = 0.4f),
                )
            }
        assertEquals(tuned, SavedGlass.decode(SavedGlass.encode(tuned)))
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
