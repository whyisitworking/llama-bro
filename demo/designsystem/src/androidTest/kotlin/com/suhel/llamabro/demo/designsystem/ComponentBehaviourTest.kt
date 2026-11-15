package com.suhel.llamabro.demo.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.demo.designsystem.components.CardTint
import com.suhel.llamabro.demo.designsystem.components.Composer
import com.suhel.llamabro.demo.designsystem.components.ComposerState
import com.suhel.llamabro.demo.designsystem.components.DemoCard
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.DsTags
import com.suhel.llamabro.demo.designsystem.components.FitText
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.GroupedList
import com.suhel.llamabro.demo.designsystem.components.JumpToLatest
import com.suhel.llamabro.demo.designsystem.components.ListRow
import com.suhel.llamabro.demo.designsystem.components.PhotoViewer
import com.suhel.llamabro.demo.designsystem.components.PrimaryButton
import com.suhel.llamabro.demo.designsystem.components.QuantRow
import com.suhel.llamabro.demo.designsystem.components.Reveal
import com.suhel.llamabro.demo.designsystem.components.Screen
import com.suhel.llamabro.demo.designsystem.components.Section
import com.suhel.llamabro.demo.designsystem.components.SliderRow
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.designsystem.components.TypingDots
import com.suhel.llamabro.demo.designsystem.components.ViewerPhoto
import com.suhel.llamabro.demo.designsystem.components.WakeHairline
import com.suhel.llamabro.demo.designsystem.components.ink
import com.suhel.llamabro.demo.designsystem.theme.DemoMotion
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.Light
import com.suhel.llamabro.demo.designsystem.theme.LocalAnimatorDurationScale
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Behaviour the screenshots can't show. */
@RunWith(AndroidJUnit4::class)
class ComponentBehaviourTest {
    @get:Rule
    val rule = createComposeRule()

    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun quantRowsHaveEqualHeight() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column(Modifier.width(360.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuantRow("Q2_K", "1.6 GB", "Fits", CardTint.TooBig, selected = false)
                    QuantRow(
                        "Q4_K_M",
                        "2.8 GB",
                        "Runs well",
                        CardTint.Great,
                        selected = true,
                        edgeLabel = "Recommended",
                    )
                    QuantRow("Q8_0", "4.5 GB", "Slow", CardTint.Slow, selected = false)
                    QuantRow("BF16", "0.7 GB", "Included", CardTint.Great, selected = false, included = true)
                    QuantRow(
                        "UD-Q4_K_XL-with-a-long-name",
                        "12.4 GB",
                        "A long label that cannot fit",
                        CardTint.Plain,
                        selected = false,
                    )
                }
            }
        }
        val heights =
            rule.onAllNodesWithTag(DsTags.CARD_SURFACE, useUnmergedTree = true).fetchSemanticsNodes().map {
                it.size.height
            }
        assertEquals(5, heights.size)
        assertEquals("row heights $heights", 1, heights.toSet().size)
    }

    @Test
    fun aFitIsAWordInItsColourAndNeverACardFill() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column(
                    Modifier.width(360.dp).background(Light.canvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (tint in CardTint.entries) {
                        Box(Modifier.testTag("card_$tint")) {
                            DemoCard { FitText("Runs great", tint, Modifier.testTag("fit_$tint")) }
                        }
                    }
                }
            }
        }
        for (tint in CardTint.entries) {
            val card = rule.onNodeWithTag("card_$tint").captureToImage().toPixelMap()
            // the card's straight left edge, clear of the corners, the shadow and the text: the surface, whatever the fit
            assertClose("$tint fill is the surface", Light.surface, card[12, card.height / 2])
            val fit = rule.onNodeWithTag("fit_$tint", useUnmergedTree = true).captureToImage().toPixelMap()
            val ink = tint.ink(Light)
            var best = Int.MAX_VALUE
            for (x in 0 until fit.width) for (y in 0 until fit.height) best = minOf(best, distance(ink, fit[x, y]))
            assertTrue("$tint label drawn in its ink (closest channel distance $best)", best <= 8)
        }
    }

    @Test
    fun aDisabledButtonIsAFlatHairlinePill() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column(
                    Modifier.width(360.dp).background(Light.canvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PrimaryButton("Get Swift", onClick = {}, modifier = Modifier.testTag("on"))
                    PrimaryButton("Get Swift", onClick = {}, modifier = Modifier.testTag("off"), enabled = false)
                }
            }
        }
        val on = rule.onNodeWithTag("on").captureToImage().toPixelMap()
        val off = rule.onNodeWithTag("off").captureToImage().toPixelMap()
        // the fill at the middle of the pill, clear of the corners and the label
        assertClose("enabled fill is the accent", Light.accent, on[on.width / 2, 8])
        assertClose("disabled fill is the hairline", Light.hairline, off[off.width / 2, 8])
    }

    @Test
    fun aSelectedCardIsOutlinedInTheAccentAndAnUnselectedOneIsNot() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column(
                    Modifier.width(360.dp).background(Light.canvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.testTag("on")) { DemoCard(selected = true) { Text("Chosen") } }
                    Box(Modifier.testTag("off")) { DemoCard { Text("Not chosen") } }
                }
            }
        }
        val on = rule.onNodeWithTag("on").captureToImage().toPixelMap()
        val off = rule.onNodeWithTag("off").captureToImage().toPixelMap()
        // inside the 2 dp border at the card's straight left edge
        assertClose("selected border", Light.accent, on[2, on.height / 2])
        assertTrue("unselected has no accent edge", distance(Light.accent, off[2, off.height / 2]) > 30)
    }

    @Test
    fun composerSendBecomesStop() {
        var busy by mutableStateOf(false)
        var sent = 0
        var stopped = 0
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Composer(
                    state = ComposerState("Hi", canAttach = true, think = ThinkLevel.Off, busy = busy),
                    placeholder = "Message Swift",
                    onTextChange = {},
                    onSend = { sent++ },
                    onStop = { stopped++ },
                    onAttach = {},
                    onThink = {},
                )
            }
        }
        val send = string(R.string.ds_send)
        val stop = string(R.string.ds_stop)
        rule.onNodeWithContentDescription(stop).assertDoesNotExist()
        rule.onNodeWithContentDescription(send).performClick()
        assertEquals(1, sent)

        busy = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription(send).assertDoesNotExist()
        rule.onNodeWithContentDescription(stop).performClick()
        assertEquals(1, stopped)
        assertEquals(1, sent)
    }

    @Test
    fun aSheetThatGrowsAfterOpeningKeepsItsContentOnScreen() {
        // a model sheet opens on "Looking at the sizes…" and then grows into the size cards and the Get button: the
        // sheet was found parked at its first height with the new content cut off (F118; intermittent on the phone)
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
    fun sendIsQuietWithNothingToSendAndLiveWithTextOrAPhoto() {
        var state by mutableStateOf(ComposerState("", canAttach = true, think = null, busy = false))
        var sent = 0
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Composer(
                    state = state,
                    placeholder = "Message",
                    onTextChange = {},
                    onSend = { sent++ },
                    onStop = {},
                    onAttach = {},
                    onThink = {},
                )
            }
        }
        val send = string(R.string.ds_send)
        rule.onNodeWithContentDescription(send).performClick()
        assertEquals("send with nothing to send does nothing", 0, sent)
        state = state.copy(hasAttachment = true)
        rule.waitForIdle()
        rule.onNodeWithContentDescription(send).performClick()
        assertEquals("a photo alone can be sent", 1, sent)
        state = state.copy(hasAttachment = false, text = "Hi")
        rule.waitForIdle()
        rule.onNodeWithContentDescription(send).performClick()
        assertEquals(2, sent)
    }

    @Test
    fun jumpToLatestClicks() {
        var clicks = 0
        rule.setContent { DemoTheme(ThemeMode.Light) { JumpToLatest(visible = true, onClick = { clicks++ }) } }
        rule.onNodeWithContentDescription(string(R.string.ds_jump_to_latest)).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun composerIsTextOverARowOfButtonsConcentricWithItsCorner() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Composer(
                    state = ComposerState("", canAttach = true, think = ThinkLevel.Off, busy = false),
                    placeholder = "Message Qwen3.5 0.8B",
                    onTextChange = {},
                    onSend = {},
                    onStop = {},
                    onAttach = {},
                    onThink = {},
                    modifier = Modifier.width(360.dp).testTag("bar"),
                )
            }
        }
        val bar = rule.onNodeWithTag("bar").getBoundsInRoot()
        // one line of text (16 above, its own height, 4 below) over a row of 48 dp targets with 4 dp under them: the line
        // is about 19 dp (the body role trims its line box), so the bar is about 91 dp; the range allows other fonts
        assertTrue("one-line bar height ${bar.height.value}", bar.height.value in 88f..98f)
        val attach = rule.onNodeWithContentDescription(string(R.string.ds_attach)).getBoundsInRoot()
        val think = rule.onNodeWithContentDescription("Think", substring = true).getBoundsInRoot()
        val send = rule.onNodeWithContentDescription(string(R.string.ds_send)).getBoundsInRoot()
        // each button's press is a 36 dp circle (D73), 10 dp from the bar's bottom; + is 10 dp from the start, send 10 dp
        // from the end, so each circle's centre stays 28 dp in, concentric with the corner (18 + 10 = 28)
        for ((name, button) in listOf("+" to attach, "think" to think, "send" to send)) {
            assertEquals("$name is 36 dp", 36f, (button.right - button.left).value, 1f)
            assertEquals("$name from the bottom", 10f, bar.bottom.value - button.bottom.value, 1f)
        }
        assertEquals(10f, attach.left.value - bar.left.value, 1f)
        assertEquals(10f, bar.right.value - send.right.value, 1f)
        // Think sits just left of send, 12 dp from it (their 48 dp targets touch)
        assertEquals(12f, send.left.value - think.right.value, 1f)
    }

    @Test
    fun wakeHairlineFillsToTheLoadsProgress() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Box(Modifier.width(300.dp).background(Light.canvas)) { WakeHairline(active = true, progress = 0.5f) }
            }
        }
        rule.waitForIdle()
        val map = rule.onNodeWithTag(DsTags.WAKE_HAIRLINE).captureToImage().toPixelMap()
        val y = map.height / 2
        assertEquals("filled up to the value: the accent", Light.accent, map[map.width / 4, y])
        assertEquals("beyond it: the soft track", Light.accentSoft, map[map.width * 3 / 4, y])
    }

    @Test
    fun wakeHairlineIsStaticWhenAnimationsAreOff() {
        var scale by mutableFloatStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            // the seam animationsDisabled() reads instead of Settings.Global, so the phone's setting is never touched
            CompositionLocalProvider(LocalAnimatorDurationScale provides scale) {
                DemoTheme(ThemeMode.Light) {
                    Box(Modifier.width(300.dp).background(Light.canvas)) { WakeHairline(active = true) }
                }
            }
        }

        fun frame(): IntArray {
            val map = rule.onNodeWithTag(DsTags.WAKE_HAIRLINE).captureToImage().toPixelMap()
            return IntArray(map.width * map.height) { map[it % map.width, it / map.width].hashCode() }
        }

        rule.mainClock.advanceTimeByFrame()
        val still1 = frame()
        rule.mainClock.advanceTimeBy(700)
        val still2 = frame()
        assertTrue("animations off: the hairline doesn't move", still1.contentEquals(still2))
        assertTrue("animations off: the hairline is drawn", still1.toSet().size > 1)

        scale = 1f
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(350)
        val moving1 = frame()
        rule.mainClock.advanceTimeBy(350)
        val moving2 = frame()
        assertFalse("animations on: the sweep moves (the check above can see motion)", moving1.contentEquals(moving2))
    }

    @Test
    fun aRevealKeepsItsLastLineWhileItFoldsAway() {
        var note by mutableStateOf<String?>("Writer may not fit")
        rule.setContent { DemoTheme(ThemeMode.Light) { Reveal(note) { Text(it) } } }
        rule.onNodeWithText("Writer may not fit").assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        note = null
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(DemoMotion.EXIT_MILLIS / 2L)
        // halfway out, it still says what it said, not nothing
        rule.onNodeWithText("Writer may not fit").assertExists()
        rule.mainClock.advanceTimeBy(DemoMotion.EXIT_MILLIS.toLong())
        rule.onNodeWithText("Writer may not fit").assertDoesNotExist()
    }

    @Test
    fun jumpToLatestLeavesWhenTheListIsAtItsEnd() {
        var away by mutableStateOf(true)
        rule.setContent { DemoTheme(ThemeMode.Light) { JumpToLatest(visible = away, onClick = {}) } }
        val label = string(R.string.ds_jump_to_latest)
        rule.onNodeWithContentDescription(label).assertIsDisplayed()
        away = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription(label).assertDoesNotExist()
    }

    @Test
    fun typingDotsSayReplyingAndHoldStillWithAnimationsOff() {
        var scale by mutableFloatStateOf(0f)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalAnimatorDurationScale provides scale) {
                DemoTheme(ThemeMode.Light) {
                    Box(Modifier.background(Light.canvas).testTag("dots")) { TypingDots() }
                }
            }
        }

        fun frame(): IntArray {
            val map = rule.onNodeWithTag("dots").captureToImage().toPixelMap()
            return IntArray(map.width * map.height) { map[it % map.width, it / map.width].hashCode() }
        }

        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription(string(R.string.ds_replying)).assertExists()
        val still1 = frame()
        rule.mainClock.advanceTimeBy(DemoMotion.DOTS_MILLIS / 3L)
        assertTrue("animations off: the dots don't move", still1.contentEquals(frame()))

        scale = 1f
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(DemoMotion.DOTS_MILLIS / 6L)
        val moving1 = frame()
        rule.mainClock.advanceTimeBy(DemoMotion.DOTS_MILLIS / 3L)
        assertFalse("animations on: the dots pulse", moving1.contentEquals(frame()))
    }

    @Test
    fun anEdgeLabelSitsInsideTheCardAtItsTopEnd() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Box(Modifier.width(360.dp).padding(24.dp)) {
                    DemoCard(
                        modifier = Modifier.fillMaxWidth(),
                        selected = true,
                        edgeLabel = "Default",
                    ) {
                        Text("Writer")
                    }
                }
            }
        }
        val card = rule.onNodeWithTag(DsTags.CARD_SURFACE, useUnmergedTree = true).getBoundsInRoot()
        val chip = rule.onNodeWithText("Default", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("the label is inside the card's top and bottom", chip.top >= card.top && chip.bottom <= card.bottom)
        assertTrue(
            "the label is within the card's width, in the end half",
            chip.left.value > (card.left.value + card.right.value) / 2,
        )
        // the text's bounds exclude the chip's 8 dp of side padding
        assertNear("the label ends 16 dp from the card's end", card.right - DemoSpacing.item - 8.dp, chip.right)
        val text = rule.onNodeWithText("Writer", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("the card's own text starts below the label", text.top >= chip.bottom)
    }

    private fun distance(a: Color, b: Color): Int =
        maxOf(
            abs((a.red * 255).toInt() - (b.red * 255).toInt()),
            abs((a.green * 255).toInt() - (b.green * 255).toInt()),
            abs((a.blue * 255).toInt() - (b.blue * 255).toInt()),
        )

    private fun assertClose(what: String, expected: Color, actual: Color) {
        assertTrue("$what: expected $expected, got $actual", distance(expected, actual) <= 3)
    }

    /** Text is laid out in whole pixels, so a distance read from a text's bounds is within a pixel of the spec's. */
    private fun assertNear(
        message: String,
        expected: androidx.compose.ui.unit.Dp,
        actual: androidx.compose.ui.unit.Dp,
    ) = assertTrue("$message: expected $expected, was $actual", abs(expected.value - actual.value) < 0.5f)

    @Test
    fun aScreenPutsItsPartsOnTheGutterWithTheSectionGapBetween() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Screen(
                    topBar = { Box(Modifier.height(64.dp)) },
                    modifier = Modifier.width(360.dp).height(480.dp),
                    scrolls = false,
                ) {
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("first"))
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("second"))
                }
            }
        }
        val first = rule.onNodeWithTag("first").getBoundsInRoot()
        val second = rule.onNodeWithTag("second").getBoundsInRoot()
        assertEquals("the left gutter", 16.dp, first.left)
        assertEquals("the right gutter", 344.dp, first.right)
        assertEquals("below the 64 dp bar and the item gap", 80.dp, first.top)
        assertEquals("the section gap between parts", Gap.Section.size, second.top - first.bottom)
    }

    @Test
    fun aSectionSitsItsLabelCloserToItsOwnContentThanToTheSectionAbove() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Stack(Gap.Section, Modifier.width(360.dp)) {
                    Section("One") { Box(Modifier.fillMaxWidth().height(40.dp).testTag("a1")) }
                    Section("Two") {
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag("b1"))
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag("b2"))
                    }
                }
            }
        }
        val a1 = rule.onNodeWithTag("a1").getBoundsInRoot()
        val b1 = rule.onNodeWithTag("b1").getBoundsInRoot()
        val b2 = rule.onNodeWithTag("b2").getBoundsInRoot()
        assertEquals("the parts of a section are a block apart", Gap.Block.size, b2.top - b1.bottom)
        val labelTop = rule.onNodeWithText("TWO").getBoundsInRoot().top
        val labelBottom = rule.onNodeWithText("TWO").getBoundsInRoot().bottom
        assertNear("the label sits an inline gap above its content", Gap.Inline.size, b1.top - labelBottom)
        assertNear("the section above ends a section gap above the label", Gap.Section.size, labelTop - a1.bottom)
        assertTrue(
            "the label is closer to its content than to the section above",
            b1.top - labelBottom < labelTop - a1.bottom,
        )
    }

    @Test
    fun aRowIs56dpWithOneLineAnd72dpWithTwoAndItsTrailingTextEndsOnThePaddingEdge() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column(Modifier.width(360.dp)) {
                    ListRow("One line", Modifier.testTag("one"), trailing = "1.0")
                    ListRow("Two lines", Modifier.testTag("two"), subtitle = "A subtitle", chevron = true)
                }
            }
        }
        assertNear("one line", 56.dp, rule.onNodeWithTag("one").getBoundsInRoot().height)
        assertNear("two lines", 72.dp, rule.onNodeWithTag("two").getBoundsInRoot().height)
        assertNear(
            "trailing text ends 16 dp from the row's end",
            344.dp,
            rule.onNodeWithText("1.0").getBoundsInRoot().right,
        )
    }

    @Test
    fun aGroupedListHasADividerBetweenRowsAndNoneAfterTheLast() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                GroupedList(Modifier.width(360.dp).testTag("group")) {
                    row { ListRow("A") }
                    row { ListRow("B") }
                    row { ListRow("C") }
                }
            }
        }
        // three 56 dp rows and two 1 dp dividers
        assertNear("the group's height", 170.dp, rule.onNodeWithTag("group").getBoundsInRoot().height)
    }

    @Test
    fun aSliderRowSaysItsValueAndReportsAChangeOnce() {
        val changes = mutableListOf<Float>()
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column(Modifier.width(360.dp)) {
                    SliderRow(
                        "Temperature",
                        1f,
                        { "%.1f".format(java.util.Locale.ROOT, it) },
                        0f..2f,
                        steps = 39,
                        onValueChange = { changes += it },
                        note = "Model default",
                    )
                }
            }
        }
        rule
            .onNodeWithContentDescription("Temperature")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.0"))
        rule.onNodeWithText("Model default").assertExists()
        rule.onNodeWithContentDescription("Temperature").performSemanticsAction(SemanticsActions.SetProgress) {
            it(0.5f)
        }
        rule.waitForIdle()
        assertEquals(listOf(0.5f), changes)
    }

    private fun showViewer(
        photos: List<ViewerPhoto> = List(3) { ViewerPhoto.Ready(ImageBitmap(400, 300)) },
        onPageChange: (Int) -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    PhotoViewer(photos, startAt = 1, onClose = onClose, onPageChange = onPageChange)
                }
            }
        }
    }

    private fun page(n: Int) = rule.onNodeWithContentDescription("Photo $n of 3")

    @Test
    fun thePhotoViewerOpensAtTheTappedPhotoAndSwipesBetweenThem() {
        showViewer()
        page(2).assertIsDisplayed()
        rule.onNodeWithTag(DsTags.PHOTO_VIEWER).performTouchInput { swipeLeft() }
        page(3).assertIsDisplayed()
    }

    @Test
    fun aZoomedPhotoPansInsteadOfSwipingAndADoubleTapZoomsBackOut() {
        showViewer()
        page(2).performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(DsTags.PHOTO_VIEWER).performTouchInput { swipeLeft() }
        page(2).assertIsDisplayed() // panned within the photo, still on it
        page(2).performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(DsTags.PHOTO_VIEWER).performTouchInput { swipeLeft() }
        page(3).assertIsDisplayed()
    }

    @Test
    fun aSwipeDownOrTheCrossClosesThePhotoViewer() {
        var closed = 0
        showViewer(onClose = { closed++ })
        rule.onNodeWithTag(DsTags.PHOTO_VIEWER).performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertEquals(1, closed)
        rule.onNodeWithContentDescription(string(R.string.ds_close_photo)).performClick()
        assertEquals(2, closed)
    }

    @Test
    fun aShortPullSpringsBackWithoutClosing() {
        var closed = 0
        showViewer(onClose = { closed++ })
        rule.onNodeWithTag(DsTags.PHOTO_VIEWER).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + height * 0.05f)
        }
        rule.waitForIdle()
        assertEquals(0, closed)
        page(2).assertIsDisplayed()
    }

    @Test
    fun theViewerTellsWhichPhotoIsInView() {
        val pages = mutableListOf<Int>()
        showViewer(onPageChange = { pages += it })
        rule.onNodeWithTag(DsTags.PHOTO_VIEWER).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(listOf(1, 2), pages)
    }

    @Test
    fun aDoubleTapWithASmallWobbleStillZoomsOut() {
        showViewer()
        page(2).performTouchInput { doubleClick() }
        rule.waitForIdle()
        // a real finger moves a pixel or two between down and up: under the touch slop it is still a tap
        page(2).performTouchInput {
            down(center)
            moveBy(Offset(2f, 1f))
            up()
            advanceEventTime(100)
            down(center)
            moveBy(Offset(-1f, 2f))
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithTag(DsTags.PHOTO_VIEWER).performTouchInput { swipeLeft() }
        page(3).assertIsDisplayed()
    }

    @Test
    fun aPhotoNotYetDecodedNeitherZoomsNorHoldsTheSwipe() {
        showViewer(listOf(ViewerPhoto.Loading, ViewerPhoto.Loading, ViewerPhoto.Loading))
        page(2).performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(DsTags.PHOTO_VIEWER).performTouchInput { swipeLeft() }
        page(3).assertIsDisplayed()
    }

    @Test
    fun aPhotoThatCantBeDecodedSaysSo() {
        showViewer(listOf(ViewerPhoto.Loading, ViewerPhoto.Unavailable, ViewerPhoto.Loading))
        rule.onNodeWithText(string(R.string.ds_photo_unavailable)).assertIsDisplayed()
    }
}
