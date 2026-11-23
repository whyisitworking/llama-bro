package com.suhel.llamabro

import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.voice.VoiceEvent
import com.suhel.llamabro.voice.VoiceSession
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The voice session (D95) through Kotlin, JNI and the bridge's in-memory `/v1/realtime` (D97) on upstream's
 * listening-and-talking test model, with the clip spoken at check time. The model is damaged on purpose, so these
 * check the conversation's shape, not its words. Run by sdk/scripts/check.sh's e2e step.
 */
class HostVoiceTest {
    private lateinit var model: Model
    private lateinit var clip: ShortArray

    @Before
    fun load() =
        runBlocking {
            val lib = System.getProperty("llamabro.host.lib")
            val models = System.getProperty("llamabro.host.models")
            val clipPath = System.getProperty("llamabro.host.clip")
            assumeTrue(
                "host end-to-end runs from sdk/scripts/check.sh",
                lib != null && models != null && clipPath != null,
            )
            clip = pcmOf(File(clipPath!!).readBytes())
            val gguf = File(models!!, "tinyLFM3-Omni-for-testing-Q2_K.gguf").path
            val mmproj = File(models, "mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf").path
            model =
                LlamaBro(LlamaBroRuntime(JniEngine, tempDir(), lib, LlamaBroConfig.Default))
                    .loadModel(ModelSource.file(gguf) { this.mmproj = mmproj }) {
                        contextSize = ContextSize.Fixed(CONTEXT)
                        seed = SEED
                    }
        }

    @After
    fun close() =
        runBlocking {
            if (::model.isInitialized) model.closeAndJoin()
        }

    private suspend fun open(): VoiceSession = model.voiceSession { maxOutputTokens = REPLY_TOKENS }

    /** The events up to and with the next reply's end. */
    private suspend fun VoiceSession.untilDone(): List<VoiceEvent> =
        withTimeout(TIMEOUT_MS) {
            val events = mutableListOf<VoiceEvent>()
            this@untilDone
                .events
                .takeWhile {
                    events += it
                    it !is VoiceEvent.ReplyDone
                }.toList()
            events
        }

    /** The clip in 100 ms appends, as a microphone gives it. */
    private fun VoiceSession.speak() {
        val chunk = inputSampleRate / 10
        for (at in clip.indices step chunk) assertTrue(append(clip, at, minOf(chunk, clip.size - at)))
    }

    @Test
    fun aSpokenTurnIsAnsweredInTextAndSpeech() =
        runBlocking {
            val session = open()
            assertEquals(16000, session.inputSampleRate)
            assertTrue("the test model speaks", session.outputSampleRate != null)
            assertTrue("it lists its voices: ${model.info.voices}", model.info.supportsSpeech)
            session.speak()
            assertTrue(session.commit())
            val events = session.untilDone()
            assertTrue(events.first() is VoiceEvent.TurnCommitted)
            val done = events.last() as VoiceEvent.ReplyDone
            assertFalse(done.cancelled)
            val text = events.filterIsInstance<VoiceEvent.ReplyText>()
            val audio = events.filterIsInstance<VoiceEvent.ReplyAudio>()
            System.err.println("voice: ${text.size} text deltas, ${audio.sumOf { it.pcm.size }} samples, $done")
            assertTrue("text and speech", text.isNotEmpty() && audio.isNotEmpty())
            assertEquals(text.joinToString("") { it.delta }, done.text)
            assertTrue(events.none { it is VoiceEvent.Error })
            session.close()
            assertEquals(emptyList<VoiceEvent>(), withTimeout(TIMEOUT_MS) { session.events.toList() })
            assertFalse(session.respond())
        }

    @Test
    fun aRefusedEventIsAnErrorAndTheSessionGoesOn() =
        runBlocking {
            val session = open()
            assertTrue(session.commit())
            // nothing was said: the commit is refused, and the error is the first event
            val before = withTimeout(TIMEOUT_MS) { session.events.takeWhile { it !is VoiceEvent.Error }.toList() }
            assertEquals(emptyList<VoiceEvent>(), before)
            assertTrue(session.sendText("Hello"))
            val reply = session.untilDone()
            assertTrue(reply.last() is VoiceEvent.ReplyDone)
            session.close()
        }

    @Test
    fun aSettingTheServerRefusesFailsTheOpen() =
        runBlocking {
            val e =
                assertThrows(LlamaBroException.InvalidRequest::class.java) {
                    runBlocking { model.voiceSession { extra = """{"max_output_tokens":"lots"}""" } }
                }
            assertTrue(e.message!!, e.message!!.contains("max_output_tokens must be an integer"))
        }

    @Test
    fun closingTheModelEndsAnOpenSession() =
        runBlocking {
            val session = open()
            model.closeAndJoin()
            assertThrows(LlamaBroException.ModelClosed::class.java) {
                runBlocking { withTimeout(TIMEOUT_MS) { session.events.toList() } }
            }
            assertFalse(session.commit())
        }

    internal companion object {
        const val CONTEXT = 4096
        const val REPLY_TOKENS = 32
        const val SEED = 42L
        const val TIMEOUT_MS = 300_000L

        /** The 16-bit samples of a WAV's `data` chunk (the test clip is 16 kHz mono). */
        fun pcmOf(wav: ByteArray): ShortArray {
            val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            var at = 12
            while (at + 8 <= wav.size) {
                val id = String(wav, at, 4, Charsets.US_ASCII)
                val size = b.getInt(at + 4)
                if (id == "data") {
                    val samples = ShortArray(minOf(size, wav.size - at - 8) / 2)
                    b.position(at + 8)
                    b.asShortBuffer().get(samples)
                    return samples
                }
                at += 8 + size + (size and 1)
            }
            error("no data chunk in the clip")
        }
    }
}
