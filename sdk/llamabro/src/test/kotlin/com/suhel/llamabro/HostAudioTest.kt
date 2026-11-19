package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.ReasoningFormat
import com.suhel.llamabro.chat.RequestOptions
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.internal.ChatJson
import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream

/**
 * Speech input through the public API on the real core (D65, D84): `Content.Audio` in chat and `transcribe`, from a
 * file, a descriptor and bytes, on upstream's audio test model (`fetch-test-models.sh`) and a clip spoken at check
 * time. The model is damaged on purpose and writes gibberish, so these check that calls complete and that the three
 * sources agree, not the words. Run by sdk/scripts/check.sh's e2e step.
 */
class HostAudioTest {
    private lateinit var model: Model
    private lateinit var clip: File

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
            clip = File(clipPath!!)
            val gguf = File(models!!, "tinyLFM3-Omni-for-testing-Q2_K.gguf").path
            val mmproj = File(models, "mmproj-tinyLFM3-Omni-for-testing-Q2_K.gguf").path
            model =
                LlamaBro(LlamaBroRuntime(JniEngine, tempDir(), lib, LlamaBroConfig.Default))
                    .loadModel(ModelSource.file(gguf) { this.mmproj = mmproj }) {
                        contextSize = ContextSize.Fixed(CONTEXT)
                    }
            assertTrue(model.info.supportsAudio)
        }

    @After
    fun close() =
        runBlocking {
            if (::model.isInitialized) model.closeAndJoin()
        }

    /** The descriptor number behind a JVM stream (`--add-opens java.base/java.io`, set for the host tests). */
    private fun fdOf(fd: FileDescriptor): Int =
        FileDescriptor::class.java
            .getDeclaredField("fd")
            .apply { isAccessible = true }
            .getInt(fd)

    /** Greedy, short and uncached, so the same audio gives the same reply whatever the source. */
    private fun Conversation.Builder.deterministic() {
        defaults {
            sampling {
                temperature = 0f
                seed = 1
                maxTokens = REPLY_TOKENS
            }
            reasoning { format = ReasoningFormat.None }
            extra = """{"cache_prompt":false}"""
        }
    }

    private val transcription: RequestOptions.Builder.() -> Unit = {
        sampling {
            temperature = 0f
            seed = 1
            maxTokens = REPLY_TOKENS
        }
        // the test model's "transcript" is reasoning; kept in the text, which the default would empty
        reasoning { format = ReasoningFormat.None }
    }

    private suspend fun Conversation.turn(text: String, vararg media: Content): ChatEvent.Done =
        send(text, *media).toList().filterIsInstance<ChatEvent.Done>().single()

    private fun lastReply(chat: Conversation): String? = (chat.messages.value.last() as Message.Assistant).content

    @Test
    fun chatWithAudioFromEachSourceAgrees() =
        runBlocking {
            val text = model.conversation { deterministic() }
            val textOnly = text.turn("What is said here?")

            val replies =
                listOf(
                    MediaSource.file(clip.path),
                    MediaSource.bytes(clip.readBytes()),
                ).map { source ->
                    val chat = model.conversation { deterministic() }
                    val done = chat.turn("What is said here?", Content.Audio(source))
                    assertTrue(
                        "the clip is in the prompt: ${done.usage?.promptTokens} vs ${textOnly.usage?.promptTokens}",
                        done.usage!!.promptTokens > textOnly.usage!!.promptTokens + AUDIO_TOKENS_AT_LEAST,
                    )
                    lastReply(chat)
                }
            val byDescriptor =
                FileInputStream(clip).use { stream ->
                    val chat = model.conversation { deterministic() }
                    chat.turn("What is said here?", Content.Audio(MediaSource.Descriptor { fdOf(stream.fd) }))
                    lastReply(chat)
                }
            assertEquals(replies[0], replies[1])
            assertEquals(replies[0], byDescriptor)
        }

    @Test
    fun voiceNoteFromADescriptorIsSentInTurnThreeAndAfterRestore() =
        runBlocking {
            val chat = model.conversation { deterministic() }
            val first =
                FileInputStream(clip).use { stream ->
                    chat.turn("What is said here?", Content.Audio(MediaSource.Descriptor { fdOf(stream.fd) }))
                }
            // the descriptor is closed now; the chat holds the bytes
            chat.turn("And again?")
            val third = chat.turn("Once more?")
            assertTrue(
                "turn 3 still carries the clip: ${third.usage?.promptTokens} vs ${first.usage?.promptTokens}",
                third.usage!!.promptTokens > first.usage!!.promptTokens,
            )
            val restored = model.restoreConversation(chat.toJson())
            val again = restored.turn("Last one?")
            assertTrue(again.usage!!.promptTokens > third.usage!!.promptTokens)
            val audio = (restored.messages.value.first() as Message.User).content[1] as Content.Audio
            assertTrue(audio.source is MediaSource.Bytes)
        }

    @Test
    fun audioFromAFileSurvivesToJsonAndRestore() =
        runBlocking {
            val chat = model.conversation { deterministic() }
            chat.turn("What is said here?", Content.Audio(MediaSource.file(clip.path)))
            val restored = model.restoreConversation(chat.toJson())
            val audio = (restored.messages.value.first() as Message.User).content[1] as Content.Audio
            assertEquals(MediaSource.file(clip.path), audio.source)
            restored.turn("And again?")
            assertEquals(chat.messages.value.size + 2, restored.messages.value.size)
        }

    @Test
    fun transcribeFromEachSourceAgrees() =
        runBlocking {
            val byPath = model.transcribe(MediaSource.file(clip.path), options = transcription)
            val byBytes = model.transcribe(MediaSource.bytes(clip.readBytes()), options = transcription)
            val byDescriptor =
                FileInputStream(clip).use { stream ->
                    model.transcribe(MediaSource.Descriptor { fdOf(stream.fd) }, options = transcription)
                }
            val text =
                when (byPath) {
                    is Typed.Value -> byPath.value
                    is Typed.Incomplete -> byPath.text
                    is Typed.Invalid -> error("a transcript is never Invalid: $byPath")
                }
            assertTrue("a transcript: $byPath", text.isNotEmpty())
            assertEquals(byPath, byBytes)
            assertEquals(byPath, byDescriptor)
            val withLanguage = model.transcribe(MediaSource.file(clip.path), language = "en", options = transcription)
            assertFalse(withLanguage is Typed.Invalid)
        }

    @Test
    fun cancellingATranscriptionOrAChatLeavesTheModelUsable() =
        runBlocking {
            val long: RequestOptions.Builder.() -> Unit = {
                transcription()
                sampling { maxTokens = LONG_TOKENS }
                extra = """{"ignore_eos":true}"""
            }
            val transcribing = async { model.transcribe(MediaSource.file(clip.path), options = long) }
            delay(CANCEL_AFTER_MS)
            transcribing.cancelAndJoin()
            assertTrue(transcribing.isCancelled)

            val chat = model.conversation { deterministic() }
            val sending: Job =
                async {
                    chat
                        .send("What is said here?", Content.Audio(MediaSource.bytes(clip.readBytes()))) {
                            sampling { maxTokens = LONG_TOKENS }
                            extra = """{"cache_prompt":false,"ignore_eos":true}"""
                        }.toList()
                }
            delay(CANCEL_AFTER_MS)
            sending.cancelAndJoin()
            assertTrue(sending.isCancelled)

            val after = model.transcribe(MediaSource.file(clip.path), options = transcription)
            assertFalse("$after", after is Typed.Invalid)
        }

    @Test
    fun badClipsAreInvalidRequestsNamingTheProblem() =
        runBlocking {
            suspend fun failure(block: suspend () -> Unit): Throwable {
                val e = runCatching { block() }.exceptionOrNull()
                assertTrue("$e", e is LlamaBroException.InvalidRequest)
                assertFalse(e is CancellationException)
                return e!!
            }
            val missing = File(clip.parentFile, "no-such-clip.wav").path
            val m1 = failure { model.transcribe(MediaSource.file(missing)) }
            assertTrue(m1.message!!, m1.message!!.contains(missing))
            val m2 =
                failure {
                    model.conversation().send("hi", Content.Audio(MediaSource.file(missing))).toList()
                }
            assertTrue(m2.message!!, m2.message!!.contains(missing))

            val m4a =
                File.createTempFile("clip", ".m4a").apply {
                    deleteOnExit()
                    writeBytes(MediaTest.M4A + ByteArray(256))
                }
            val m3 = failure { model.transcribe(MediaSource.file(m4a.path)) }
            assertTrue(m3.message!!, m3.message!!.contains("WAV, MP3 or FLAC") && m3.message!!.contains("record WAV"))

            val m5 = failure { model.transcribe(MediaSource.Descriptor { UNOPENED_FD }) }
            assertTrue(m5.message!!, m5.message!!.contains("descriptor $UNOPENED_FD"))
            val m6 =
                failure {
                    model.conversation().send("hi", Content.Audio(MediaSource.Descriptor { UNOPENED_FD })).toList()
                }
            assertTrue(m6.message!!, m6.message!!.contains("descriptor $UNOPENED_FD"))
        }

    /** D84: only a media part's own `input_audio.data` is a placeholder; the user's words are never replaced. */
    @Test
    fun userTextWithThePlaceholderIsNotReplaced() =
        runBlocking {
            val request =
                ChatRequest {
                    messages(
                        Message.user(
                            Content.Text("lb-attachment:0 and lb-attachment:1"),
                            Content.Audio(MediaSource.bytes(clip.readBytes())),
                        ),
                        Message.Assistant("I heard lb-attachment:0."),
                        Message.user("lb-attachment:0"),
                    )
                }
            val encoded = ChatJson.encode(request)
            // apply-template answers once, not as a stream
            val body = JsonObject(Json.parseToJsonElement(encoded.body).jsonObject - setOf("stream", "stream_options"))
            val response = model.request(Endpoint.ApplyTemplate, body.toString(), encoded.attachments).single()
            val prompt =
                Json
                    .parseToJsonElement(response)
                    .jsonObject
                    .getValue("prompt")
                    .jsonPrimitive.content
            assertTrue(prompt, prompt.contains("lb-attachment:0 and lb-attachment:1"))
            assertTrue(prompt, prompt.contains("I heard lb-attachment:0."))
            assertEquals(prompt, 3, Regex("lb-attachment:0").findAll(prompt).count())
            assertFalse(prompt, prompt.contains("base64"))

            // and a chat with it completes, the words kept as they are
            val chat = model.conversation { deterministic() }
            chat.turn("lb-attachment:0", Content.Audio(MediaSource.file(clip.path)))
            assertEquals(
                Content.Text("lb-attachment:0"),
                (chat.messages.value.first() as Message.User).content[0],
            )
        }

    private companion object {
        const val CONTEXT = 4096
        const val REPLY_TOKENS = 8
        const val LONG_TOKENS = 2000
        const val CANCEL_AFTER_MS = 300L
        const val AUDIO_TOKENS_AT_LEAST = 30
        const val UNOPENED_FD = 4000
    }
}
