package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one model row and the ring download panel (models-page design §3, §4.5): one height in every download
 * stage and every state, the controls named, the name whole at large font.
 */
@RunWith(AndroidJUnit4::class)
class ModelRowAndDownloadPanelTest {
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
        fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        return results.single()
    }

    private fun view(stage: DownloadStage, detail: String = "0.9 of 3.4 GB · 4 min left") =
        DownloadView(stage, quant = "Q4_K_M", fraction = 0.27f, detail = detail)

    @Test
    fun thePanelIsOneHeightInEveryStage() {
        show {
            Column(Modifier.width(360.dp)) {
                for (stage in DownloadStage.entries) {
                    DownloadPanel(view(stage), onToggle = {}, onCancel = {}, Modifier.testTag("full_$stage"))
                    DownloadPanel(
                        view(stage),
                        onToggle = {},
                        onCancel = {},
                        Modifier.testTag("compact_$stage"),
                        compact = true,
                    )
                }
            }
        }
        for (stage in DownloadStage.entries) {
            // a button's height, so the card's foot holds it in 64 dp as it holds Get
            assertEquals("$stage", DemoSize.button, rule.onNodeWithTag("full_$stage").getBoundsInRoot().height)
            assertEquals("$stage", DemoSize.target, rule.onNodeWithTag("compact_$stage").getBoundsInRoot().height)
        }
    }

    @Test
    fun theControlsAreNamedAndCallBack() {
        var toggled = 0
        var cancelled = 0
        show {
            Column(Modifier.width(360.dp)) {
                DownloadPanel(view(DownloadStage.Downloading), onToggle = { toggled++ }, onCancel = { cancelled++ })
                DownloadPanel(view(DownloadStage.Paused, "0.9 of 3.4 GB"), onToggle = { toggled++ }, onCancel = {})
                DownloadPanel(view(DownloadStage.Stopped, "The connection dropped"), onToggle = { toggled++ }, {})
            }
        }
        rule.onNodeWithContentDescription("Pause").performClick()
        rule.onNodeWithContentDescription("Resume").performClick()
        rule.onNodeWithContentDescription("Try again").performClick()
        assertEquals(3, toggled)
        rule.onAllNodesWithContentDescription("Cancel")[0].performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun theControlsAreTouchTargets() {
        show {
            Column(Modifier.width(360.dp)) {
                DownloadPanel(view(DownloadStage.Downloading), onToggle = {}, onCancel = {}, compact = true)
            }
        }
        for (label in listOf("Pause", "Cancel")) {
            val bounds = rule.onNodeWithContentDescription(label).getBoundsInRoot()
            assertEquals(label, DemoSize.target, bounds.height)
        }
    }

    @Test
    fun theRingAnnouncesItsProgress() {
        show { DownloadPanel(view(DownloadStage.Downloading), onToggle = {}, onCancel = {}) }
        val progress =
            rule
                .onNodeWithContentDescription("Pause")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertNotNull(progress)
        assertEquals(0.27f, progress!!.current, 0.001f)
    }

    @Test
    fun theLinesNameTheStageAndTheQuant() {
        show {
            Column(Modifier.width(360.dp)) {
                DownloadPanel(view(DownloadStage.Downloading), onToggle = {}, onCancel = {})
                DownloadPanel(view(DownloadStage.Waiting, "after Gemma 3 4B"), onToggle = {}, onCancel = {})
                DownloadPanel(view(DownloadStage.Stopped, "The connection dropped"), onToggle = {}, onCancel = {})
            }
        }
        rule.onNodeWithText("Downloading · Q4_K_M").assertExists()
        rule.onNodeWithText("0.9 of 3.4 GB · 4 min left").assertExists()
        rule.onNodeWithText("Waiting · Q4_K_M").assertExists()
        rule.onNodeWithText("after Gemma 3 4B").assertExists()
        rule.onNodeWithText("Stopped · Q4_K_M").assertExists()
    }

    @Composable
    private fun GemmaRow(
        tag: String,
        state: String? = null,
        fit: FitLevel? = FitLevel.Well,
        name: String = "Gemma 3 4B",
        maker: String = "Google",
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
        onInfo: (() -> Unit)? = null,
        // a row with no icons and no fact: its mark alone makes line 2's quiet end at large font
        bare: Boolean = false,
        download: (@Composable () -> Unit)? = null,
    ) {
        ModelRow(
            name = name,
            fit = fit,
            fitDescription = "Runs well",
            maker = maker,
            size = "3.4 GB",
            state = state,
            capabilities = if (bare) emptyList() else listOf(DemoIcons.CapabilityImages to "Sees images"),
            fact = if (bare) null else "1.2M downloads",
            modifier = Modifier.width(360.dp).testTag(tag),
            onClick = onClick,
            onLongClick = onLongClick,
            onInfo = onInfo,
            download = download,
        )
    }

    @Test
    fun aLongPressAndTheInfoActionEachCallTheirOwn() {
        var clicked = 0
        var held = 0
        var info = 0
        show { GemmaRow("row", onClick = { clicked++ }, onLongClick = { held++ }, onInfo = { info++ }) }
        rule.onNodeWithTag("row").performTouchInput { longClick() }
        assertEquals(1, held)
        rule.onNodeWithContentDescription("About Gemma 3 4B").performClick()
        assertEquals(1, info)
        rule.onNodeWithTag("row").performClick()
        assertEquals("the info action is its own tap, not the row's", 1, clicked)
    }

    @Test
    fun theInfoActionIsATouchTargetAndKeepsTheRowsHeight() {
        show {
            Column {
                GemmaRow("plain")
                GemmaRow("info", state = "Current", onInfo = {})
            }
        }
        assertEquals(DemoSize.target, rule.onNodeWithContentDescription("About Gemma 3 4B").getBoundsInRoot().height)
        assertEquals(
            rule.onNodeWithTag("plain").getBoundsInRoot().height,
            rule.onNodeWithTag("info").getBoundsInRoot().height,
        )
    }

    @Test
    fun atTwiceTheFontALongMakerDoesNotCutTheSize() {
        // the size is the line's fact that matters: at large font the line wraps rather than ellipsise it
        show(fontScale = 2f) { GemmaRow("row", maker = "Alibaba Qwen", state = "Current", onInfo = {}) }
        val layout = rule.onNodeWithText("Alibaba Qwen · 3.4 GB").layout()
        assertFalse("the size is cut", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
    }

    @Test
    fun atTwiceTheFontTheNameShowsInFullBesideTheInfoAction() {
        show(fontScale = 2f) { GemmaRow("row", name = "Qwen3.5 0.8B", state = "Current", onInfo = {}) }
        val layout = rule.onNodeWithText("Qwen3.5 0.8B").layout()
        assertFalse("the name is cut", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
    }

    @Test
    fun atNormalSizeARowIsOneHeightWithOrWithoutAState() {
        show {
            Column {
                GemmaRow("none")
                GemmaRow("state", state = "On phone")
            }
        }
        assertEquals(
            rule.onNodeWithTag("none").getBoundsInRoot().height,
            rule.onNodeWithTag("state").getBoundsInRoot().height,
        )
    }

    @Test
    fun aRowIsOneHeightBeforeAndAfterItsFitIsKnown() {
        show {
            Column {
                GemmaRow("unknown", fit = null)
                GemmaRow("known", fit = FitLevel.Slow)
            }
        }
        assertEquals(
            rule.onNodeWithTag("unknown").getBoundsInRoot().height,
            rule.onNodeWithTag("known").getBoundsInRoot().height,
        )
    }

    @Test
    fun aRowIsOneHeightInEveryDownloadStage() {
        show {
            // four rows outgrow the test window: let the column run past it and measure each row unclipped
            Column(Modifier.wrapContentHeight(unbounded = true)) {
                for (stage in DownloadStage.entries) {
                    GemmaRow("row_$stage", state = "$stage") {
                        DownloadPanel(view(stage), onToggle = {}, onCancel = {}, compact = true)
                    }
                }
            }
        }
        val heights =
            DownloadStage.entries.map {
                rule
                    .onNodeWithTag("row_$it")
                    .fetchSemanticsNode()
                    .size.height
            }
        assertEquals(heights.toString(), 1, heights.distinct().size)
    }

    @Test
    fun atTwiceTheFontTheNameShowsInFull() {
        show(fontScale = 2f) { GemmaRow("row", state = "On phone") }
        val name = rule.onNodeWithText("Gemma 3 4B")
        val layout = name.layout()
        assertFalse("the name is cut", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
    }

    @Test
    fun atTwiceTheFontTheStateMovesUnderItsLine() {
        show(fontScale = 2f) { GemmaRow("row", state = "On phone") }
        val line = rule.onNodeWithText("Google · 3.4 GB").getBoundsInRoot()
        assertTrue(rule.onNodeWithText("On phone").getBoundsInRoot().top >= line.bottom)
    }

    @Test
    fun atTwiceTheFontAStatelessRowIsShorterThanOneWithAState() {
        // with no icons and no fact, the mark is what makes line 2's quiet end at large font
        show(fontScale = 2f) {
            Column(Modifier.wrapContentHeight(unbounded = true)) {
                GemmaRow("none", bare = true)
                GemmaRow("state", state = "On phone", bare = true)
            }
        }
        val none =
            rule
                .onNodeWithTag("none")
                .fetchSemanticsNode()
                .size.height
        val state =
            rule
                .onNodeWithTag("state")
                .fetchSemanticsNode()
                .size.height
        assertTrue("$none should be shorter than $state", none < state)
    }

    @Test
    fun aStateArrivingFadesThroughAndTheRowEasesToItsNewHeight() {
        var state by mutableStateOf<String?>(null)
        show(fontScale = 2f) { GemmaRow("row", state = state, bare = true) }
        val before =
            rule
                .onNodeWithTag("row")
                .fetchSemanticsNode()
                .size.height
        rule.mainClock.autoAdvance = false
        state = "On phone"
        // the old (empty) state fades out first, then the line eases to its new height as the pill fades in
        toMidTransition(AFTER_FADE_OUT_FRAMES)
        val during =
            rule
                .onNodeWithTag("row")
                .fetchSemanticsNode()
                .size.height
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val after =
            rule
                .onNodeWithTag("row")
                .fetchSemanticsNode()
                .size.height
        assertTrue("$before < $during < $after", during in (before + 1) until after)
    }

    @Test
    fun aStageChangeFadesThroughNeverShowingTwoStagesAndKeepsItsHeight() {
        var stage by mutableStateOf(DownloadStage.Downloading)
        show {
            Column(Modifier.width(360.dp)) {
                DownloadPanel(view(stage), onToggle = {}, onCancel = {}, Modifier.testTag("full"))
                DownloadPanel(view(stage), onToggle = {}, onCancel = {}, Modifier.testTag("compact"), compact = true)
            }
        }
        rule.mainClock.autoAdvance = false
        stage = DownloadStage.Stopped
        // frame by frame, the two stages' lines are never on screen together
        repeat(FADE_THROUGH_FRAMES) {
            toMidTransition(1)
            val old = rule.onAllNodesWithText("Downloading · Q4_K_M").fetchSemanticsNodes().size
            val new = rule.onAllNodesWithText("Stopped · Q4_K_M").fetchSemanticsNodes().size
            assertTrue("frame $it shows both stages", old == 0 || new == 0)
            assertEquals(DemoSize.button, rule.onNodeWithTag("full").getBoundsInRoot().height)
            assertEquals(DemoSize.target, rule.onNodeWithTag("compact").getBoundsInRoot().height)
        }
        rule.onAllNodesWithText("Stopped · Q4_K_M")[0].assertExists()
        // each panel's control already answers to the new stage, with one name, not the two fading glyphs'
        assertEquals(2, rule.onAllNodesWithContentDescription("Try again").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().size)
    }

    /**
     * Frame by frame, letting the UI settle after each (with the clock held, idle means recomposed, not animated), so
     * the change recomposes and its animation starts before the clock moves on.
     */
    private fun toMidTransition(frames: Int = MID_TRANSITION_FRAMES) {
        repeat(frames) {
            rule.mainClock.advanceTimeBy(FRAME_MILLIS)
            rule.waitForIdle()
        }
    }

    private companion object {
        // about 130 ms: inside both the 250 ms size change and the 300 ms crossfade
        const val MID_TRANSITION_FRAMES = 8

        // past the 90 ms fade out, the place step's 90 ms wait (D120) and into the 250 ms size change after it
        const val AFTER_FADE_OUT_FRAMES = 20

        // the whole fade through, 90 + 120 ms, and a little more
        const val FADE_THROUGH_FRAMES = 16
        const val FRAME_MILLIS = 16L
    }
}
