package com.suhel.llamabro.demo.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.components.CapabilityIcons
import com.suhel.llamabro.demo.designsystem.components.DemoIcons
import com.suhel.llamabro.demo.designsystem.components.FitLevel
import com.suhel.llamabro.demo.designsystem.components.FitSymbol
import com.suhel.llamabro.demo.designsystem.components.LoadingSlot
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The model row's pieces: the fit symbol, the capability line and the loading slot. */
@RunWith(AndroidJUnit4::class)
class ModelRowBehaviourTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun loadingSlotHasTheSameHeightActiveOrNot() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column(Modifier.width(200.dp)) {
                    LoadingSlot(active = true, Modifier.testTag("on"))
                    LoadingSlot(active = false, Modifier.testTag("off"))
                }
            }
        }
        val on = rule.onNodeWithTag("on").getBoundsInRoot().height
        val off = rule.onNodeWithTag("off").getBoundsInRoot().height
        assertEquals(on, off)
        assertEquals(4.dp, on)
    }

    @Test
    fun fitSymbolsShareOneSize() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column {
                    for (level in FitLevel.entries) FitSymbol(level, "words $level", Modifier.testTag("fit_$level"))
                }
            }
        }
        for (level in FitLevel.entries) {
            rule.onNodeWithTag("fit_$level").assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
        }
    }

    @Test
    fun fitSymbolSpeaksItsWords() {
        rule.setContent { DemoTheme(ThemeMode.Light) { FitSymbol(FitLevel.TooBig, "Too big") } }
        rule.onNodeWithContentDescription("Too big").assertExists()
    }

    @Test
    fun aLongPressOnTheFitSymbolShowsItsWords() {
        rule.setContent { DemoTheme(ThemeMode.Light) { FitSymbol(FitLevel.Well, "Runs well") } }
        // the words are a description, not text: no text node until the tooltip shows
        rule.onNodeWithText("Runs well").assertDoesNotExist()
        rule.onNodeWithContentDescription("Runs well").performTouchInput { longClick() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Runs well").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun capabilityIconsReadCapabilitiesThenTheFact() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                CapabilityIcons(
                    listOf(DemoIcons.CapabilityThinks to "Thinks", DemoIcons.CapabilityImages to "Sees images"),
                    "1.2M downloads",
                )
            }
        }
        rule.onNodeWithContentDescription("Thinks, Sees images, 1.2M downloads", useUnmergedTree = true).assertExists()
    }
}
