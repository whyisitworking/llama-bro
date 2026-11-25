package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.data.ContextChoice
import com.suhel.llamabro.demo.data.KeepLoaded
import com.suhel.llamabro.demo.data.ReplyLength
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings at twice the font on a 360 dp phone: every row's subtitle is read whole, none cut short. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsLargeTextTest {
    @get:Rule val rule = createComposeRule()

    @Test fun atTwiceTheFontNoSubtitleOnTheSettingsPageIsCut() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                DemoTheme(ThemeMode.Light) {
                    SettingsHome(
                        onBack = {},
                        onOpen = {},
                        routes = SettingsRoutes("a", "b", "c", "d", "e", "f", "g", "h", "i"),
                    )
                }
            }
        }
        for (subtitle in SUBTITLES) {
            val results = mutableListOf<TextLayoutResult>()
            rule
                .onNodeWithText(subtitle, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(results)
            val layout = results.single()
            assertFalse("\"$subtitle\" is cut", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
        }
    }

    @Test fun homeListsInferenceProcessorMemoryAndNoStorage() {
        rule.setContent {
            DemoTheme(
                ThemeMode.Light,
            ) { SettingsHome({}, {}, SettingsRoutes("a", "b", "c", "d", "e", "f", "g", "h", "i")) }
        }
        val titles = listOf("Default model", "Hugging Face token", "Inference", "Processor", "Memory", "Voice")
        val tops =
            titles.map {
                rule
                    .onNodeWithText(it)
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            }
        assertEquals(tops.sorted(), tops)
        rule.onNodeWithText("Sampling").assertDoesNotExist()
        rule.onNodeWithText("Storage").assertDoesNotExist()
        rule.onNodeWithText("The models on this phone").assertDoesNotExist()
    }

    @Test fun inferenceHoldsContextThenReplyLength() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                InferenceContent(
                    InferenceState(ContextChoice.Automatic, null, ReplyLength.Automatic, emptyList()),
                    onContext = {},
                    onLength = {},
                    onOpen = {},
                    onBack = {},
                )
            }
        }
        rule.onNodeWithTag("inference_settings").assertExists()
        val context =
            rule
                .onNodeWithTag("context_length", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
        val reply =
            rule
                .onNodeWithTag("reply_length", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue(context < reply)
        rule.onNodeWithText("Changing it reloads the model.", useUnmergedTree = true).assertExists()
    }

    @Test fun processorHoldsTheChipChoice() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                ProcessorContent(ProcessorState(ChipChoice.Automatic, emptySet(), null), onChip = {}, onBack = {})
            }
        }
        rule.onNodeWithTag("processor_settings").assertExists()
        rule.onNodeWithTag("runs_on", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Changing it reloads the model.", useUnmergedTree = true).assertExists()
    }

    @Test fun memoryHasNoChipOrContext() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                MemoryContent(MemoryState(null, false, KeepLoaded.OneMinute), onUnload = {}, onKeep = {}, onBack = {})
            }
        }
        rule.onNodeWithTag("memory_settings").assertExists()
        rule.onNodeWithTag("runs_on", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("context_length", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("After you leave the app", ignoreCase = true).assertExists()
    }

    @Test fun voiceHasVoiceModeThenTheMessageBar() {
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                VoiceContent(
                    VoiceSettingsState(listOf("alloy"), "alloy", true, null, PhoneLanguage("en", "English")),
                    onVoice = {},
                    onCaption = {},
                    onBack = {},
                )
            }
        }

        // labels are drawn in capitals; the page's own title is "Voice" and is not a section
        fun top(text: String) =
            rule
                .onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.top
        val order =
            listOf(
                "VOICE MODE",
                "ITS VOICE",
                "TALKING",
                "CAPTIONS",
                "IN THE MESSAGE BAR",
                "DICTATION",
            ).map(::top)
        assertEquals(order.sorted(), order)
        assertEquals(order.size, order.toSet().size)
        rule.onNodeWithText("VOICE", useUnmergedTree = true).assertDoesNotExist()
        rule
            .onNodeWithText("Uses less memory; your turns stay as voice messages; changing it reloads the model")
            .assertExists()
    }

    private companion object {
        val SUBTITLES =
            listOf(
                "Layout, theme and glass",
                "Tell every chat about you",
                "Which model new chats start with",
                "For models that need one",
                "Context, reply length and each model's values",
                "Where models run: NPU, GPU or CPU",
                "The model in memory, and when it's freed",
                "Voice mode and dictation",
                "Versions and licences",
            )
    }
}
