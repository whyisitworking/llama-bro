package com.suhel.llamabro.demo.journey

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.suhel.llamabro.demo.models.Catalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** A reply cut at its length limit and continued, and a chat's own sampling values (D60). */
@OptIn(ExperimentalTestApi::class)
class SamplingJourneys : Journey() {
    private val swift = Catalog.picks[0].key
    private val robot = Robot()

    @Test fun aReplyCutAtItsLimitIsContinued() {
        onPhone(swift)
        world.engine.reply {
            content("The boats came in")
            finishLength()
        }
        world.engine.reply { content(" at dusk.") }
        see("What's on your mind?")
        send("Tell me about the harbour")
        see("Stopped at the length limit.")
        shot("1-cut")
        tap("Continue")
        see("The boats came in at dusk.")
        gone("Stopped at the length limit.")
        shot("2-continued")
    }

    @Test fun aChatsOwnValueIsSentAndGivenBack() {
        onPhone(swift)
        world.engine.reply { content("First answer") }
        world.engine.reply { content("Second answer") }
        see("What's on your mind?")
        send("hi")
        see("First answer")

        compose.onNodeWithTag("model_title").performClick()
        see("THIS CHAT")
        tap("Inference")
        see("INFERENCE FOR THIS CHAT")
        compose.waitForIdle()
        Thread.sleep(SHEET_SETTLE)
        compose.onNodeWithContentDescription("Temperature").performSemanticsAction(SemanticsActions.SetProgress) {
            it(0.3f)
        }
        see("Changed for this chat")
        shot("1-chat-value")
        robot.pressBack()

        send("again")
        see("Second answer")
        val body = Json.parseToJsonElement(world.engine.chatRequests.last()).jsonObject
        assertEquals(0.3f, body.getValue("temperature").jsonPrimitive.float, 1e-6f)

        compose.onNodeWithTag("model_title").performClick()
        see("1 value changed for this chat")
        tap("Inference")
        tap("Use the model's values")
        gone("Changed for this chat")
    }

    @Test fun aShortReplyLengthIsSent() {
        onPhone(swift)
        world.engine.reply { content("First answer") }
        world.engine.reply { content("Second answer") }
        see("What's on your mind?")
        send("hi")
        see("First answer")

        tapDescription("Chats")
        tapDescription("Settings")
        tap("Inference")
        seeTag("reply_length")
        tap("Short")
        shot("1-reply-length")
        robot.pressBack()
        robot.pressBack()

        send("again")
        see("Second answer")
        val body = Json.parseToJsonElement(world.engine.chatRequests.last()).jsonObject
        assertEquals(1_024, body.getValue("max_tokens").jsonPrimitive.int)
    }

    private companion object {
        const val SHEET_SETTLE = 700L
    }
}
