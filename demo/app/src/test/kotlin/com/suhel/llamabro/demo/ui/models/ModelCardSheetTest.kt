package com.suhel.llamabro.demo.ui.models

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.DemoSheetState
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.designsystem.components.SheetFrame
import com.suhel.llamabro.demo.designsystem.components.rememberDemoSheetState
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.ui.chat.Markdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The card's layout (docs/design/models-page.md §4): its top holds while the page lands; its foot is 64 dp always. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelCardSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val base = CardPreviews.notHere

    @Test
    fun theSheetsTopDoesntMoveWhenThePageArrives() {
        var view by mutableStateOf(base.copy(page = PageView.Unknown))
        rule.setContent { DemoTheme(ThemeMode.Light) { ModelCardSheet(view, CardActions(), onDismiss = {}) } }
        rule.waitForIdle()
        val top = handleTop()
        val page = (1..40).joinToString("\n\n") { "A paragraph of the model's page, number $it." }
        view = view.copy(page = PageView.Markdown(Markdown.parsePage("# About\n\n$page")))
        rule.waitForIdle()
        rule.onNodeWithTag("page_overline").assertExists()
        assertEquals("the page landing moved the sheet", top, handleTop())
    }

    @Test
    fun theFootIsSixtyFourDpInEveryState() {
        val feet =
            listOf(
                CardFoot.Get("Get Q4_K_M · 3.4 GB", enabled = true, time = "about 8 min"),
                CardFoot.Get("Get", enabled = false),
                CardFoot.OnPhone(another = true),
                CardFoot.OnPhone(another = false),
                CardFoot.CantChat,
            ) +
                DownloadStage.entries.map { stage ->
                    CardFoot.Download(DownloadView(stage, "UD-Q4_K_XL", 0.27f, "0.9 of 3.4 GB · 4 min left"))
                }
        var view by mutableStateOf(base)
        rule.setContent { DemoTheme(ThemeMode.Light) { ModelCardSheet(view, CardActions(), onDismiss = {}) } }
        for (foot in feet) {
            view = base.copy(foot = foot)
            rule.waitForIdle()
            assertEquals("$foot", DemoSize.sheetFoot, rule.onNodeWithTag(SHEET_FOOT).getBoundsInRoot().height)
        }
    }

    private fun showLarge(view: () -> CardView) {
        rule.setContent {
            val density = LocalDensity.current
            // the sheet in place (a modal window takes its density from the window, not from here)
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                DemoTheme(ThemeMode.Light) {
                    val shown = view()
                    // the foot alone under a line: the body at 2x would take the whole window
                    SheetFrame(pinned = { CardFootView(shown.foot, CardActions()) }) { Text(shown.header.name) }
                }
            }
        }
        rule.waitForIdle()
    }

    /** At 2x the foot is one height in every state, and Get's time is a line of its own, never cut. */
    @Test
    fun atTwiceTheFontTheFootIsOneHeightAndGetIsNeverCut() {
        val feet =
            listOf(
                CardFoot.Get("Get Q4_K_M · 3.4 GB", enabled = true, time = "about 8 min"),
                CardFoot.Download(
                    DownloadView(DownloadStage.Downloading, "Q4_K_M", 0.3f, "0.9 of 3.4 GB · 4 min left"),
                ),
                CardFoot.Download(DownloadView(DownloadStage.Stopped, "Q4_K_M", 0.3f, "The connection dropped")),
                CardFoot.OnPhone(another = true),
            )
        var view by mutableStateOf(base)
        showLarge { view }
        val heights =
            feet.map { foot ->
                view = base.copy(foot = foot)
                rule.waitForIdle()
                rule.onNodeWithTag(SHEET_FOOT).getBoundsInRoot().height
            }
        assertTrue("$heights", heights.all { it == heights.first() })
        view = base
        rule.waitForIdle()
        // the shown Get and its measured twin (never placed, kept out of the merged tree) are one text
        val get =
            rule
                .onAllNodes(hasText("Get Q4_K_M · 3.4 GB", substring = true), useUnmergedTree = true)
                .onFirst()
                .fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult]
                .action!!
                .let { read -> mutableListOf<TextLayoutResult>().also { read(it) }.single() }
        assertTrue("the time is on its own line", get.lineCount >= 2)
        assertTrue("never cut", (0 until get.lineCount).none { get.isLineEllipsized(it) })
    }

    /** A model that can't chat says so once, on its state line. */
    @Test
    fun aModelThatCantChatSaysSoOnce() {
        showCard(state("Can't chat"))
        rule.onNodeWithText("Not for chat: this is an embedding model").assertExists()
        rule.onAllNodesWithText("Embeddings, not chat").assertCountEquals(0)
        rule.onAllNodesWithText("can't chat", substring = true).assertCountEquals(0)
    }

    /**
     * D123: under the name, one quiet line: the maker, the capabilities as icons with no words on screen (a screen
     * reader hears them), the fit symbol; the repo is the page's source, not a header line.
     */
    @Test
    fun theHeadersOneQuietLineSaysTheMakerTheIconsAndTheFit() {
        showCard(base)
        rule.onAllNodesWithText("Thinks", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("by ", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText(base.header.repo).assertCountEquals(0)
        val spoken =
            rule
                .onNodeWithTag("card_facts")
                .fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
                .single()
        val fit =
            base.header.fit!!
                .label
                .text()
        assertTrue(spoken, spoken.startsWith(base.header.maker!!) && "Thinks" in spoken && spoken.endsWith(fit))
    }

    @Test
    fun offlineWithNothingCachedTheCardHasOneNoticeAndOneTryAgain() {
        // the sizes and the page both failed: one notice in the choice's place, one Try again
        val offline = CardPreviews.states.single { it.first == "Couldn't list" }.second
        assertEquals(PageView.Failed(retryable = true), offline.page)
        var retried = 0
        rule.setContent {
            DemoTheme(ThemeMode.Light) { ModelCardSheet(offline, CardActions(onRetry = { retried++ }), onDismiss = {}) }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("Try again").assertCountEquals(1)
        rule.onAllNodesWithText("Couldn't read this model's page.").assertCountEquals(0)
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    // the card

    private fun state(name: String) = CardPreviews.states.single { it.first == name }.second

    private fun showCard(view: CardView, actions: CardActions = CardActions()) {
        rule.setContent { DemoTheme(ThemeMode.Light) { ModelCardSheet(view, actions, onDismiss = {}) } }
        rule.waitForIdle()
    }

    private fun style(text: String) =
        rule
            .onNode(hasText(text), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .let { get -> mutableListOf<TextLayoutResult>().also { get(it) }.single() }
            .layoutInput.style

    private fun fontSize(text: String) = style(text).fontSize

    private fun fontWeight(text: String) = style(text).fontWeight

    @Test
    fun theCardsLinesAreQuietUnderItsName() {
        // D123: the name in the serif, then everything in the quiet body size, Regular
        showCard(base.copy(page = PageView.None))
        assertEquals(DemoType.cardTitle.fontSize, fontSize(base.header.name))
        assertEquals(DemoType.bodyMedium.fontSize, fontSize("Not on this phone"))
        assertEquals(DemoType.bodyMedium.fontSize, fontSize("Recommended"))
        assertEquals(FontWeight.Normal, fontWeight("Not on this phone"))
        assertEquals(FontWeight.Normal, fontWeight("Recommended"))
    }

    @Test
    fun noStateLineWhileADownloadRuns() {
        // the ring panel alone describes a download (D119): a state line beside it would say the same twice
        showCard(state("Downloading"))
        rule.onNodeWithTag("sheet_state").assertDoesNotExist()
        rule.onNodeWithText("Downloading · Q4_K_M", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theHeaderIsTheNameAndOneLine() {
        // the header was five stacked lines; D123 makes it two
        showCard(base.copy(page = PageView.None))
        val header = rule.onNodeWithTag("card_header").getBoundsInRoot()
        val name = rule.onNodeWithText(base.header.name).getBoundsInRoot()
        val line = rule.onNodeWithTag("card_facts").getBoundsInRoot()
        assertEquals(header.bottom, line.bottom)
        assertTrue("one line under the name", (line.bottom - line.top).value < DemoSize.fitSymbol.value * 2)
        assertTrue(line.top >= name.bottom)
    }

    @Test
    fun theOverlineStartsWhereTheStateLineStarts() {
        // one inset for every line in the card
        showCard(base)
        val label =
            rule
                .onNodeWithTag("page_overline")
                .performScrollTo()
                .getBoundsInRoot()
                .left
        assertEquals(rule.onNodeWithTag("sheet_state").getBoundsInRoot().left, label)
    }

    @Test
    fun aFootThatChangesKindFadesThroughNeverShowingBoth() {
        // Get to the ring panel, the ring panel to "Start a chat", each in one frame
        var view by mutableStateOf(base.copy(page = PageView.None))
        rule.setContent { DemoTheme(ThemeMode.Light) { ModelCardSheet(view, CardActions(), onDismiss = {}) } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        view = state("Downloading").copy(page = PageView.None)
        repeat(FRAMES) {
            rule.mainClock.advanceTimeBy(FRAME_MILLIS)
            rule.waitForIdle()
            val get = rule.onAllNodes(hasText("Get Q4_K_M", substring = true), true).fetchSemanticsNodes().size
            val ring = rule.onAllNodes(hasText("Downloading · Q4_K_M"), true).fetchSemanticsNodes().size
            assertTrue("frame $it shows both feet", get == 0 || ring == 0)
            // not a hard swap either: the old foot is still the one drawn while it fades out
            if (it == HOLD_FRAME) assertEquals("Get swapped in one frame", 1 to 0, get to ring)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onAllNodes(hasText("Downloading · Q4_K_M"), true).assertCountEquals(1)
    }

    @Test
    fun aQuantOnThePhoneCantBePickedInAdvanced() {
        // card "Open": Basic disabled the tier on the phone; Advanced must too
        val another = state("Another size")
        showCard(another.copy(choice = another.choice!!.copy(mode = SheetMode.Advanced), page = PageView.None))
        val here = rule.onNode(hasTestTag("quant_row") and hasAnyDescendant(hasText("Q4_K_M")))
        here.assertExists()
        assertFalse(here.fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        rule.onNodeWithText("Image reader", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("mmproj", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun anotherSizeSaysWhatIsHereAndDoneGoesBack() {
        // after "Get another size" there was no way back and nothing said why Smaller was selected
        var done = 0
        showCard(state("Another size").copy(page = PageView.None), CardActions(onDoneChoosing = { done++ }))
        rule.onNodeWithText("Q4_K_M is already on this phone.").assertExists()
        rule.onNodeWithTag("done_choosing").performClick()
        assertEquals(1, done)
    }

    // a close that a drag catches leaves the card following its view again

    @Test
    fun aCloseCaughtOnItsWayDownFollowsTheLiveCardAgain() {
        lateinit var closer: CardCloser
        lateinit var sheet: DemoSheetState
        lateinit var scope: CoroutineScope
        var live by mutableStateOf(base)
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                sheet = rememberDemoSheetState()
                scope = rememberCoroutineScope()
                closer = remember { CardCloser(sheet, scope) }
                DemoSheet(onDismissRequest = {}, state = sheet) { Text(closer.shown(live)!!.header.name) }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { closer.close(live) {} }
        repeat(3) {
            rule.mainClock.advanceTimeBy(FRAME_MILLIS)
            rule.waitForIdle()
        }
        live = live.copy(header = live.header.copy(name = "Changed while closing"))
        rule.mainClock.advanceTimeBy(FRAME_MILLIS)
        rule.waitForIdle()
        rule.onNodeWithText("Changed while closing").assertDoesNotExist()
        // a drag catches the sheet: it goes back up
        rule.runOnIdle { scope.launch { sheet.expand() } }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("Changed while closing").assertExists()
    }

    private fun handleTop() = rule.onNodeWithContentDescription(DRAG_HANDLE).getBoundsInRoot().top

    private companion object {
        const val SHEET_FOOT = "ds_sheet_foot"
        const val DRAG_HANDLE = "Drag handle"
        const val FRAMES = 20
        const val GROW_FRAMES = 90
        const val NAME_WORDS = 14

        // 48 ms in: inside the 90 ms fade out, the change already composed
        const val HOLD_FRAME = 2
        const val FRAME_MILLIS = 16L
    }

    @Test
    fun getAnotherSizeShowsTheSizesWithTheOneOnThePhoneMarked() {
        // a card on the phone opens short (no sizes yet); "Get another size" adds them, and the person sees them, the
        // file already here marked, not cut off below the card's first height
        val onPhone =
            CardPreviews.states
                .single { it.first == "On this phone" }
                .second
                .copy(page = PageView.None)
        val another =
            CardPreviews.states
                .single { it.first == "Another size" }
                .second
                .copy(page = PageView.None)
        var view by mutableStateOf(onPhone)
        rule.setContent { DemoTheme(ThemeMode.Light) { ModelCardSheet(view, CardActions(), onDismiss = {}) } }
        rule.waitForIdle()
        view = another
        rule.waitForIdle()
        rule
            .onNode(
                hasText("On this phone", substring = true) and hasAnyAncestor(hasTestTag("tier_Recommended")),
                useUnmergedTree = true,
            ).assertIsDisplayed()
        rule.onNode(hasTestTag("tier_Best"), useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * "Get another size" is one motion: the sheet grows with its scroll held, and only then, if the
     * size Get would fetch is still off screen, the least scroll brings it into view; in Advanced as in Basic.
     */
    @Test
    fun getAnotherSizeGrowsTheSheetFirstThenBringsOnlyTheSelectedSizeIntoView() {
        val onPhone =
            CardPreviews.states
                .single { it.first == "On this phone" }
                .second
                .copy(page = PageView.None)
        val another =
            CardPreviews.states
                .single { it.first == "Another size" }
                .second
                .copy(page = PageView.None)
        val choice = checkNotNull(another.choice)
        val more =
            listOf("Q2_K", "Q3_K_S", "IQ4_XS", "Q4_K_S", "Q5_K_S", "Q5_K_M", "Q6_K", "Q8_K_XL").mapIndexed { i, q ->
                QuantView(q, 2_000_000_000L + i * 300_000_000L, null, recommended = false, onPhone = false)
            }
        val advanced =
            another.copy(
                choice = choice.copy(mode = SheetMode.Advanced, quants = choice.quants + more, selected = "Q6_K"),
            )
        var view by mutableStateOf(onPhone)
        rule.setContent { DemoTheme(ThemeMode.Light) { ModelCardSheet(view, CardActions(), onDismiss = {}) } }
        rule.waitForIdle()
        val compact = handleTop()
        rule.mainClock.autoAdvance = false
        view = advanced
        val name = { rule.onNodeWithText(advanced.header.name).getBoundsInRoot().top }
        val frames =
            (0 until GROW_FRAMES).map {
                rule.mainClock.advanceTimeBy(FRAME_MILLIS)
                rule.waitForIdle()
                handleTop() to name() - handleTop()
            }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val settled = handleTop()
        assertTrue("the sheet grew: $compact to $settled", settled < compact)
        // the content moved within the sheet (a scroll) only once the sheet stood at its final top
        frames.zipWithNext().forEachIndexed { i, (before, after) ->
            if ((after.second - before.second).value.let { kotlin.math.abs(it) } > 0.5f) {
                assertEquals(
                    "frame ${i + 1} scrolled before the sheet had grown",
                    settled.value,
                    before.first.value,
                    0.5f,
                )
            }
        }
        val selected =
            rule.onNode(
                hasTestTag("quant_row") and hasAnyDescendant(hasText("Q6_K")),
                useUnmergedTree = true,
            )
        val scroll = rule.onNodeWithTag("ds_sheet_scroll").getBoundsInRoot()
        val row = selected.getBoundsInRoot()
        assertTrue(
            "the selected size is in view: $row in $scroll",
            row.top >= scroll.top && row.bottom <= scroll.bottom,
        )
    }
}
