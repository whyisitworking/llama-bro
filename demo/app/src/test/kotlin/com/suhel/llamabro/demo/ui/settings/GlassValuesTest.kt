package com.suhel.llamabro.demo.ui.settings

import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.GlassTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Settings › Appearance › Glass copies (D151, D152, D155): every value of the glass, as the Kotlin that sets it.
 */
class GlassValuesTest {
    @Test fun theCopiedValuesAreTheGlassesAsKotlin() {
        val glass = GlassTheme.Default.let { it.copy(chip = it.chip.copy(refraction = 20.dp, dispersion = 1.25f)) }
        val text = describe(glass)
        for (part in listOf("chip = GlassStyle(", "refraction = 20.dp", "dispersion = 1.25f", "panel = GlassStyle(")) {
            assertTrue("$part in $text", part in text)
        }
        for (part in listOf("press = GlassPress(", "swell = ${GlassTheme.Default.press.swell}f")) {
            assertTrue("$part in $text", part in text)
        }
        for (part in listOf("bar = BarGlass(", "fade = ${GlassTheme.Default.bar.fade.value.toInt()}.dp")) {
            assertTrue("$part in $text", part in text)
        }
    }
}
