package com.suhel.llamabro.demo.ui.models

import androidx.activity.ComponentDialog
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.ui.chat.Markdown
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** The card's top row and its links (D156): Back on a stacked card, the model on Hugging Face on every card. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelCardTopRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val base = CardPreviews.notHere

    private fun show(actions: CardActions, view: CardView = base, opening: String? = null) {
        rule.setContent {
            DemoTheme(ThemeMode.Light) { ModelCardSheet(view, actions, onDismiss = {}, opening = opening) }
        }
        rule.waitForIdle()
    }

    @Test fun theRootCardHasNoBackAndOpensOnHuggingFace() {
        var opened = 0
        show(CardActions(onOpenInBrowser = { opened++ }))
        rule.onNodeWithTag("card_back").assertDoesNotExist()
        rule.onNodeWithContentDescription("Open on Hugging Face").performClick()
        assertEquals(1, opened)
    }

    @Test fun aStackedCardsBackGoesBack() {
        var back = 0
        show(CardActions(onBack = { back++ }))
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, back)
    }

    @Test fun theSystemBackGoesBackACardBeforeItCloses() {
        var back = 0
        var dismissed = 0
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                ModelCardSheet(base, CardActions(onBack = { back++ }), onDismiss = { dismissed++ })
            }
        }
        rule.waitForIdle()
        // the sheet is a window of its own: Back goes to its dispatcher
        rule.runOnUiThread {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        rule.waitForIdle()
        assertEquals(1, back)
        assertEquals(0, dismissed)
    }

    @Test fun aLinkBeingOpenedSaysSo() {
        show(CardActions(), opening = "Qwen/Qwen3.5-4B")
        rule.onNodeWithTag("sheet_opening").assertExists()
        rule.onNodeWithText("Opening Qwen/Qwen3.5-4B…").assertExists()
    }

    @Test fun aLinkOnThePageGoesToTheCard() {
        val links = mutableListOf<String>()
        val page =
            PageView.Markdown(
                Markdown.parsePage("Quantized from [the original](/Qwen/Qwen3.5-4B).", pageRepo = "unsloth/x"),
            )
        show(CardActions(onLink = { links += it }), base.copy(page = page))
        rule.onNodeWithTag("model_page").performScrollTo()
        // the page's text carries the link; its listener is the card's, not the platform's
        val text =
            rule
                .onNodeWithText("Quantized from the original.", substring = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .first()
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
        assertEquals("https://huggingface.co/Qwen/Qwen3.5-4B", link.url)
        checkNotNull(link.linkInteractionListener).onClick(link)
        assertEquals(listOf("https://huggingface.co/Qwen/Qwen3.5-4B"), links)
    }
}
