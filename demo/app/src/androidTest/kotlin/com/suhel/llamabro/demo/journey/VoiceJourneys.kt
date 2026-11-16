package com.suhel.llamabro.demo.journey

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.demo.models.Catalog
import org.junit.Before
import org.junit.Test

/**
 * Voice messages (D76), on the fake engine with a scripted microphone: the mic appears once the model has said it
 * hears, a recording is sent as the message, and the model answers it.
 */
class VoiceJourneys : Journey() {
    private val swift = Catalog.picks[0].key

    /** A test can't answer the system's permission dialog: the microphone is granted first. */
    @Before fun allowMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    @Test fun aVoiceMessageIsRecordedSentAndAnswered() {
        world.engine.supportsAudio = true
        onPhone(swift)
        world.engine.reply { content("Hello, I'm listening") }
        world.engine.reply { content("You sound cheerful") }
        see("What's on your mind?")
        send("hi")
        see("Hello, I'm listening")
        // the model said it hears on its load: with the bar empty, the mic takes Send's place
        tapDescription("Record a voice message")
        // the scripted voice ends after a second, as a call would end it: the recording waits for ✕ or ↑
        seeDescription("Recording stopped")
        shot("1-recording-waits")
        tapDescription("Send the voice message")
        seeDescription("Voice message, 0:01")
        see("You sound cheerful")
        shot("2-voice-message-answered")
        tapDescription("Play")
        seeDescription("Pause")
    }

    @Test fun aDiscardedRecordingLeavesNothingBehind() {
        world.engine.supportsAudio = true
        onPhone(swift)
        world.engine.reply { content("Hello") }
        see("What's on your mind?")
        send("hi")
        see("Hello")
        tapDescription("Record a voice message")
        seeDescription("Recording stopped")
        tapDescription("Discard the recording")
        seeDescription("Record a voice message")
    }
}
