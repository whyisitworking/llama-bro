package com.suhel.llamabro

import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.voice.TranscriptionEvent
import com.suhel.llamabro.voice.TranscriptionSession
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The transcription session (D138) through Kotlin, JNI and the bridge's in-memory `/v1/realtime` on upstream's
 * listening-and-talking test model, which upstream transcribes live: LFM3's audio encoder streams and its template has
 * an ASR prompt (server-context.cpp ws_realtime, `live_asr`). The model is damaged on purpose: these check the
 * session's shape, not its words. Run by sdk/scripts/check.sh's e2e step.
 */
class HostTranscriptionTest {
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
            clip = HostVoiceTest.pcmOf(File(clipPath!!).readBytes())
            val gguf = File(models!!, "tinyLFM3-Omni-for-testing-Q2_K.gguf").path
            val mmproj = File(models, "mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf").path
            model =
                LlamaBro(LlamaBroRuntime(JniEngine, tempDir(), lib, LlamaBroConfig.Default))
                    .loadModel(ModelSource.file(gguf) { this.mmproj = mmproj }) {
                        contextSize = ContextSize.Fixed(HostVoiceTest.CONTEXT)
                        seed = HostVoiceTest.SEED
                    }
        }

    @After
    fun close() =
        runBlocking {
            if (::model.isInitialized) model.closeAndJoin()
        }

    private suspend fun open(): TranscriptionSession = model.transcriptionSession { language = "en" }

    /** [from] to [to] of the clip in 100 ms appends, as a microphone gives it. */
    private fun TranscriptionSession.speak(from: Int = 0, to: Int = clip.size) {
        val chunk = inputSampleRate / 10
        for (at in from until to step chunk) assertTrue(append(clip, at, minOf(chunk, to - at)))
    }

    /** The events up to and with the [n]th [TranscriptionEvent.Done]. */
    private suspend fun TranscriptionSession.untilDone(n: Int = 1): List<TranscriptionEvent> =
        withTimeout(HostVoiceTest.TIMEOUT_MS) {
            val events = mutableListOf<TranscriptionEvent>()
            var done = 0
            this@untilDone
                .events
                .takeWhile {
                    events += it
                    if (it is TranscriptionEvent.Done) done++
                    done < n
                }.toList()
            events
        }

    @Test
    fun lfm3TranscribesLive() =
        runBlocking {
            open().use { session ->
                assertTrue(session.live)
                assertEquals(16000, session.inputSampleRate)
                session.speak()
                assertTrue(session.hasUncommittedAudio)
                assertTrue(session.finish())
                assertFalse(session.hasUncommittedAudio)
                val events = session.untilDone()
                // the final commit ends the input: upstream's live path sends no committed for it, only the last done
                assertTrue(events.none { it is TranscriptionEvent.Committed })
                val done = events.last() as TranscriptionEvent.Done
                // the deltas add up to the item's transcript, as upstream builds it (item_text)
                val text = events.filterIsInstance<TranscriptionEvent.Text>().filter { it.itemId == done.itemId }
                assertEquals(done.transcript, text.joinToString("") { it.delta })
            }
        }

    @Test
    fun eachCommitIsAnItemOfItsOwn() =
        runBlocking {
            open().use { session ->
                session.speak(to = clip.size / 2)
                assertTrue(session.commit())
                session.speak(from = clip.size / 2)
                assertTrue(session.finish())
                val done = session.untilDone(2).filterIsInstance<TranscriptionEvent.Done>()
                assertEquals(2, done.size)
                assertNotEquals(done[0].itemId, done[1].itemId)
            }
        }

    @Test
    fun theSetupReachesTheServer() =
        runBlocking {
            // an input rate other than the model's is taken: upstream resamples it
            model
                .transcriptionSession {
                    inputSampleRate = 48000
                    prompt = "Llama Bro"
                }.use { session ->
                    assertEquals(48000, session.inputSampleRate)
                }
        }

    @Test
    fun closingTheModelEndsAnOpenSession() =
        runBlocking {
            val session = open()
            model.closeAndJoin()
            assertThrows(LlamaBroException.ModelClosed::class.java) {
                runBlocking { withTimeout(HostVoiceTest.TIMEOUT_MS) { session.events.toList() } }
            }
            assertFalse(session.commit())
        }
}
