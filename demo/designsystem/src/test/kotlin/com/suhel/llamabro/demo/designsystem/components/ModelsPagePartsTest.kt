package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The parts the Models page adds to the library (models-page design §2, §6): a section's note, the fit key, chips. */
@RunWith(AndroidJUnit4::class)
class ModelsPagePartsTest {
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

    @Test
    fun aSectionsNoteSitsAtTheEndOfItsLine() {
        show { SectionLabel("On this phone", note = "6.1 GB · 120 GB free") }
        val label = rule.onNodeWithText("ON THIS PHONE").getBoundsInRoot()
        val note = rule.onNodeWithText("6.1 GB · 120 GB free").getBoundsInRoot()
        assertTrue("one line", note.top < label.bottom)
        assertTrue("at the end", note.left >= label.right)
    }

    @Test
    fun atTwiceTheFontASectionsNoteMovesUnderItsLabel() {
        show(fontScale = 2f) { SectionLabel("On this phone", note = "6.1 GB · 120 GB free") }
        val label = rule.onNodeWithText("ON THIS PHONE").getBoundsInRoot()
        assertTrue(rule.onNodeWithText("6.1 GB · 120 GB free").getBoundsInRoot().top >= label.bottom)
    }

    @Test
    fun theFitKeyShowsEachSymbolWithItsWords() {
        val words = listOf("Runs great", "Runs well", "Slow here", "Too big", "Can't chat")
        show { FitKey(FitLevel.entries.zip(words)) }
        for (word in words) rule.onNodeWithText(word).assertExists()
        // the words are said once, by the text: the symbol beside them is decoration
        rule.onAllNodesWithContentDescription("Runs great").assertCountEquals(0)
    }

    @Test
    fun aDialogCardShowsWhatItHoldsBetweenItsTextAndItsButtons() {
        show {
            DialogCard("What's a model?", "A model is the mind that answers you.", "Got it", {}, {}) {
                SectionNote("Inside")
            }
        }
        val text = rule.onNodeWithText("A model is the mind that answers you.").getBoundsInRoot()
        val inside = rule.onNodeWithText("Inside").getBoundsInRoot()
        val button = rule.onNodeWithText("Got it").getBoundsInRoot()
        assertTrue(inside.top >= text.bottom)
        assertTrue(button.top >= inside.bottom)
    }

    @Test
    fun startAlignedChipsBeginAtTheStart() {
        var picked = ""
        show { StarterChips(listOf("qwen coder"), onPick = { picked = it }, centered = false) }
        val chip = rule.onNodeWithText("qwen coder")
        assertEquals("starts at the edge, not centred", true, chip.getBoundsInRoot().left.value < 40f)
        chip.performClick()
        assertEquals("qwen coder", picked)
    }

    @Test
    fun aCrossfadedBodyShowsItsNewKind() {
        var mode by mutableStateOf(false)
        show { Crossfaded(mode) { searching -> SectionNote(if (searching) "Search" else "Browse") } }
        rule.onNodeWithText("Browse").assertExists()
        mode = true
        rule.waitForIdle()
        rule.onNodeWithText("Search").assertExists()
        rule.onNodeWithText("Browse").assertDoesNotExist()
    }
}
