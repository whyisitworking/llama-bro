package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LightDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The sheet and the model page (models-page design §4; models audit S3, S5, S9, S25, S26, S27, S30): the card keeps the
 * height of its first layout, slides down before it goes, keeps its action in a 64 dp foot; the page's headings sit
 * under the sheet's own and its tables span the width.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class SheetAndPageTest {
    @get:Rule
    val rule = createComposeRule()

    private val handle get() = rule.onNodeWithContentDescription(DRAG_HANDLE)

    @Test
    fun anExpandableSheetKeepsItsFirstHeightWhenItsContentGrows() {
        var grown by mutableStateOf(false)
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                DemoSheet(
                    onDismissRequest = {},
                    expandable = true,
                    pinned = { Box(Modifier.fillMaxWidth().height(DemoSize.button).testTag("get")) },
                ) {
                    Text("Qwen3.5 4B")
                    if (grown) repeat(LINES) { Text("A line of the model's page, number $it") }
                }
            }
        }
        rule.waitForIdle()
        val first = handle.getBoundsInRoot().top
        grown = true
        rule.waitForIdle()
        assertEquals("the page landing moved the sheet", first, handle.getBoundsInRoot().top)
        rule.onNodeWithTag("get").assertIsDisplayed()
    }

    @Test
    fun aSettledSheetStillGoesFullScreenWhenPulledUp() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                DemoSheet(
                    onDismissRequest = {},
                    expandable = true,
                    pinned = { Box(Modifier.fillMaxWidth().height(DemoSize.button).testTag("get")) },
                ) {
                    repeat(LINES) { Text("A line of the model's page, number $it") }
                }
            }
        }
        rule.waitForIdle()
        val compact = rule.onNodeWithTag(DsTags.SHEET_SCROLL).getBoundsInRoot().height
        assertTrue("opens no taller than its compact height: $compact", compact <= DemoSize.sheetCompactHeight)
        rule.onNodeWithTag(DsTags.SHEET_SCROLL).performTouchInput { swipeUp() }
        rule.waitForIdle()
        val full = rule.onNodeWithTag(DsTags.SHEET_SCROLL).getBoundsInRoot().height
        assertTrue("pulled up, it takes the screen: $compact then $full", full > compact)
        rule.onNodeWithTag("get").assertIsDisplayed()
    }

    @Test
    fun aPlainSheetThatGrowsAfterOpeningKeepsItsContentOnScreen() {
        // F118: the short sheets (chat menus, the switcher) still follow their content up
        var grown by mutableStateOf(false)
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                DemoSheet(onDismissRequest = {}) {
                    Text("Looking at the sizes…")
                    if (grown) {
                        repeat(4) { Box(Modifier.fillMaxWidth().height(72.dp)) }
                        Box(Modifier.fillMaxWidth().height(56.dp).testTag("get"))
                    }
                }
            }
        }
        rule.waitForIdle()
        grown = true
        rule.waitForIdle()
        rule.onNodeWithTag("get").assertIsDisplayed()
    }

    @Test
    fun theActionSitsInAFootAtLeastSixtyFourDpTall() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                DemoSheet(
                    onDismissRequest = {},
                    expandable = true,
                    pinned = { PrimaryButton("Get Q4_K_M · 3.4 GB", onClick = {}, Modifier.fillMaxWidth()) },
                ) {
                    repeat(LINES) { Text("A line of the model's page, number $it") }
                }
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag(DsTags.SHEET_FOOT).getBoundsInRoot().height >= DemoSize.sheetFoot)
    }

    @Test
    fun aFootOfTwoButtonsIsNotClipped() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                DemoSheet(
                    onDismissRequest = {},
                    expandable = true,
                    pinned = {
                        PrimaryButton("Chat with Qwen3.5 4B", onClick = {}, Modifier.fillMaxWidth().testTag("chat"))
                        QuietButton("Another size", onClick = {}, Modifier.fillMaxWidth().testTag("another"))
                    },
                ) {
                    repeat(LINES) { Text("A line of the model's page, number $it") }
                }
            }
        }
        rule.waitForIdle()
        val foot = rule.onNodeWithTag(DsTags.SHEET_FOOT).getBoundsInRoot()
        for (tag in listOf("chat", "another")) {
            val button = rule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag is cut", button.top >= foot.top && button.bottom <= foot.bottom)
            assertEquals(tag, DemoSize.button, button.height)
        }
    }

    @Test
    fun aPlainSheetKeepsItsActionInlineWithoutAFoot() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                DemoSheet(onDismissRequest = {}, pinned = { Text("Done") }) { Text("Think") }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Done").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag(DsTags.SHEET_FOOT).fetchSemanticsNodes().size)
    }

    @Test
    fun hideThenDismissSlidesTheSheetDownBeforeDismissing() {
        var dismissed = 0
        lateinit var state: DemoSheetState
        lateinit var scope: CoroutineScope
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                state = rememberDemoSheetState()
                scope = rememberCoroutineScope()
                DemoSheet(onDismissRequest = { dismissed++ }, state = state) { Text("Start a chat") }
            }
        }
        rule.waitForIdle()
        val open = handle.getBoundsInRoot().top
        rule.mainClock.autoAdvance = false
        scope.launch { state.hideThenDismiss { dismissed++ } }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals("dismissed before the slide", 0, dismissed)
        assertTrue("the sheet is sliding down", handle.getBoundsInRoot().top > open)
        rule.mainClock.advanceTimeBy(SLIDE_MS)
        assertEquals(1, dismissed)
    }

    @Test
    fun theDragHandleShowsNoTooltipAndTakesNoFocusFromTouch() {
        rule.setContent { DemoTheme(ThemeMode.Light) { DemoSheet(onDismissRequest = {}) { Text("Think") } } }
        rule.waitForIdle()
        handle.performTouchInput { longClick() }
        rule.waitForIdle()
        assertEquals("a tooltip showed", 0, rule.onAllNodesWithText(DRAG_HANDLE).fetchSemanticsNodes().size)
        if (handle.fetchSemanticsNode().config.getOrNull(SemanticsActions.RequestFocus) != null) {
            handle.performSemanticsAction(SemanticsActions.RequestFocus)
        }
        handle.assertIsNotFocused()
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

    @Test
    fun aPagesTextIsQuietAndItsHeadingsAreSoftLabels() {
        val page =
            MarkdownBlocks(
                listOf(
                    MarkdownBlock.Heading(1, listOf(MarkdownSpan("Introduction"))),
                    MarkdownBlock.Heading(3, listOf(MarkdownSpan("Usage"))),
                    MarkdownBlock.Paragraph(listOf(MarkdownSpan("Runs on a phone."))),
                ),
            )
        rule.setContent { DemoTheme(ThemeMode.Light) { MarkdownText(page, onCopyCode = {}, page = true) } }
        // D123: the page is small and muted under the card's own lines; every heading a soft Regular label
        val body =
            rule
                .onNodeWithText("Runs on a phone.")
                .layout()
                .layoutInput.style
        assertEquals(DemoType.pageBody.fontSize, body.fontSize)
        assertEquals(LightDemoColors.muted, body.color)
        for (heading in listOf("Introduction", "Usage")) {
            val style =
                rule
                    .onNodeWithText(heading)
                    .layout()
                    .layoutInput.style
            assertEquals(heading, DemoType.quietLabel.fontSize, style.fontSize)
            assertEquals(heading, DemoType.quietLabel.fontWeight, style.fontWeight)
            assertEquals(heading, LightDemoColors.soft, style.color)
        }
    }

    @Test
    fun aReplysHeadingsTakeTheSerifLadder() {
        // D150: a reply's H1 to H4 in its serif heading roles, H5 and H6 as H4
        val levels = listOf(1 to DemoType.replyH1, 2 to DemoType.replyH2, 3 to DemoType.replyH3, 6 to DemoType.replyH4)
        val reply =
            MarkdownBlocks(levels.map { (level, _) -> MarkdownBlock.Heading(level, listOf(MarkdownSpan("H$level"))) })
        rule.setContent { DemoTheme(ThemeMode.Light) { MarkdownText(reply, onCopyCode = {}) } }
        for ((level, role) in levels) {
            val style =
                rule
                    .onNodeWithText("H$level")
                    .layout()
                    .layoutInput.style
            assertEquals("H$level size", role.fontSize, style.fontSize)
            assertEquals("H$level weight", role.fontWeight, style.fontWeight)
            assertEquals("H$level face", role.fontFamily, style.fontFamily)
        }
    }

    @Test
    fun aPagesNarrowTableSpansTheWidth() {
        val cell = { text: String -> listOf(MarkdownSpan(text)) }
        val table =
            MarkdownBlock.Table(
                alignments = listOf(MarkdownAlignment.Start, MarkdownAlignment.Start),
                header = listOf(cell("Quant"), cell("Size")),
                rows = listOf(listOf(cell("Q4_K_M"), cell("3.4 GB"))),
            )
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Box(Modifier.width(360.dp).testTag("page")) {
                    MarkdownText(MarkdownBlocks(listOf(table)), onCopyCode = {}, page = true)
                }
            }
        }
        val page = rule.onNodeWithTag("page").getBoundsInRoot()
        val last = rule.onNodeWithText("3.4 GB").getBoundsInRoot()
        // a cell's text sits inside its padding
        assertEquals("the table stops short of the edge", page.right, last.right + DemoSpacing.compact)
        assertFalse(rule.onNodeWithText("Quant").getBoundsInRoot().right >= last.left)
    }

    private val quotedTable =
        MarkdownBlocks(
            listOf(
                MarkdownBlock.Quote(
                    listOf(
                        MarkdownBlock.Table(
                            alignments = listOf(MarkdownAlignment.Start, MarkdownAlignment.Start),
                            header = listOf(listOf(MarkdownSpan("a")), listOf(MarkdownSpan("b"))),
                            rows = listOf(listOf(listOf(MarkdownSpan("1")), listOf(MarkdownSpan("2")))),
                        ),
                    ),
                ),
            ),
        )

    @Test
    fun aTableInAQuoteRendersInAReply() {
        // the quote's bar asks its blocks for their intrinsic height; a subcomposed table threw there
        rule.setContent { DemoTheme(ThemeMode.Light) { MarkdownText(quotedTable, onCopyCode = {}) } }
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun aTableInAQuoteRendersOnAPage() {
        rule.setContent { DemoTheme(ThemeMode.Light) { MarkdownText(quotedTable, onCopyCode = {}, page = true) } }
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    private companion object {
        const val DRAG_HANDLE = "Drag handle"
        const val LINES = 60
        const val SLIDE_MS = 2_000L
    }
}
