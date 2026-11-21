package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat's model switcher on the one model row (models-page design §7): "Current" on the chat's model, the fit as a
 * symbol, a tap switches, a long-press offers "Use for new chats", the info action opens the model's card.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelSwitcherTest {
    @get:Rule val rule = createComposeRule()

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val deep = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
    private val library =
        listOf(
            LibraryModel(swift, "Qwen3.5 0.8B", 600_000_000, lastUsed = null, isDefault = false),
            LibraryModel(deep, "Qwen3.5 4B", 2_800_000_000, lastUsed = null, isDefault = true),
        )

    private val switched = mutableListOf<HfModel>()
    private val pinned = mutableListOf<HfModel>()
    private val opened = mutableListOf<HfModel>()
    private var done = 0

    private fun show() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                Column {
                    ModelSwitcherContent(
                        library,
                        current = swift,
                        facts = DeviceFacts(budgetBytes = 6L shl 30, chip = DeviceKind.Cpu),
                        onSwitch = { switched += it },
                        onUseForNewChats = { pinned += it },
                        onInfo = { opened += it },
                        onGetMore = {},
                        onDone = { done++ },
                    )
                }
            }
        }
    }

    private fun row(name: String) = rule.onNode(hasText(name) and hasAnyAncestor(hasTestTag("switcher")))

    @Test fun theChatsModelSaysCurrentAndTheDefaultSaysDefault() {
        show()
        rule.onNodeWithText("Current").assertExists()
        rule.onNodeWithText("Default").assertExists()
        rule.onAllNodesWithText("Current").assertCountEquals(1)
    }

    @Test fun theFitIsASymbolNotWords() {
        show()
        for (words in listOf("Runs great", "Runs well", "Slow here", "Too big")) {
            rule.onAllNodesWithText(words).assertCountEquals(0)
        }
    }

    @Test fun tappingARowSwitchesTheChatsModel() {
        show()
        row("Qwen3.5 4B").performClick()
        assertEquals(listOf(deep), switched)
        assertEquals(1, done)
    }

    @Test fun tappingTheCurrentModelSwitchesNothing() {
        show()
        row("Qwen3.5 0.8B").performClick()
        assertEquals(emptyList<HfModel>(), switched)
        assertEquals(1, done)
    }

    @Test fun aLongPressOffersUseForNewChats() {
        show()
        row("Qwen3.5 0.8B").performTouchInput { longClick() }
        rule.onNodeWithText("Use for new chats").performClick()
        assertEquals(listOf(swift), pinned)
        assertEquals(emptyList<HfModel>(), switched)
    }

    @Test fun theInfoActionOpensTheModelsCardWithoutSwitching() {
        show()
        rule.onNodeWithContentDescription("About Qwen3.5 4B").performClick()
        assertEquals(listOf(deep), opened)
        assertEquals(emptyList<HfModel>(), switched)
    }
}
