package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LocalAnimatorDurationScale
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The design system's part of the Models page fixes (D120): a value fading out is the last one shown, the list's
 * exit → place → enter, parts that fade before they fold, the ring panel at large font, one notice, the search
 * field's clear, the expandable sheet's held first measure and its large-font foot.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ModelsPageMotionTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalAnimatorDurationScale provides 1f,
            ) {
                DemoTheme(ThemeMode.Light, content)
            }
        }
    }

    private fun frames(count: Int) =
        repeat(count) {
            rule.mainClock.advanceTimeBy(FRAME)
            rule.waitForIdle()
        }

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    /** Texts drawn under a line whose words are said as one phrase (cleared semantics): the unmerged tree. */
    private fun drawn(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.single()
    }

    /** Downloading 5%, then 60%, then paused: the words fading out are 60%'s, never the stage's first. */
    @Test
    fun aValueFadingOutIsTheLastOneShownNotTheFirstOfItsKind() {
        var view by mutableStateOf("Downloading" to "36 of 739 MB")
        show { FadeThrough(view, contentKey = { it.first }) { Text(it.second) } }
        view = "Downloading" to "443 of 739 MB"
        rule.waitForIdle()
        assertEquals(1, count("443 of 739 MB"))
        rule.mainClock.autoAdvance = false
        view = "Paused" to "443 of 739 MB · paused"
        // the frames of the fade-out: the value drawn is the one already shown, never the stage's first
        repeat(FADE_OUT_FRAMES) {
            frames(1)
            assertEquals("frame $it drew an older value", 0, count("36 of 739 MB"))
        }
        assertEquals(1, count("443 of 739 MB") + count("443 of 739 MB · paused"))
    }

    /** D120: the list's one policy, as specs: what stays moves only after exits, and arrivals after that. */
    @Test
    fun theListMovesOnlyAfterExitsAndArrivalsWaitForThePlacing() {
        val exit = exitFadeSpec<Float>() as TweenSpec<Float>
        val place = listPlaceSpec<Float>() as TweenSpec<Float>
        val enter = listEnterSpec<Float>() as TweenSpec<Float>
        assertTrue("place waits for the exit", place.delay >= exit.delay + exit.durationMillis)
        assertTrue("enter waits for the place", enter.delay >= place.delay + place.durationMillis)
        val size = rowSizeSpec<Float>() as TweenSpec<Float>
        assertEquals("a row's own size change moves with its neighbours", place.delay, size.delay)
        assertEquals(place.durationMillis, size.durationMillis)
    }

    /** A leaving row's neighbours hold their place while it fades, so no moving row crosses a leaving one. */
    @Test
    fun rowsUnderALeavingRowHoldStillUntilItHasFaded() {
        var items by mutableStateOf(listOf("a", "b", "c"))
        show {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(items, key = { it }) { item ->
                    Text(item, Modifier.height(64.dp).testTag(item).listItemMotion(this))
                }
            }
        }
        val before = rule.onNodeWithTag("b").getBoundsInRoot().top
        rule.mainClock.autoAdvance = false
        items = listOf("b", "c")
        // inside the exit fade: the row under the leaving one hasn't started to move
        repeat((DemoMotion.TEXT_OUT_MILLIS / FRAME.toInt()) - 1) {
            frames(1)
            assertEquals("frame $it", before, rule.onNodeWithTag("b").getBoundsInRoot().top)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("b").getBoundsInRoot().top < before)
    }

    /** A part that goes fades out first and keeps its height while it does: no glyph is drawn cut. */
    @Test
    fun aPartThatGoesFadesBeforeItFolds() {
        var value by mutableStateOf<String?>("Pick a model to talk to.")
        show { Column { Unfold(value, Modifier.testTag("part")) { Text(it) } } }
        val full = rule.onNodeWithTag("part").getBoundsInRoot().height
        rule.mainClock.autoAdvance = false
        value = null
        // every frame of the fade keeps the part's whole height: it starts to fold only once its words are gone
        repeat(DemoMotion.TEXT_OUT_MILLIS / FRAME.toInt()) {
            frames(1)
            assertEquals("frame $it", full, rule.onNodeWithTag("part").getBoundsInRoot().height)
        }
    }

    /** Waiting to downloading: the ring's arc moves with the new words, not while "Waiting" fades out. */
    @Test
    fun theRingsAmountChangesWithTheWordsNotBefore() {
        var view by mutableStateOf(DownloadView(DownloadStage.Waiting, "Q4_K_M", 0f, "after Gemma 3 4B"))
        show { DownloadPanel(view, {}, {}, Modifier.width(360.dp)) }
        rule.mainClock.autoAdvance = false
        view = DownloadView(DownloadStage.Downloading, "Q4_K_M", 0.05f, "0.2 of 3.4 GB · 8 min left")
        frames(2)
        assertEquals(1, count("Waiting · Q4_K_M"))
        assertEquals(0f, ringAmount(), 0f)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(0.05f, ringAmount(), 0.001f)
    }

    private fun ringAmount(): Float =
        rule
            .onNodeWithContentDescription("Pause", useUnmergedTree = true)
            .fetchSemanticsNode()
            .let { node ->
                node.children
                    .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) }
                    ?.current
                    ?: node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current
                    ?: 0f
            }

    /** At twice the font the panel's lines wrap rather than cut, on a row and in the card's foot. */
    @Test
    fun atTwiceTheFontTheRingPanelNeverCutsItsLines() {
        val view = DownloadView(DownloadStage.Stopped, "Q4_K_M", 0.4f, "The connection dropped · 0.3 of 0.7 GB kept")
        show(fontScale = 2f) {
            Column(Modifier.width(360.dp)) {
                DownloadPanel(view, {}, {}, compact = true)
            }
        }
        for (text in listOf("Stopped · Q4_K_M", view.detail)) {
            val layout = rule.onNodeWithText(text).layout()
            assertFalse("\"$text\" is cut at 2x", layout.hasVisualOverflow)
            assertFalse("\"$text\" ellipsised", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        }
    }

    /** One notice: its action a bordered button of the standard height, the line on the gutter. */
    @Test
    fun aNoticesActionIsABorderedButtonAndItsLineCanSitOnTheGutter() {
        show {
            Column(Modifier.width(360.dp).testTag("box")) {
                Notice("Other sizes need the network.", action = "Try again", inset = false)
            }
        }
        val action = rule.onNodeWithText("Try again")
        assertTrue(
            "never underlined",
            action
                .layout()
                .layoutInput.style.textDecoration != TextDecoration.Underline,
        )
        val box = rule.onNodeWithTag("box").getBoundsInRoot()
        val line = rule.onNodeWithText("Other sizes need the network.").getBoundsInRoot()
        assertEquals("on the gutter", box.left, line.left)
        assertTrue(rule.onNodeWithText("Try again").getBoundsInRoot().height <= DemoSize.button)
    }

    /** With text, a clear ✕; tapped, the field is empty. */
    @Test
    fun theSearchFieldClearsWithItsCross() {
        var text by mutableStateOf("gem")
        show { SearchField(text, { text = it }, "Search models") }
        rule.onNodeWithContentDescription("Clear").performClick()
        assertEquals("", text)
        rule.onNodeWithContentDescription("Clear").assertDoesNotExistCompat()
    }

    private fun SemanticsNodeInteraction.assertDoesNotExistCompat() =
        assertTrue(runCatching { fetchSemanticsNode() }.isFailure)

    /** The body holds at 0 while the window can't hold the compact height, so Material's show waits for it. */
    @Test
    fun anExpandableSheetHoldsItsFirstMeasureUntilTheWindowHasItsHeight() {
        val compact = 1_400
        val screen = 2_400
        assertTrue("a window not yet sized holds the show", sheetHeld(600, compact, screen))
        assertFalse("a window sized holds nothing", sheetHeld(2_300, compact, screen))
        assertFalse("room for the compact height holds nothing", sheetHeld(1_400, compact, screen))
        assertFalse("a short screen shows at its height", sheetHeld(900, compact, 1_000))
    }

    /** A part that changes kind keeps the tallest kind's height; the others are measured, not shown. */
    @Test
    fun aReservedPartIsTheTallestKindsHeightAndShowsOnlyItself() {
        var state by mutableStateOf(0)
        val get = @Composable { PrimaryButton("Get Q4_K_M · 3.4 GB\nabout 8 min", {}, Modifier.fillMaxWidth()) }
        val panel = @Composable {
            DownloadPanel(DownloadView(DownloadStage.Downloading, "Q4_K_M", 0.3f, "0.9 of 3.4 GB · 4 min left"), {}, {})
        }
        val chat = @Composable { PrimaryButton("Start a chat", {}, Modifier.fillMaxWidth()) }
        show(fontScale = 2f) {
            ReserveTallest(listOf(get, panel, chat), Modifier.width(360.dp).testTag("foot")) {
                when (state) {
                    0 -> get()
                    1 -> panel()
                    else -> chat()
                }
            }
        }
        val heights =
            (0..2).map {
                state = it
                rule.waitForIdle()
                rule.onNodeWithTag("foot").getBoundsInRoot().height
            }
        assertTrue("$heights", heights.all { abs((it - heights[0]).value) < 0.5f })
        assertEquals("only the shown kind is heard", 1, count("Start a chat"))
    }

    /** A part's content fades in from halfway through its unfolding, not over an empty band. */
    @Test
    fun aPartsContentFadesInFromHalfwayThroughItsUnfolding() {
        val place = listPlaceSpec<Float>() as TweenSpec<Float>
        val fade = enterFadeSpec<Float>() as TweenSpec<Float>
        assertEquals(place.delay + place.durationMillis / 2, fade.delay)
    }

    /** Line 3's fact fades through: the old and the new fact are never drawn together. */
    @Test
    fun aRowsFactFadesThrough() {
        var fact by mutableStateOf("1.2M downloads")
        show { CapabilityIcons(emptyList(), fact) }
        rule.mainClock.autoAdvance = false
        fact = "2.4M downloads"
        frames(2)
        assertEquals("the old fact fades out, not snapped away", 1, drawn("1.2M downloads"))
        repeat(16) {
            frames(1)
            assertTrue("frame $it", drawn("1.2M downloads") + drawn("2.4M downloads") <= 1)
        }
    }

    /** A quant with no note is centred in the note's reserved height, so the row isn't top-heavy. */
    @Test
    fun aQuantWithNoNoteIsCentredInItsRow() {
        show {
            Column(Modifier.width(360.dp)) {
                QuantRow("Q3_K_M", "2.6 GB", false, Modifier.testTag("row"), notes = true) {}
            }
        }
        val row = rule.onNodeWithTag("row").getBoundsInRoot()
        val quant = rule.onNodeWithText("Q3_K_M").getBoundsInRoot()
        val middle = (row.top + row.bottom) / 2
        assertTrue("$quant in $row", abs(((quant.top + quant.bottom) / 2 - middle).value) <= 1f)
    }

    /** A bordered quiet button is the shared button height. */
    @Test
    fun aBorderedButtonIsTheButtonHeight() {
        show { QuietButton("Done", {}, Modifier.testTag("done"), bordered = true) }
        assertEquals(DemoSize.button, rule.onNodeWithTag("done").getBoundsInRoot().height)
    }

    private companion object {
        const val FRAME = 16L
        const val FADE_OUT_FRAMES = 6
    }
}
