package com.suhel.llamabro

import com.suhel.llamabro.audio.TranscribeConfig
import com.suhel.llamabro.audio.Transcript
import com.suhel.llamabro.spi.EngineAttachment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D84: `Model.transcribe` over llama-server's `/v1/audio/transcriptions`, from a file, a descriptor or bytes. */
class TranscribeTest {
    private suspend fun setup(
        info: String = FakeModel.HEARING_INFO,
        task: ModelTask = ModelTask.Generate,
    ): Pair<FakeEngine, Model> {
        val engine = FakeEngine()
        engine.model.infoJson = info
        return engine to testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) { this.task = task }
    }

    /** `to_json_oaicompat_asr`. */
    private fun answer(engine: FakeEngine, text: String, outputTokens: Int) {
        engine.model.onRequest = { r ->
            r.chunk(
                """{"type":"transcript.text.done","text":${JsonPrimitive(text)},"usage":{"type":"tokens",""" +
                    """"input_tokens":60,"output_tokens":$outputTokens,"total_tokens":${60 + outputTokens},""" +
                    """"input_tokens_details":{"cached_tokens":0}}}""",
            )
            r.done()
        }
    }

    private fun body(r: FakeRequest): JsonObject = Json.parseToJsonElement(r.body) as JsonObject

    @Test
    fun transcribeReturnsTheText() =
        runTest {
            val (engine, model) = setup()
            answer(engine, "Hello there.", 4)
            assertEquals(
                Transcript("Hello there."),
                model.transcribe(MediaSource.file("/clips/note.wav")),
            )
            val r = engine.model.requests.single()
            assertEquals("transcriptions", r.endpoint)
            val a = r.attachments.single() as EngineAttachment.Path
            assertEquals("file", a.name)
            assertEquals("/clips/note.wav", a.path)
            assertNotEquals(Thread.currentThread(), engine.model.requestThreads.single())
        }

    @Test
    fun aClipTheDecoderRefusesIsInvalidRequest() =
        runTest {
            val (engine, model) = setup()
            engine.model.onRequest = { r ->
                r.chunk(
                    """{"error":{"code":500,"message":"Failed to load image or audio file","type":"server_error"}}""",
                    500,
                )
                r.done()
            }
            val e = runCatching { model.transcribe(MediaSource.file("/clips/note.wav")) }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.InvalidRequest)
            assertTrue(e!!.message!!, e.message!!.contains("WAV, MP3 or FLAC"))
        }

    @Test
    fun transcribeFromBytesAndDescriptor() =
        runTest {
            val (engine, model) = setup()
            answer(engine, "Hi.", 2)
            model.transcribe(MediaSource.bytes(MediaTest.WAV))
            engine.fdContents[MediaTest.FD] = MediaTest.WAV
            model.transcribe(MediaSource.Descriptor { MediaTest.FD })
            val (bytes, fd) = engine.model.requests
            assertArrayEquals(MediaTest.WAV, (bytes.attachments.single() as EngineAttachment.Bytes).bytes)
            // a transcription's descriptor is read natively, in the call
            assertEquals(MediaTest.FD, (fd.attachments.single() as EngineAttachment.Fd).fd)
            assertTrue(engine.fdReads.isEmpty())
        }

    @Test
    fun transcribeCutAtTheLimitIsTruncated() =
        runTest {
            val (engine, model) = setup()
            answer(engine, "Hello th", 4)
            assertEquals(
                Transcript("Hello th", truncated = true),
                model.transcribe(MediaSource.file("/clips/note.wav")) { maxTokens = 4 },
            )
        }

    @Test
    fun transcribeSendsLanguageAndPrompt() =
        runTest {
            val (engine, model) = setup()
            answer(engine, "Bonjour.", 3)
            model.transcribe(MediaSource.file("/clips/note.wav")) {
                language = "fr"
                prompt = "Names: Suhel."
                temperature = 0f
                maxTokens = 64
                seed = 3
            }
            val b = body(engine.model.requests.single())
            assertEquals(JsonPrimitive("fr"), b["language"])
            assertEquals(JsonPrimitive("Names: Suhel."), b["prompt"])
            assertEquals(JsonPrimitive("json"), b["response_format"])
            // form fields: upstream reads these three as strings (convert_transcriptions_to_chatcmpl)
            assertEquals(JsonPrimitive("false"), b["stream"])
            assertEquals(JsonPrimitive("64"), b["max_tokens"])
            assertEquals(JsonPrimitive("0.0"), b["temperature"])
            assertEquals(JsonPrimitive(3), b["seed"])
            // the request on the wire is what the options-based call sent (D143): same keys, same order
            assertEquals(
                """{"temperature":"0.0","seed":3,"max_tokens":"64","language":"fr","prompt":"Names: Suhel.",""" +
                    """"response_format":"json","stream":"false"}""",
                engine.model.requests
                    .single()
                    .body,
            )
        }

    @Test
    fun transcribeExtraReachesTheBodyAndCannotSetTheOwnedFields() =
        runTest {
            val (engine, model) = setup()
            answer(engine, "Hi.", 2)
            model.transcribe(MediaSource.file("/clips/note.wav")) { extra = """{"top_k":5}""" }
            assertEquals(JsonPrimitive(5), body(engine.model.requests.single())["top_k"])
            for (owned in listOf("stream", "response_format")) {
                val e = runCatching { TranscribeConfig { extra = """{"$owned":"x"}""" } }.exceptionOrNull()
                assertTrue("$owned: $e", e is IllegalArgumentException)
            }
            assertTrue(runCatching { TranscribeConfig { maxTokens = 0 } }.exceptionOrNull() is IllegalArgumentException)
        }

    @Test
    fun transcribeWithoutLanguageOrPromptLeavesThemOut() =
        runTest {
            val (engine, model) = setup()
            answer(engine, "Hi.", 2)
            model.transcribe(MediaSource.file("/clips/note.wav"))
            val b = body(engine.model.requests.single())
            assertEquals(null, b["language"])
            assertEquals(null, b["prompt"])
        }

    @Test
    fun transcribeOnAModelThatCantHearIsNotSupported() =
        runTest {
            val (engine, model) = setup(FakeModel.DEFAULT_INFO)
            val error = runCatching { model.transcribe(MediaSource.file("/clips/note.wav")) }.exceptionOrNull()
            assertTrue("$error", error is LlamaBroException.NotSupported)
            assertTrue(error!!.message!!, error.message!!.contains("supportsAudio"))
            assertTrue(engine.model.requests.isEmpty())
        }

    @Test
    fun transcribeRefusesOtherFormatsAndTasks() =
        runTest {
            val (engine, model) = setup()
            val aac = runCatching { model.transcribe(MediaSource.bytes(MediaTest.M4A)) }.exceptionOrNull()
            assertTrue("$aac", aac is LlamaBroException.InvalidRequest)
            assertTrue(aac!!.message!!, aac.message!!.contains("WAV, MP3 or FLAC"))
            assertTrue(engine.model.requests.isEmpty())
            val (_, embedder) = setup(task = ModelTask.Embedding())
            val task = runCatching { embedder.transcribe(MediaSource.file("/a.wav")) }.exceptionOrNull()
            assertTrue("$task", task is IllegalStateException)
            assertTrue(task!!.message!!, task.message!!.contains("ModelTask.Generate"))
        }
}
