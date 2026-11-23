package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Dictation (D140) over FakeLlamaBro, on upstream's three routes: live text, a piece at each pause in a realtime
 * session, and pieces over `transcribe` for a model without one; then the last pass over the whole recording.
 */
class DictationTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun end() = scope.cancel()

    /** Speech and silence in 50 ms blocks, paced a little as a microphone is; then it ends. */
    private class Mic(vararg val parts: Pair<Short, Int>) {
        var closed = false

        fun source() =
            object : SampleSource {
                private val blocks = parts.flatMap { (amplitude, n) -> List(n) { amplitude } }.iterator()
                override val rate = 16_000

                override fun read(buffer: ShortArray): Int {
                    if (!blocks.hasNext()) return -1
                    Thread.sleep(PACE_MILLIS)
                    val a = blocks.next()
                    // a tone, so the level is the amplitude's; silence is zeros
                    for (i in buffer.indices) buffer[i] = if (i % 2 == 0) a else (-a).toShort()
                    return buffer.size
                }

                override fun close() {
                    closed = true
                }
            }
    }

    private val quiet: Short = 0
    private val loud: Short = 8_000

    /** Two phrases with a pause after each. */
    private fun twoPhrases() = Mic(quiet to 10, loud to 20, quiet to 12, loud to 20, quiet to 12)

    private fun model(script: FakeLlamaBro.() -> Unit): Model =
        runBlocking {
            FakeLlamaBro(script)
                .apply { supportsAudio = true }
                .llamaBro
                .loadModel(ModelSource.file("/models/omni.gguf"))
        }

    private fun clip(): File = File(folder.root, "dictation.wav")

    /** Waits until [millis] of the scripted recording went in, as a person finishes speaking. */
    private suspend fun Dictation.heard(millis: Long) = withTimeout(TIMEOUT) { state.first { it.millis >= millis } }

    @Test
    fun aLiveSessionGivesItsTextAndNoSecondPass() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcribesLive = true
                    transcription("Remind me to call the bank.")
                }
            val mic = Mic(quiet to 10, loud to 20, quiet to 4)
            val dictation = Dictation(model, mic::source, clip(), scope)
            dictation.start()
            withTimeout(TIMEOUT) { dictation.state.first { it.route != null } }
            assertEquals(DictationRoute.Live, dictation.state.value.route)
            val text = withTimeout(TIMEOUT) { dictation.finish() }
            assertEquals("Remind me to call the bank.", text)
            assertFalse(clip().exists())
            assertTrue(mic.closed)
        }

    @Test
    fun aSessionPerPauseIsTranscribedOnceMoreAsAWhole() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcribesLive = false
                    transcription("Remind me.")
                    transcription("To call the bank.")
                    transcript("Remind me to call the bank.")
                }
            val dictation = Dictation(model, twoPhrases()::source, clip(), scope)
            dictation.start()
            // the first piece's text shows while the person goes on
            withTimeout(TIMEOUT) { dictation.state.first { it.text.startsWith("Remind me.") } }
            assertEquals(DictationRoute.PerPause, dictation.state.value.route)
            dictation.heard(74 * 50)
            val text = withTimeout(TIMEOUT) { dictation.finish() }
            assertEquals("Remind me to call the bank.", text)
            assertEquals("Remind me to call the bank.", dictation.state.value.text)
        }

    @Test
    fun aModelWithoutASessionIsTranscribedInPiecesThenAsAWhole() =
        runBlocking {
            val model =
                model {
                    streamsAudio = false
                    transcript("language English<asr_text>Remind me.")
                    transcript("language English<asr_text>Remind me to call the bank.")
                }
            val dictation =
                Dictation(model, Mic(quiet to 10, loud to 20, quiet to 12, quiet to 30)::source, clip(), scope)
            dictation.start()
            // Qwen3-ASR's format is taken off, as upstream's client does
            withTimeout(TIMEOUT) { dictation.state.first { it.text == "Remind me." } }
            assertEquals(DictationRoute.Pieces, dictation.state.value.route)
            dictation.heard(72 * 50)
            assertEquals("Remind me to call the bank.", withTimeout(TIMEOUT) { dictation.finish() })
        }

    @Test
    fun aFailedLastPassKeepsThePieces() =
        runBlocking {
            val model =
                model {
                    streamsAudio = false
                    transcript("Remind me.")
                    inputTooLong(1) // the last pass fails
                }
            val dictation =
                Dictation(model, Mic(quiet to 10, loud to 20, quiet to 12, quiet to 30)::source, clip(), scope)
            dictation.start()
            withTimeout(TIMEOUT) { dictation.state.first { it.text == "Remind me." } }
            dictation.heard(72 * 50)
            assertEquals("Remind me.", withTimeout(TIMEOUT) { dictation.finish() })
        }

    @Test
    fun theRecordingTimeAndLevelsAreShown() =
        runBlocking {
            val model =
                model {
                    streamsAudio = true
                    transcription("")
                }
            val dictation = Dictation(model, Mic(quiet to 4, loud to 6)::source, clip(), scope)
            dictation.start()
            val state = withTimeout(TIMEOUT) { dictation.state.first { it.millis >= 500 } }
            assertEquals(10, state.levels.size)
            assertTrue(state.levels.last() > state.levels.first())
            withTimeout(TIMEOUT) { dictation.finish() }
            Unit
        }

    @Test
    fun aMicrophoneThatWontOpenIsAFailureAndNoText() =
        runBlocking {
            val model = model { streamsAudio = true }
            var failed: RecordingFailure? = null
            val dictation = Dictation(model, { error("no microphone") }, clip(), scope)
            dictation.start { failed = it }
            assertNull(withTimeout(TIMEOUT) { dictation.finish() })
            assertEquals(RecordingFailure.Microphone, failed)
        }

    @Test
    fun cancellingThrowsTheRecordingAway() =
        runBlocking {
            val model = model { streamsAudio = true }
            val dictation = Dictation(model, Mic(quiet to 200)::source, clip(), scope)
            dictation.start()
            withTimeout(TIMEOUT) { dictation.state.first { it.millis > 0 } }
            dictation.cancel()
            withTimeout(TIMEOUT) {
                while (clip().exists()) kotlinx.coroutines.delay(20)
            }
        }

    private companion object {
        const val TIMEOUT = 10_000L
        const val PACE_MILLIS = 3L
    }
}
