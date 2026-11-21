package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared components at large font and in tight space: the name wins, a label
 * that can't fit ellipsises, a notice is one plain line with its action.
 */
@RunWith(AndroidJUnit4::class)
class LargeTextAndNoticeTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                DemoTheme(ThemeMode.Light, content)
            }
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)
        action?.action?.invoke(results)
        return results.single()
    }

    private fun TextLayoutResult.ellipsised(): Boolean = (0 until lineCount).any { isLineEllipsized(it) }

    @Test
    fun largeTextStartsAtOnePointThree() {
        var at129 = true
        var at130 = false
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.29f)) { at129 = isLargeText() }
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) { at130 = isLargeText() }
        }
        rule.waitForIdle()
        assertFalse(at129)
        assertTrue(at130)
    }

    @Test
    fun aNoticeShowsItsActionAndCallsIt() {
        var tapped = 0
        show { Notice("Couldn't reach Hugging Face.", action = "Try again", onAction = { tapped++ }) }
        rule.onNodeWithText("Couldn't reach Hugging Face.").assertExists()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, tapped)
    }

    @Test
    fun aNoticeWithoutAnActionIsJustTheLine() {
        show { Notice("No models match.") }
        rule.onNodeWithText("No models match.").assertExists()
    }

    @Test
    fun atTwiceTheFontARowKeepsItsWholeTitleAndPutsTheRestUnderIt() {
        show(fontScale = 2f) {
            ListRow(
                "Qwen3.5 0.8B",
                trailing = "Used today",
                end = { EdgeChip("Default") },
            )
        }
        val title = rule.onNodeWithText("Qwen3.5 0.8B")
        assertFalse("the title is cut", title.layout().ellipsised())
        val titleBounds = title.getBoundsInRoot()
        val trailing = rule.onNodeWithText("Used today").getBoundsInRoot()
        val chip = rule.onNodeWithText("Default").getBoundsInRoot()
        assertTrue("the trailing text sits under the title", trailing.top >= titleBounds.bottom)
        assertTrue("the chip sits under the title", chip.top >= titleBounds.bottom)
    }

    @Test
    fun atNormalSizeATitleThatFitsBesideTheTrailingTextSharesItsLine() {
        show {
            ListRow("Qwen3.5 0.8B", trailing = "Used today")
        }
        val title = rule.onNodeWithText("Qwen3.5 0.8B").getBoundsInRoot()
        val trailing = rule.onNodeWithText("Used today").getBoundsInRoot()
        assertTrue("one line", trailing.top < title.bottom)
        assertTrue("trailing at the end", trailing.left > title.right)
    }

    @Test
    fun atNormalSizeALongTitleWrapsWithTheTrailingTextBesideIt() {
        val name = "A rather long model name that needs the row"
        show {
            ListRow(name, Modifier.width(320.dp), trailing = "Used yesterday")
        }
        val title = rule.onNodeWithText(name)
        val layout = title.layout()
        assertTrue("the title wraps", layout.lineCount >= 2)
        assertFalse("the title is cut", layout.ellipsised())
        val titleBounds = title.getBoundsInRoot()
        val trailing = rule.onNodeWithText("Used yesterday").getBoundsInRoot()
        assertTrue("the trailing text stays beside", trailing.left >= titleBounds.right)
        assertTrue("on the title's lines", trailing.top < titleBounds.bottom)
    }

    @Test
    fun atTwiceTheFontATrailingTextMovesUnderTheTitle() {
        show(fontScale = 2f) { ListRow("Context length", trailing = "8,192") }
        val title = rule.onNodeWithText("Context length").getBoundsInRoot()
        assertTrue(rule.onNodeWithText("8,192").getBoundsInRoot().top >= title.bottom)
    }

    @Test
    fun atTwiceTheFontASwitchStaysAtTheEnd() {
        show(fontScale = 2f) {
            ListRow(
                "Keep the model loaded",
                control = { Switch(checked = true, onCheckedChange = {}, Modifier.testTag("switch")) },
            )
        }
        val title = rule.onNodeWithText("Keep the model loaded")
        assertFalse("the title is cut", title.layout().ellipsised())
        val titleBounds = title.getBoundsInRoot()
        val control = rule.onNodeWithTag("switch").getBoundsInRoot()
        assertTrue("the switch is at the end", control.left >= titleBounds.right)
        assertTrue("on the title's line", control.top < titleBounds.bottom)
    }

    @Test
    fun aQuietButtonWithALongLabelEllipsises() {
        show {
            QuietButton(
                "Reset every setting of this model to its defaults",
                onClick = {},
                Modifier.width(120.dp),
            )
        }
        val label = rule.onNodeWithText("Reset every setting of this model to its defaults")
        assertTrue(label.layout().ellipsised())
    }

    @Test
    fun anEdgeChipWithALongLabelEllipsises() {
        show { EdgeChip("Recommended for this phone", Modifier.width(60.dp)) }
        assertTrue(rule.onNodeWithText("Recommended for this phone").layout().ellipsised())
    }

    @Test
    fun theButtonsShareOneHeight() {
        show {
            androidx.compose.foundation.layout.Column {
                PrimaryButton("Start a chat", onClick = {}, Modifier.testTag("primary"))
                QuietButton("Cancel", onClick = {}, Modifier.testTag("bordered"), bordered = true)
                QuietButton("Unload", onClick = {}, Modifier.testTag("quiet"))
            }
        }
        for (tag in listOf("primary", "bordered", "quiet")) {
            assertEquals(tag, DemoSize.button, rule.onNodeWithTag(tag).getBoundsInRoot().height)
        }
    }

    @Test
    fun atLargeTextASectionLabelPutsItsActionUnderTheLabel() {
        show(fontScale = 2f) { SectionLabel("Suggested for this phone", action = "What's a model?") }
        val label = rule.onNodeWithText("SUGGESTED FOR THIS PHONE")
        assertFalse("the label is cut", label.layout().ellipsised())
        val action = rule.onNodeWithText("What's a model?").getBoundsInRoot()
        assertTrue(action.top >= label.getBoundsInRoot().bottom)
    }

    @Test
    fun theFitSymbolGrowsWithTheFont() {
        show(fontScale = 1.25f) { FitSymbol(FitLevel.Great, "Runs great", Modifier.testTag("fit")) }
        assertEquals(DemoSize.fitSymbol * 1.25f, rule.onNodeWithTag("fit").getBoundsInRoot().height)
    }

    @Test
    fun atTwiceTheFontTheFitSymbolIsCapped() {
        show(fontScale = 2f) { FitSymbol(FitLevel.Great, "Runs great", Modifier.testTag("fit")) }
        assertEquals(DemoSize.fitSymbolMax, rule.onNodeWithTag("fit").getBoundsInRoot().height)
    }

    @Test
    fun aSearchFieldKeepsItsSizeWhileItLoads() {
        var loading by mutableStateOf(false)
        show { SearchField("qwen", {}, "Search any model", Modifier.testTag("field"), loading = loading) }
        val idle = rule.onNodeWithTag("field").getBoundsInRoot()
        // the sweep never ends: step the clock by hand rather than wait for an idle that never comes
        rule.mainClock.autoAdvance = false
        loading = true
        rule.mainClock.advanceTimeBy(FRAMES_OF_SWEEP)
        assertEquals(idle, rule.onNodeWithTag("field").getBoundsInRoot())
    }

    private companion object {
        const val FRAMES_OF_SWEEP = 500L
    }

    @Test
    fun aStatusPillIsOneLine() {
        show { StatusPill("On this phone", Modifier.width(48.dp)) }
        val pill = rule.onNodeWithText("On this phone").layout()
        assertEquals(1, pill.lineCount)
        assertTrue(pill.ellipsised())
    }

    @Test
    fun atTwiceTheFontASliderTitleWrapsRatherThanCut() {
        // the chat's sampling sheet at 2x: "Presence penalty" beside its value on a sheet card's 294 dp
        show(fontScale = 2f) {
            SliderRow(
                "Presence penalty",
                1.5f,
                { "%.1f".format(it) },
                0f..2f,
                steps = 0,
                onValueChange = {},
                modifier = Modifier.width(294.dp),
                note = "Model default",
            )
        }
        assertFalse(rule.onNodeWithText("Presence penalty").layout().ellipsised())
    }

    @Test
    fun atTwiceTheFontALimitLinesActionMovesUnderTheLine() {
        // beside the line at 2x the action left the words a column a few letters wide, breaking them mid-word
        val line = "Qwen3.5 4B is using more memory than was free. Android may close apps in the background."
        show(fontScale = 2f) {
            LimitLine(line, "Smaller size", onAction = {}, modifier = Modifier.width(328.dp), onDismiss = {})
        }
        val text = rule.onNodeWithText(line).getBoundsInRoot()
        assertTrue(rule.onNodeWithText("Smaller size").getBoundsInRoot().top >= text.bottom)
        assertTrue(rule.onNodeWithContentDescription("Dismiss").getBoundsInRoot().top >= text.bottom)
    }
}
