package com.suhel.llamabro.demo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.ui.chat.ChatContentPreview
import com.suhel.llamabro.demo.ui.chat.ChatScreenStates
import com.suhel.llamabro.demo.ui.settings.SamplingModelPreview
import com.suhel.llamabro.demo.ui.settings.SamplingStates
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * At twice the font on a 360 dp phone the words that name things are whole (models-page design §5, "the name is never
 * what gets cut"): the chat's model in its top bar, a segment's label.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeTextNamesTest {
    @get:Rule val rule = createComposeRule()

    private fun showLarge(content: @Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), content = content)
        }
    }

    private fun assertWhole(matcher: SemanticsMatcher, what: String) {
        val results = mutableListOf<TextLayoutResult>()
        rule
            .onNode(matcher, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(results)
        val layout = results.single()
        assertFalse("$what is cut", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
    }

    @Test fun atTwiceTheFontTheChatsModelNameIsWholeInTheTopBar() {
        showLarge { ChatContentPreview(ChatScreenStates().values.first()) }
        assertWhole(hasText("Qwen3.5 0.8B") and hasAnyAncestor(hasTestTag("model_title")), "the model's name")
    }

    @Test fun atTwiceTheFontASegmentsLabelIsWhole() {
        showLarge { SamplingModelPreview(SamplingStates().values.first()) }
        assertWhole(hasText("Not thinking"), "\"Not thinking\"")
    }
}
