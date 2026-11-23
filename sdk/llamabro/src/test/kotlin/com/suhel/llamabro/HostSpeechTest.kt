package com.suhel.llamabro

import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.voice.SpeechAudio
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Speech (D139) through Kotlin, JNI and the bridge's `/v1/audio/speech` on upstream's listening-and-talking test model
 * (24 kHz, voices "female" and "male"). The model is damaged on purpose: these check the audio's shape, not its sound.
 * Run by sdk/scripts/check.sh's e2e step.
 */
class HostSpeechTest {
    private lateinit var model: Model
    private lateinit var clipPath: String

    @Before
    fun load() =
        runBlocking {
            val lib = System.getProperty("llamabro.host.lib")
            val models = System.getProperty("llamabro.host.models")
            val clip = System.getProperty("llamabro.host.clip")
            assumeTrue(
                "host end-to-end runs from sdk/scripts/check.sh",
                lib != null && models != null && clip != null,
            )
            clipPath = clip!!
            val gguf = File(models!!, "tinyLFM3-Omni-for-testing-Q2_K.gguf").path
            val mmproj = File(models, "mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf").path
            model =
                LlamaBro(LlamaBroRuntime(JniEngine, tempDir(), lib, LlamaBroConfig.Default))
                    .loadModel(ModelSource.file(gguf) { this.mmproj = mmproj }) {
                        contextSize = ContextSize.Fixed(HostTestSupport.CONTEXT)
                    }
        }

    @After
    fun close() =
        runBlocking {
            if (::model.isInitialized) model.closeAndJoin()
        }

    private suspend fun speech(text: String, configure: com.suhel.llamabro.voice.SpeechConfig.Builder.() -> Unit) =
        withTimeout(HostTestSupport.TIMEOUT_MS) { model.speak(text, configure).toList() }

    private fun List<SpeechAudio>.samples(): ShortArray = flatMap { it.pcm.toList() }.toShortArray()

    @Test
    fun speechStreamsAsPcmAtTheModelsRate() =
        runBlocking {
            val pieces =
                speech("Hello there.") {
                    voice = "female"
                    seed = SEED
                    maxFrames = FRAMES
                }
            assertTrue(pieces.isNotEmpty())
            assertTrue(pieces.all { it.sampleRate == 24000 && it.channels == 1 })
            assertTrue(pieces.samples().isNotEmpty())
        }

    @Test
    fun aSeedRepeatsTheSpeech() =
        runBlocking {
            val config: com.suhel.llamabro.voice.SpeechConfig.Builder.() -> Unit = {
                voice = "male"
                seed = SEED
                maxFrames = FRAMES
            }
            assertArrayEquals(speech("Good morning.", config).samples(), speech("Good morning.", config).samples())
        }

    @Test
    fun aSpeakerReferenceIsTaken() =
        runBlocking {
            val pieces =
                speech("Hello there.") {
                    speakerReference = MediaSource.file(clipPath)
                    seed = SEED
                    maxFrames = FRAMES
                }
            assertTrue(pieces.samples().isNotEmpty())
        }

    @Test
    fun aSettingTheModelDoesNotTakeIsUpstreamsRefusal() =
        runBlocking {
            val e =
                assertThrows(LlamaBroException.InvalidRequest::class.java) {
                    runBlocking { speech("Hello.") { speed = 1.3f } }
                }
            assertTrue(e.message!!, e.message!!.contains("speed"))
        }

    @Test
    fun aVoiceTheModelDoesNotHaveIsRefusedAtOnce() {
        assertThrows(IllegalArgumentException::class.java) { model.speak("Hi") { voice = "robot" } }
        assertThrows(IllegalArgumentException::class.java) {
            model.speak("Hi") {
                voice = "female"
                speakerReference = MediaSource.file(clipPath)
            }
        }
    }

    @Test
    fun cancellingTheCollectorStopsTheSpeech() =
        runBlocking {
            // the first piece, then the flow is cancelled: the model is free for the next request
            withTimeout(
                HostTestSupport.TIMEOUT_MS,
            ) { model.speak("A longer sentence to speak.") { seed = SEED }.first() }
            assertEquals(1, speech("Again.") { maxFrames = FRAMES }.map { it.channels }.distinct().size)
        }

    private companion object {
        const val SEED = 7L
        const val FRAMES = 40
    }
}
