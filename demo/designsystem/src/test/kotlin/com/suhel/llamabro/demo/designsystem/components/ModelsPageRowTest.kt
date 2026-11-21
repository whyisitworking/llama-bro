package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/** The design system's part of the Models page fixes (D121, D123). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ModelsPageRowTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { DemoTheme(ThemeMode.Light, content) }

    private val DpRect.centerX get() = (left + right) / 2
    private val DpRect.centerY get() = (top + bottom) / 2

    private fun near(what: String, a: Dp, b: Dp) = assertTrue("$what: $a vs $b", abs(a.value - b.value) <= 1f)

    @Composable
    private fun SampleRow(info: Boolean) =
        ModelRow(
            name = "Qwen3.5 4B",
            fit = FitLevel.Well,
            fitDescription = "Runs well",
            maker = "Alibaba Qwen",
            size = "3.4 GB",
            state = "Current",
            capabilities = emptyList(),
            fact = null,
            onInfo = if (info) ({}) else null,
        )

    /**
     * A title that keeps a name with its size by a no-break space ("Remove Qwen3.5 0.8B?") is read, and
     * found, with a plain space: the no-break space is the line's, not the words'.
     */
    @Test fun aTitlesNoBreakSpaceReadsAsASpace() {
        show { DialogCard("Remove Qwen3.5\u00A00.8B?", "This frees 0.7 GB.", "Remove", {}, "Keep", {}) }
        assertEquals(1, rule.onAllNodesWithText("Remove Qwen3.5 0.8B?").fetchSemanticsNodes().size)
    }

    /**
     * With D123's fit centred on both lines: the info action sits just before the fit symbol,
     * centred on the lines as the symbol is, so a row with it has the page's right edges: its fit symbol where a row
     * without it has its own. Its target is still 48 dp, and doesn't cover the symbol's centre.
     */
    @Test fun theInfoActionSitsBeforeTheFitSymbol() {
        show {
            Column {
                SampleRow(info = true)
                SampleRow(info = false)
            }
        }
        val symbols = rule.onAllNodesWithContentDescription("Runs well")
        val withInfo = symbols[0].getBoundsInRoot()
        val without = symbols[1].getBoundsInRoot()
        val info = rule.onNodeWithContentDescription("About Qwen3.5 4B").getBoundsInRoot()
        near("the fit symbols' column", withInfo.centerX, without.centerX)
        near("the info centred as the symbol is", info.centerY, withInfo.centerY)
        assertTrue("the info before the symbol", info.right <= withInfo.centerX)
        near("the info's target", info.right - info.left, 48.dp)
    }
}
