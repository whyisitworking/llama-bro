package com.suhel.llamabro.demo.journey

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.suhel.llamabro.demo.models.Catalog
import org.junit.Test

/** A model the fake Hugging Face keeps behind a token. */
@OptIn(ExperimentalTestApi::class)
class GatedJourney : Journey(seenFirstRun = false) {
    private val swift = Catalog.picks[0].key

    @Test fun gatedModelNeedsAToken() {
        world.downloads.gated += swift
        seeTag("pick_card")
        tap("Qwen3.5 0.8B")
        tap("Get Qwen3.5 0.8B")

        // the download is refused: the chat says so in one line, with a button for what to do
        seeTag("chat_screen")
        see("This model needs a Hugging Face token.")
        shot("1-the-token-line")
        tap("Add token")

        // Settings → Hugging Face token: paste it, save
        seeTag("token_field")
        compose
            .onNode(
                hasSetTextAction() and hasAnyAncestor(hasTestTag("token_field")),
            ).performTextInput("hf_fake_token")
        compose.onNodeWithTag("token_save").performClick()
        see("Saved privately on this phone")
        shot("2-token-saved")

        // back in the chat the download has started again by itself, and it completes
        tapDescription("Back")
        gone("This model needs a Hugging Face token.")
        seeDescription("Arriving")
        world.downloads.finish(swift)
        goneTag("download_ring")
        world.engine.reply { content("Hello from Qwen3.5 0.8B") }
        send("hi")
        see("Hello from Qwen3.5 0.8B")
        shot("3-answered")
    }
}
