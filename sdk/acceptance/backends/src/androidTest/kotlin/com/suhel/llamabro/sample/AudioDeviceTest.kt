package com.suhel.llamabro.sample

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.conversation
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.transcribe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech input on the phone (D65, D84, for F152): LFM3-Omni-2B transcribes a clip and answers about it in a chat; it
 * logs the transcript, the reply, timings, and the clip's prompt tokens against its length (audio tokens per second).
 * A second case measures what earlier voice notes cost a later turn: a chat holding three, then a fourth, text-only
 * turn, against the same chat with no media, as time from send to the first token. Runs only when asked, attended,
 * with the model, its audio encoder and a 16 kHz WAV clip in the app's files:
 *   ANDROID_SERIAL=<serial> ./gradlew -p sdk :acceptance:backends:connectedFullDebugAndroidTest \
 *     -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.suhel.llamabro.sample.AudioDeviceTest \
 *     -Pandroid.testInstrumentationRunnerArguments.audio=LFM3-Omni-2B-Q4_K_M.gguf \
 *     -Pandroid.testInstrumentationRunnerArguments.audioMmproj=mmproj-LFM3-Omni-2B-Q8_0.gguf \
 *     -Pandroid.testInstrumentationRunnerArguments.clip=clip.wav
 * Results go to logcat under "Audio" and to filesDir/audio.log.
 */
@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var model: Model
    private lateinit var clip: File

    @Before fun load() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            val name = args.getString("audio")
            val mmproj = args.getString("audioMmproj")
            val clipName = args.getString("clip")
            assumeTrue(
                "a device check: pass the runner arguments audio=<model.gguf> audioMmproj=<encoder.gguf> clip=<file.wav>",
                name != null && mmproj != null && clipName != null,
            )
            val file = File(context.filesDir, name!!)
            val encoder = File(context.filesDir, mmproj!!)
            clip = File(context.filesDir, clipName!!)
            listOf(file, encoder, clip).forEach { assumeTrue("missing ${it.path}", it.exists()) }
            val started = System.nanoTime()
            model =
                LlamaBro.create(context).loadModel(ModelSource.file(file.path) { this.mmproj = encoder.path }) {
                    contextSize = ContextSize.Fixed(CONTEXT)
                }
            log("---- $name + $mmproj on ${model.loadReport.device}, loaded in ${ms(started)} ms")
            log("clip $clipName: %.2f s, %d bytes".format(clipSeconds(), clip.length()))
            assertTrue("the model hears", model.info.supportsAudio)
        }

    @After fun close() =
        runBlocking {
            if (::model.isInitialized) model.closeAndJoin()
        }

    private fun log(line: String) {
        Log.i("Audio", line)
        File(context.filesDir, "audio.log").appendText(line + "\n")
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    /** The clip's length from its WAV header: the `data` chunk's size over the byte rate. */
    private fun clipSeconds(): Double {
        val b = ByteBuffer.wrap(clip.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = b.getInt(BYTE_RATE_AT)
        var at = FIRST_CHUNK_AT
        while (at + CHUNK_HEADER <= b.limit()) {
            val id = String(ByteArray(4) { b.get(at + it) }, Charsets.US_ASCII)
            val size = b.getInt(at + 4)
            if (id == "data") return size.toDouble() / byteRate
            at += CHUNK_HEADER + size + (size and 1)
        }
        error("no data chunk in ${clip.name}")
    }

    private fun Conversation.Builder.greedy() {
        defaults {
            sampling {
                temperature = 0f
                maxTokens = REPLY_TOKENS
            }
            reasoning { enableThinking = false }
        }
    }

    /** One turn: its reply, the prompt's token count, and the time from send to its first token. */
    private class Turn(val reply: String, val promptTokens: Int, val firstTokenMs: Long, val totalMs: Long)

    private suspend fun Conversation.turn(text: String, vararg media: Content): Turn {
        val started = System.nanoTime()
        var first = -1L
        val reply = StringBuilder()
        var done: ChatEvent.Done? = null
        send(text, *media).collect { ev ->
            if (first < 0 && (ev is ChatEvent.ContentDelta || ev is ChatEvent.ReasoningDelta)) first = ms(started)
            if (ev is ChatEvent.ContentDelta) reply.append(ev.delta)
            if (ev is ChatEvent.Done) done = ev
        }
        val prompt = done?.usage?.promptTokens ?: done?.timings?.promptTokens ?: -1
        return Turn(reply.toString(), prompt, first, ms(started))
    }

    @Test fun transcribesAndAnswersAboutTheClip() =
        runBlocking {
            val started = System.nanoTime()
            val transcript =
                model.transcribe(MediaSource.file(clip.path)) {
                    temperature = 0f
                    maxTokens = REPLY_TOKENS
                }
            log("transcribe (file): ${ms(started)} ms: $transcript")
            assertTrue("a transcript: $transcript", transcript.text.isNotBlank())

            val fromDescriptor =
                ParcelFileDescriptor.open(clip, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val t = System.nanoTime()
                    model.transcribe(MediaSource.descriptor(pfd)) { temperature = 0f }.also {
                        log("transcribe (descriptor): ${ms(t)} ms: $it")
                    }
                }
            log("descriptor agrees with file: ${fromDescriptor == transcript}")

            val textOnly = model.conversation { greedy() }.turn(QUESTION)
            val withClip = model.conversation { greedy() }.turn(QUESTION, Content.Audio(MediaSource.file(clip.path)))
            val audioTokens = withClip.promptTokens - textOnly.promptTokens
            log(
                "chat with the clip: first token ${withClip.firstTokenMs} ms, whole ${withClip.totalMs} ms, " +
                    "prompt ${withClip.promptTokens} tokens (text alone ${textOnly.promptTokens}): reply ${withClip.reply}",
            )
            log(
                "audio tokens: $audioTokens for %.2f s = %.1f tokens/s".format(
                    clipSeconds(),
                    audioTokens / clipSeconds(),
                ),
            )
            assertTrue("a reply about the clip", withClip.reply.isNotBlank())
            assertTrue("the clip is in the prompt", audioTokens > 0)
        }

    @Test fun perTurnCostOfEarlierVoiceNotes() =
        runBlocking {
            val voice = model.conversation { greedy() }
            val bytes = clip.readBytes()
            voice.turn("Here is my first voice note.", Content.Audio(MediaSource.file(clip.path)))
            ParcelFileDescriptor.open(clip, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                voice.turn("Here is my second voice note.", Content.Audio(MediaSource.descriptor(pfd)))
            }
            voice.turn("Here is my third voice note.", Content.Audio(MediaSource.bytes(bytes)))
            val withMedia = voice.turn(FOLLOW_UP)

            val plain = model.conversation { greedy() }
            plain.turn("Here is my first note.")
            plain.turn("Here is my second note.")
            plain.turn("Here is my third note.")
            val withoutMedia = plain.turn(FOLLOW_UP)

            log(
                "4th text turn after 3 voice notes: first token ${withMedia.firstTokenMs} ms, prompt " +
                    "${withMedia.promptTokens} tokens; with no media: first token ${withoutMedia.firstTokenMs} ms, " +
                    "prompt ${withoutMedia.promptTokens} tokens",
            )
            assertTrue(withMedia.firstTokenMs >= 0 && withoutMedia.firstTokenMs >= 0)
        }

    private companion object {
        const val CONTEXT = 8192
        const val REPLY_TOKENS = 256
        const val QUESTION = "What is said in this recording?"
        const val FOLLOW_UP = "Thanks. In one sentence, what did I send you?"
        const val BYTE_RATE_AT = 28
        const val FIRST_CHUNK_AT = 12
        const val CHUNK_HEADER = 8
    }
}
