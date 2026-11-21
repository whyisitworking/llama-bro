package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalAnimatorDurationScale
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The design system's part of the Models page fixes (D119): text that fades through, the dialog at large font, the
 * expandable sheet's one owner of its height, one inset, the quant table, the ring panel's optical edges and the row's
 * footer leaving.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ModelsPageComponentsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, still: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalAnimatorDurationScale provides if (still) 0f else 1f,
            ) {
                DemoTheme(ThemeMode.Light, content)
            }
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.single()
    }

    private fun count(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun frame() {
        rule.mainClock.advanceTimeBy(FRAME_MILLIS)
        rule.waitForIdle()
    }

    // text changes fade through, never two at once

    @Test
    fun fadeThroughNeverDrawsTwoValuesAtOnce() {
        var word by mutableStateOf("Downloading")
        show { FadeThrough(word) { Text(it) } }
        rule.mainClock.autoAdvance = false
        word = "Paused"
        var sawNew = false
        repeat(FADE_FRAMES) {
            frame()
            assertTrue("frame $it shows both words", count("Downloading") + count("Paused") == 1)
            sawNew = sawNew || count("Paused") == 1
        }
        assertTrue(sawNew)
    }

    @Test
    fun fadeThroughHoldsTheOldWordUntilItHasFadedOut() {
        var word by mutableStateOf("Waiting")
        show { FadeThrough(word) { Text(it) } }
        rule.mainClock.autoAdvance = false
        word = "Downloading"
        repeat(3) { frame() }
        // inside the 90 ms fade out the old word is still the one drawn
        assertEquals(1, count("Waiting"))
        repeat(FADE_FRAMES) { frame() }
        assertEquals(1, count("Downloading"))
    }

    @Test
    fun fadeThroughIsInstantWithAnimationsOff() {
        var word by mutableStateOf("Waiting")
        show(still = true) { FadeThrough(word) { Text(it) } }
        rule.mainClock.autoAdvance = false
        word = "Downloading"
        // the write reaches the composition on the next frame, which draws the new word with no fade
        frame()
        frame()
        assertEquals(1, count("Downloading"))
    }

    // the dialog at large font

    @Test
    fun atTwiceTheFontTheDialogsButtonsStackAndNoLabelIsCut() {
        show(fontScale = 2f) {
            Box(Modifier.width(320.dp)) {
                DialogCard(
                    "Cancel the download?",
                    "0.9 GB has arrived. Cancelling deletes it.",
                    "Cancel download",
                    {},
                    "Keep",
                    {},
                    danger = true,
                )
            }
        }
        val confirm = rule.onNodeWithText("Cancel download")
        val layout = confirm.layout()
        assertFalse("the action label is cut", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        val keep = rule.onNodeWithText("Keep").getBoundsInRoot()
        val cancel = confirm.getBoundsInRoot()
        assertTrue("stacked: Keep under the confirm", keep.top >= cancel.bottom)
    }

    @Test
    fun keepIsABorderedButtonTheConfirmsHeightNotUnderlinedText() {
        show {
            Box(Modifier.width(360.dp)) {
                DialogCard("Remove Qwen3.5 4B?", "This frees 3.4 GB.", "Remove", {}, "Keep", {}, danger = true)
            }
        }
        val keep =
            rule
                .onNodeWithText("Keep")
                .layout()
                .layoutInput.style
        assertNull("Keep is underlined text", keep.textDecoration)
        assertEquals(
            DemoSize.button,
            rule.onNode(hasClickAction() and hasText("Keep")).getBoundsInRoot().height,
        )
        assertEquals(
            DemoSize.button,
            rule.onNode(hasClickAction() and hasText("Remove")).getBoundsInRoot().height,
        )
    }

    @Test
    fun theDialogsTitleIsAStepOverItsLine() {
        show(fontScale = 2f) {
            Box(Modifier.width(320.dp)) {
                DialogCard("What's a model?", "A model is the part that writes the replies.", "Got it", {}, null, {})
            }
        }
        val title =
            rule
                .onNodeWithText("What's a model?")
                .layout()
                .layoutInput.style.fontSize
        val line =
            rule
                .onNodeWithText("A model is the part that writes the replies.")
                .layout()
                .layoutInput.style
        assertTrue("$title over ${line.fontSize}", title.value > line.fontSize.value)
        assertEquals(DemoType.bodyMedium.fontSize, line.fontSize)
    }

    @Test
    fun aLongDialogAtTwiceTheFontKeepsItsButtonOnScreen() {
        show(fontScale = 2f) {
            DemoDialog("What's a model?", "A model is the part that writes the replies.", "Got it", {}, {}) {
                Column { repeat(LONG_LINES) { Text("Runs well: it answers quickly here, line $it") } }
            }
        }
        // the line and the key scroll between the title and the button, so the button stays on the 891 dp screen
        rule.onNodeWithText("Got it").assertIsDisplayed()
        assertTrue(rule.onNodeWithText("Got it").getBoundsInRoot().bottom <= SCREEN_HEIGHT)
        rule.onNodeWithTag(DsTags.DIALOG_SCROLL).assertExists()
    }

    // the expandable sheet's height has one owner

    @Test
    fun anExpandableSheetOpensAtTheCompactHeightAndNeverRisesAboveWhereItSettles() {
        rule.mainClock.autoAdvance = false
        show {
            DemoSheet(onDismissRequest = {}, expandable = true, pinned = { Text("Get") }) { Text("Qwen3.5 4B") }
        }
        val tops = mutableListOf<Float>()
        repeat(OPEN_FRAMES) {
            frame()
            rule
                .onAllNodesWithContentDescription(DRAG_HANDLE)
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.let { tops += it.boundsInRoot.top }
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val settled = handleTop()
        assertTrue("never above its settled top: $tops vs $settled", tops.all { it >= settled - 1f })
        // the scroll and the foot fill the compact height from the first frame, so a short card holds the room its
        // page lands in
        val body = rule.onNodeWithTag(DsTags.SHEET_SCROLL).getBoundsInRoot().height
        val foot = rule.onNodeWithTag(DsTags.SHEET_FOOT).getBoundsInRoot().height
        assertTrue("$body + $foot", abs((body + foot - DemoSize.sheetCompactHeight).value) <= 1f)
    }

    @Test
    fun pulledUpTheSheetGrowsOverSeveralFramesNotInOne() {
        show {
            DemoSheet(onDismissRequest = {}, expandable = true, pinned = { Text("Get") }) {
                repeat(PAGE_LINES) { Text("A line of the model's page, number $it") }
            }
        }
        rule.waitForIdle()
        val compact = handleTop()
        rule.mainClock.autoAdvance = false
        // scrolled within (as "Another size" brings its sizes into view): a swipe would run the clock on with it
        rule.onNodeWithTag(DsTags.SHEET_SCROLL).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, SCROLL_PX) }
        val tops = mutableListOf<Float>()
        repeat(GROW_FRAMES) {
            frame()
            tops += handleTop()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val full = handleTop()
        val travel = compact - full
        assertTrue("it grew: $compact to $full", travel > 0f)
        val steps = (listOf(compact) + tops).zipWithNext { a, b -> a - b }
        assertTrue("one frame moved it ${steps.max()} of $travel", steps.max() < travel * MAX_STEP_SHARE)
        assertTrue("never under the window's top", full >= 0f)
    }

    @Test
    fun scrolledWhileItOpensTheSheetGrowsOnlyToTheWindowsTopEdge() {
        // the full height came from the sheet's live position; a scroll during the opening slide overshot it
        rule.mainClock.autoAdvance = false
        show {
            DemoSheet(onDismissRequest = {}, expandable = true, pinned = { Text("Get", Modifier.testTag("get")) }) {
                repeat(PAGE_LINES) { Text("A line of the model's page, number $it") }
            }
        }
        repeat(MID_OPEN_FRAMES) { frame() }
        rule.onNodeWithTag(DsTags.SHEET_SCROLL).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, SCROLL_PX) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("the sheet's top is on screen", handleTop() >= 0f)
        val foot = rule.onNodeWithTag(DsTags.SHEET_FOOT).getBoundsInRoot()
        assertTrue("the foot is on screen: ${foot.bottom}", foot.bottom <= SCREEN_HEIGHT + 0.5.dp)
        rule.onNodeWithTag("get").assertIsDisplayed()
    }

    @Test
    fun theFullHeightIsTheWindowsLessTheBarsAndTheHandleWhereverTheSheetIs() {
        // it came from the sheet's live offset, which a scroll mid-slide or mid-drag overshot past the status
        // bar; now it has no input but the window and the insets
        assertEquals(
            2400 - 96 - 126 - 48,
            fullBodyPx(windowPx = 2400, statusBarPx = 96, navigationBarPx = 48, handlePx = 126),
        )
    }

    @Test
    fun theScrimFollowsTheSheet() {
        assertEquals(1f, scrimShade(offset = 1000f, sheetHeight = 800f, windowHeight = 1800f))
        assertEquals(0.5f, scrimShade(offset = 1400f, sheetHeight = 800f, windowHeight = 1800f))
        assertEquals(0f, scrimShade(offset = 1800f, sheetHeight = 800f, windowHeight = 1800f))
    }

    private fun handleTop() =
        rule
            .onNodeWithContentDescription(DRAG_HANDLE)
            .getBoundsInRoot()
            .top.value

    // one inset

    @Test
    fun aRuledLabelStartsOnTheGutterLikeTheLinesAroundIt() {
        show {
            Column(Modifier.width(360.dp).testTag("column")) {
                Text("On this phone · Q4_K_M · 3.4 GB", Modifier.testTag("line"))
                RuledLabel("From the model's page on Hugging Face")
            }
        }
        val line = rule.onNodeWithTag("line").getBoundsInRoot().left
        assertEquals(line, rule.onNodeWithText("FROM THE MODEL'S PAGE ON HUGGING FACE").getBoundsInRoot().left)
    }

    // the quant table

    @Test
    fun aQuantSitsOnItsSizesBaselineAndTheReaderRowIsFlat() {
        show {
            Column(Modifier.width(360.dp)) {
                QuantRow("Q3_K_M", "2.6 GB", selected = false, note = "Recommended", symbol = { Text("•") })
                QuantRow(
                    "Image reader",
                    "0.7 GB",
                    selected = false,
                    modifier = Modifier.testTag("reader"),
                    included = true,
                    flat = true,
                    symbol = { Text("Included") },
                )
            }
        }
        val quant = rule.onNodeWithText("Q3_K_M")
        val size = rule.onNodeWithText("2.6 GB")
        val quantBaseline = quant.getBoundsInRoot().top.value + quant.layout().firstBaseline / density()
        val sizeBaseline = size.getBoundsInRoot().top.value + size.layout().firstBaseline / density()
        assertEquals(quantBaseline, sizeBaseline, 1f)
        assertEquals(
            DemoType.label.fontSize,
            quant
                .layout()
                .layoutInput.style.fontSize,
        )
        // the reader row is a fact, not a choice: no card edge around it
        val cards = rule.onAllNodesWithTag(DsTags.CARD_SURFACE).fetchSemanticsNodes().size
        assertEquals("only the quant's row is a card", 1, cards)
    }

    private fun density(): Float =
        rule
            .onRoot()
            .fetchSemanticsNode()
            .layoutInfo.density.density

    // the ring panel's marks meet the gutters

    @Test
    fun inTheSheetsFootTheTargetsAreWholeAndTheMarksMeetTheGutters() {
        // F130: offset past the panel, the ✕'s target was cut to ~36 dp for accessibility; the marks on the
        // gutters
        show {
            DemoSheet(
                onDismissRequest = {},
                expandable = true,
                pinned = { DownloadPanel(panelView, {}, {}, Modifier.testTag("panel")) },
            ) {
                Text("Qwen3.5 4B", Modifier.fillMaxWidth().testTag("line"))
            }
        }
        assertWholeTargetsAndMarksOn(rule.onNodeWithTag("line").getBoundsInRoot().let { it.left to it.right })
    }

    @Test
    fun onARowTheFootersTargetsAreWholeAndTheMarksMeetTheTextsEdges() {
        show {
            ModelRow(
                name = "Gemma 3 4B",
                fit = FitLevel.Well,
                fitDescription = "Runs well",
                maker = "Google",
                size = "3.4 GB",
                state = null,
                capabilities = emptyList(),
                fact = "1.2M downloads",
                modifier = Modifier.width(360.dp).testTag("row"),
                download = { DownloadPanel(panelView, {}, {}, Modifier.testTag("panel"), compact = true) },
            )
        }
        val card = rule.onNodeWithTag("row").getBoundsInRoot()
        // a model row's inset is a little more than a card's (D123)
        val inset = DemoSpacing.item + DemoSpacing.inline / 2
        assertWholeTargetsAndMarksOn(card.left + inset to card.right - inset)
        // the row's text stays on its inset
        assertEquals(
            (card.left + inset).value,
            rule
                .onNodeWithText("Gemma 3 4B")
                .getBoundsInRoot()
                .left.value,
            0.5f,
        )
    }

    private val panelView = DownloadView(DownloadStage.Downloading, "Q4_K_M", 0.3f, "1.0 of 3.4 GB")

    private fun assertWholeTargetsAndMarksOn(edges: Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>) {
        val (left, right) = edges
        val panel = rule.onNodeWithTag("panel").getBoundsInRoot()
        for (name in listOf("Pause", "Cancel")) {
            // the clickable node, not its glyph: 48 dp, and inside the panel's bounds, which accessibility cuts a
            // target to on the phone (F130; host semantics don't cut, so the containment is what is checked)
            val target = rule.onNodeWithContentDescription(name).getBoundsInRoot()
            assertTrue("$name is ${target.width} wide", target.width >= DemoSize.target - 0.5.dp)
            assertTrue("$name is ${target.height} tall", target.height >= DemoSize.target - 0.5.dp)
            assertTrue("$name reaches past the panel: $target in $panel", target.left >= panel.left - 0.5.dp)
            assertTrue("$name reaches past the panel: $target in $panel", target.right <= panel.right + 0.5.dp)
        }
        val ring =
            rule
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
                .getBoundsInRoot()
        assertEquals("the ring's edge on the gutter", left.value, ring.left.value, 0.5f)
        val glyph = rule.onNodeWithContentDescription("Cancel", useUnmergedTree = true).getBoundsInRoot()
        assertEquals("the ✕'s glyph on the gutter", right.value, glyph.right.value, 0.5f)
    }

    // the row's footer leaves fading and folding together

    @Test
    fun aLandedDownloadsFooterFadesAndFoldsAwayOverSeveralFrames() {
        val view = DownloadView(DownloadStage.Downloading, "Q4_K_M", 0.99f, "3.3 of 3.4 GB")
        var downloading by mutableStateOf(true)
        show {
            ModelRow(
                name = "Gemma 3 4B",
                fit = FitLevel.Well,
                fitDescription = "Runs well",
                maker = "Google",
                size = "3.4 GB",
                state = null,
                capabilities = emptyList(),
                fact = "Used today",
                modifier = Modifier.width(360.dp).testTag("row"),
                download = if (downloading) ({ DownloadPanel(view, {}, {}, compact = true) }) else null,
            )
        }
        val before =
            rule
                .onNodeWithTag("row")
                .getBoundsInRoot()
                .height.value
        rule.mainClock.autoAdvance = false
        downloading = false
        repeat(3) { frame() }
        // the panel is still there as the change lands, leaving, not gone in one frame with an empty band left
        assertEquals(1, count("Downloading · Q4_K_M"))
        val heights = mutableListOf<Float>()
        repeat(GROW_FRAMES) {
            frame()
            heights +=
                rule
                    .onNodeWithTag("row")
                    .getBoundsInRoot()
                    .height.value
        }
        val after = heights.last()
        assertTrue("it folded: $before to $after", after < before)
        val drop = (listOf(before) + heights).zipWithNext { a, b -> a - b }.max()
        assertTrue("one frame dropped $drop of ${before - after}", drop < (before - after) * MAX_STEP_SHARE)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
        const val FADE_FRAMES = 16
        const val OPEN_FRAMES = 40
        const val GROW_FRAMES = 30
        const val PAGE_LINES = 60
        const val LONG_LINES = 30
        const val MAX_STEP_SHARE = 0.6f
        const val SCROLL_PX = 300f
        const val MID_OPEN_FRAMES = 5
        val SCREEN_HEIGHT = 891.dp
        const val DRAG_HANDLE = "Drag handle"
    }
}
