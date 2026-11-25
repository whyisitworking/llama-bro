package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.LocalAnimatorDurationScale
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A chip of glass answers a finger itself (D155), alongside its click: held or dragged, it draws each frame of its
 * swell, pull and light (here with or without animations), and let go it settles, having clicked once unless the
 * finger slid off it. How it looks
 * is for a phone: a host test draws no pixels to compare.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class GlassPressTest {
    @get:Rule
    val rule = createComposeRule()

    private var clicks = 0

    private fun show(animatorScale: Float) {
        rule.setContent {
            CompositionLocalProvider(LocalAnimatorDurationScale provides animatorScale) {
                DemoTheme(ThemeMode.Light) {
                    FloatingFrame(
                        top = {
                            CompositionLocalProvider(LocalGlassChips provides true) {
                                IconAction(DemoIcons.Plus, "Chip", { clicks++ }, Modifier.testTag("chip"))
                            }
                        },
                        bottom = { BottomBar { GlassPreview() } },
                    ) {
                        Box(Modifier.fillMaxSize().background(LocalDemoColors.current.ink))
                    }
                }
            }
        }
    }

    private fun pressAndLetGo() {
        val chip = rule.onNodeWithTag("chip")
        chip.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HELD_MILLIS)
        chip.assertIsDisplayed()
        chip.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(SETTLED_MILLIS)
    }

    @Test fun aPressedChipClicksOnceItIsLetGo() {
        show(animatorScale = 1f)
        pressAndLetGo()
        assertEquals(1, clicks)
    }

    @Test fun withAnimationsOffAPressedChipStillClicks() {
        show(animatorScale = 0f)
        pressAndLetGo()
        assertEquals(1, clicks)
    }

    @Test fun aChipDraggedOffAndLetGoDoesntClick() {
        show(animatorScale = 1f)
        val chip = rule.onNodeWithTag("chip")
        chip.performTouchInput {
            down(center)
            moveBy(Offset(width * 3f, height * 2f))
        }
        rule.mainClock.advanceTimeBy(HELD_MILLIS)
        chip.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(SETTLED_MILLIS)
        assertEquals(0, clicks)
    }

    @Test fun aChipDraggedWithinItselfStillClicks() {
        show(animatorScale = 1f)
        val chip = rule.onNodeWithTag("chip")
        chip.performTouchInput {
            down(center)
            moveBy(Offset(width / 4f, 0f))
        }
        rule.mainClock.advanceTimeBy(HELD_MILLIS)
        chip.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(SETTLED_MILLIS)
        assertEquals(1, clicks)
    }

    @Test fun aQuickTapClicksOnce() {
        show(animatorScale = 1f)
        rule.onNodeWithTag("chip").performClick()
        rule.mainClock.advanceTimeBy(SETTLED_MILLIS)
        assertEquals(1, clicks)
    }

    private companion object {
        const val HELD_MILLIS = 300L
        const val SETTLED_MILLIS = 2_000L
    }
}
