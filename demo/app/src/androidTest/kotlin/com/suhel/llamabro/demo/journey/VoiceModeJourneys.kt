package com.suhel.llamabro.demo.journey

import android.Manifest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import com.suhel.llamabro.demo.models.Catalog
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Voice mode (D100), on the fake engine with a scripted microphone and a silent speaker: the voice-mode button appears
 * once the model has said it talks, holding the talk button sends a turn, the reply is captioned, and back in the chat
 * the turn is a captioned voice message and the reply plays again.
 */
@OptIn(ExperimentalTestApi::class)
class VoiceModeJourneys : Journey() {
    private val omni = Catalog.suggested.first { it.talks }.key

    /** As for voice messages: the microphone is granted first, or the journey is skipped and says why (F150). */
    @Before fun allowMicrophone() {
        assumeTrue(
            "this phone refuses to grant the microphone to the test (F150): no voice to drive",
            Robot().grant(Manifest.permission.RECORD_AUDIO),
        )
    }

    @Test fun aSpokenTurnIsAnsweredAloudAndKeptInTheChat() {
        world.engine.supportsAudio = true
        world.engine.voices = listOf("female", "male")
        onPhone(omni)
        world.engine.reply { content("Hello") }
        world.engine.transcript("Tell me a short story.")
        world.engine.voiceReply {
            text("The keeper finds a bottle. ")
            audio(ShortArray(4_800))
            text("Inside is a map.")
        }
        see("What's on your mind?")
        send("hi")
        see("Hello")
        // the model said it talks on its load: with the bar empty, the voice-mode button takes Send's place
        tapDescription("Talk with the model")
        seeTag("voice_mode")
        see("Tap or hold to talk")
        see("Voice: female")
        shot("1-voice-ready")
        holdTalk()
        see("Inside is a map.", substring = true)
        see("Tell me a short story.")
        shot("2-voice-captioned")
        tapDescription("Close voice mode")
        gone("Tap or hold to talk")
        // the turn is a voice message with its caption, and the reply plays again
        seeDescription("Voice message")
        see("Tell me a short story.")
        seeDescription("Play the reply")
        shot("3-voice-in-the-chat")
    }

    @Test fun aTapStartsATurnAndTheNextTapSendsIt() {
        world.engine.supportsAudio = true
        world.engine.voices = listOf("female")
        onPhone(omni)
        world.engine.reply { content("Hello") }
        world.engine.transcript("What time is it?")
        world.engine.voiceReply { text("It is late.") }
        see("What's on your mind?")
        send("hi")
        see("Hello")
        tapDescription("Talk with the model")
        seeTag("voice_mode")
        // a quick tap starts listening, and listening goes on after the finger is up
        tapDescription("Start talking")
        see("Tap to send")
        Thread.sleep(HOLD_MILLIS)
        see("Tap to send")
        tapDescription("Send what you said")
        see("It is late.", substring = true)
        see("What time is it?")
    }

    /** Presses the talk button, waits until it listens, and lets go: the scripted voice is about a second long. */
    private fun holdTalk() {
        seeDescription("Start talking")
        val talk = compose.onNodeWithContentDescription("Start talking")
        talk.performTouchInput { down(center) }
        compose.waitUntilAtLeastOneExists(hasContentDescription("Send what you said"), WAIT)
        // the scripted microphone gives its second of voice at once; give it time to arrive
        Thread.sleep(HOLD_MILLIS)
        // the press's own events say it was a hold, not a tap: the turn is sent on release
        compose.onNodeWithContentDescription("Send what you said").performTouchInput {
            advanceEventTime(HOLD_MILLIS)
            up()
        }
    }

    private companion object {
        const val HOLD_MILLIS = 600L
    }
}
