package com.suhel.llamabro

import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.spi.EngineAttachment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** D65: typed embed and rerank over llama-server's embeddings and rerank routes. */
class EmbeddingsTest {
    private suspend fun model(engine: FakeEngine, task: ModelTask) =
        testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) { this.task = task }

    private fun body(r: FakeRequest): JsonObject = Json.parseToJsonElement(r.body) as JsonObject

    /** llama-server's base64: the floats' bytes in the machine's order (little-endian on every target). */
    private fun base64(v: FloatArray): String {
        val b = ByteBuffer.allocate(v.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        return Base64.getEncoder().encodeToString(b.array())
    }

    /** `format_embeddings_response_oaicompat` with base64, entries in the given (index, vector) order. */
    private fun embeddingsResponse(vararg entries: Pair<Int, FloatArray>): String =
        """{"model":"m","object":"list","usage":{"prompt_tokens":3,"total_tokens":3},"data":[""" +
            entries.joinToString(",") { (i, v) ->
                """{"embedding":"${base64(v)}","index":$i,"object":"embedding","encoding_format":"base64"}"""
            } + "]}"

    private fun answer(engine: FakeEngine, response: String) {
        engine.model.onRequest = { r ->
            r.chunk(response)
            r.done()
        }
    }

    @Test
    fun embedSendsBase64AndDecodesTheVector() =
        runTest {
            val engine = FakeEngine()
            val v = floatArrayOf(0.1f, -2f, 3.5f, Float.MIN_VALUE)
            answer(engine, embeddingsResponse(0 to v))
            val out = model(engine, ModelTask.Embedding()).embed("hello")
            assertArrayEquals(v, out, 0f)
            val r = engine.model.requests.single()
            assertEquals("embeddings", r.endpoint)
            assertEquals(JsonPrimitive("base64"), body(r)["encoding_format"])
            assertEquals(JsonPrimitive("hello"), body(r)["input"])
        }

    @Test
    fun embedManyKeepsOrder() =
        runTest {
            val engine = FakeEngine()
            val a = floatArrayOf(1f, 0f)
            val b = floatArrayOf(0f, 1f)
            val c = floatArrayOf(0.5f, 0.5f)
            // out of order on purpose: the vectors follow `index`, not the response's order
            answer(engine, embeddingsResponse(2 to c, 0 to a, 1 to b))
            val out = model(engine, ModelTask.Embedding()).embed(listOf("a", "b", "c"))
            assertEquals(3, out.size)
            assertArrayEquals(a, out[0], 0f)
            assertArrayEquals(b, out[1], 0f)
            assertArrayEquals(c, out[2], 0f)
            assertEquals(
                JsonArray(listOf("a", "b", "c").map { JsonPrimitive(it) }),
                body(engine.model.requests.single())["input"],
            )
        }

    @Test
    fun normalizationIsSent() =
        runTest {
            val engine = FakeEngine()
            answer(engine, embeddingsResponse(0 to floatArrayOf(1f)))
            val m = model(engine, ModelTask.Embedding())
            val cases =
                listOf(
                    Normalization.None to -1,
                    Normalization.MaxAbsInt16 to 0,
                    Normalization.Taxicab to 1,
                    Normalization.Euclidean to 2,
                    Normalization.PNorm(3) to 3,
                    Normalization.PNorm(7) to 7,
                )
            for ((n, expected) in cases) m.embed("x", normalization = n)
            m.embed("x") // the default is upstream's: Euclidean
            val sent = engine.model.requests.map { body(it)["embd_normalize"]!!.jsonPrimitive.int }
            assertEquals(cases.map { it.second } + 2, sent)
            assertThrows(IllegalArgumentException::class.java) { Normalization.PNorm(2) }
            assertEquals(Normalization.PNorm(4), Normalization.PNorm(4))
            assertEquals("PNorm(p=4)", Normalization.PNorm(4).toString())
        }

    @Test
    fun embedContentSendsItsParts() =
        runTest {
            val engine = FakeEngine()
            val v = floatArrayOf(0.25f)
            answer(engine, embeddingsResponse(0 to v))
            val png = byteArrayOf(1, 2, 3)
            val out =
                model(engine, ModelTask.Embedding()).embed(Content.Text("a cat"), Content.Image(png, "image/png"))
            assertArrayEquals(v, out, 0f)
            val parts = body(engine.model.requests.single())["input"]!!.jsonObject["content"]!!.jsonArray
            assertEquals(JsonPrimitive("text"), parts[0].jsonObject["type"])
            assertEquals(JsonPrimitive("a cat"), parts[0].jsonObject["text"])
            assertEquals(JsonPrimitive("image_url"), parts[1].jsonObject["type"])
            // D84: the native side writes the data URL in place of the placeholder
            assertEquals(JsonPrimitive("lb-attachment:0"), parts[1].jsonObject["image_url"]!!.jsonObject["url"])
            val a =
                engine.model.requests
                    .single()
                    .attachments
                    .single() as EngineAttachment.Bytes
            assertEquals("0" to "image/png", a.name to a.mime)
            assertArrayEquals(png, a.bytes)
        }

    @Test
    fun embedMediaFromAFileOrDescriptorIsNeverReadInKotlin() =
        runTest {
            val engine = FakeEngine()
            answer(engine, embeddingsResponse(0 to floatArrayOf(1f)))
            engine.model.infoJson = FakeModel.HEARING_INFO
            val big =
                java.io.File
                    .createTempFile("big", ".png")
                    .apply { deleteOnExit() }
            java.io.RandomAccessFile(big, "rw").use { it.setLength(20L * 1024 * 1024) }
            model(engine, ModelTask.Embedding())
                .embed(
                    Content.Image(MediaSource.file(big.path), "image/png"),
                    Content.Audio(MediaSource.Descriptor { 9 }),
                )
            val r = engine.model.requests.single()
            assertTrue("only placeholders: ${r.body.length} chars", r.body.length < 1024)
            val parts = body(r)["input"]!!.jsonObject["content"]!!.jsonArray
            assertEquals(JsonPrimitive("lb-attachment:1"), parts[1].jsonObject["input_audio"]!!.jsonObject["data"])
            assertEquals(big.path, (r.attachments[0] as EngineAttachment.Path).path)
            assertEquals(9, (r.attachments[1] as EngineAttachment.Fd).fd)
            assertTrue(engine.fdReads.isEmpty())
            assertFalse(Thread.currentThread() == engine.model.requestThreads.single())
        }

    @Test
    fun embedAudioIsCheckedLikeChat() =
        runTest {
            val engine = FakeEngine()
            val deaf = model(engine, ModelTask.Embedding())
            val e1 = runCatching { deaf.embed(Content.Audio(MediaSource.bytes(MediaTest.WAV))) }.exceptionOrNull()
            assertTrue("$e1", e1 is LlamaBroException.InvalidRequest && e1.message!!.contains("supportsAudio"))
            val hearing = FakeEngine().apply { model.infoJson = FakeModel.HEARING_INFO }
            val e2 =
                runCatching {
                    model(hearing, ModelTask.Embedding()).embed(Content.Audio(MediaSource.bytes(MediaTest.M4A)))
                }.exceptionOrNull()
            assertTrue("$e2", e2 is LlamaBroException.InvalidRequest && e2.message!!.contains("WAV, MP3 or FLAC"))
            assertTrue(engine.model.requests.isEmpty() && hearing.model.requests.isEmpty())
        }

    @Test
    fun aPictureOnAModelWithoutPicturesIsInvalidRequest() =
        runTest {
            val engine = FakeEngine()
            // oaicompat_content_load_media throws a runtime_error, which ex_wrapper sends as a server_error
            val message =
                "image input is not supported - hint: if this is unexpected, you may need to provide the mmproj"
            engine.model.onRequest = { r ->
                r.chunk("""{"error":{"code":500,"message":"$message","type":"server_error"}}""", 500)
                r.done()
            }
            val m = model(engine, ModelTask.Embedding())
            val e = runCatching { m.embed(Content.Image(byteArrayOf(1))) }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.InvalidRequest)
            assertEquals(message, e!!.message)
        }

    @Test
    fun aPictureTheDecoderRefusesIsInvalidRequest() =
        runTest {
            val engine = FakeEngine()
            engine.model.onRequest = { r ->
                r.chunk(
                    """{"error":{"code":500,"message":"Failed to load image or audio file","type":"server_error"}}""",
                    500,
                )
                r.done()
            }
            val m = model(engine, ModelTask.Embedding())
            val e = runCatching { m.embed(Content.Image(byteArrayOf(1), "image/heic")) }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.InvalidRequest)
            assertTrue(e!!.message!!, e.message!!.contains("not HEIC or WebP"))
        }

    private fun rerankResponse(vararg ranked: Pair<Int, Double>): String =
        """{"model":"m","object":"list","usage":{"prompt_tokens":9,"total_tokens":9},"results":[""" +
            ranked.joinToString(",") { (i, s) -> """{"index":$i,"relevance_score":$s}""" } + "]}"

    @Test
    fun rerankReturnsBestFirstWithTopN() =
        runTest {
            val engine = FakeEngine()
            answer(engine, rerankResponse(2 to 0.9, 0 to 0.25))
            val m = model(engine, ModelTask.Rerank)
            val out = m.rerank("q", listOf("d0", "d1", "d2"), topN = 2)
            assertEquals(listOf(Ranked(2, 0.9f), Ranked(0, 0.25f)), out)
            val sent = body(engine.model.requests.single())
            assertEquals(
                "rerank",
                engine.model.requests
                    .single()
                    .endpoint,
            )
            assertEquals(JsonPrimitive("q"), sent["query"])
            assertEquals(JsonArray(listOf("d0", "d1", "d2").map { JsonPrimitive(it) }), sent["documents"])
            assertEquals(JsonPrimitive(2), sent["top_n"])

            m.rerank("q", listOf("d0"))
            assertFalse("top_n left to upstream", "top_n" in body(engine.model.requests.last()))
            assertEquals("Ranked(index=2, score=0.9)", Ranked(2, 0.9f).toString())
        }

    @Test
    fun embedOnAChatModelNamesTheTask() =
        runTest {
            val engine = FakeEngine()
            val m = model(engine, ModelTask.Generate)
            for (call in listOf<suspend () -> Unit>(
                { m.embed("x") },
                { m.embed(listOf("x")) },
                { m.embed(Content.Text("x")) },
            )) {
                val e = runCatching { call() }.exceptionOrNull()
                assertTrue("$e", e is IllegalStateException)
                assertTrue(e!!.message, e.message!!.contains("ModelTask.Embedding"))
                assertTrue(e.message, e.message!!.contains("ModelTask.Generate"))
            }
            assertTrue("no request made", engine.model.requests.isEmpty())
        }

    @Test
    fun rerankOnAnEmbeddingModelNamesTheTask() =
        runTest {
            val engine = FakeEngine()
            val m = model(engine, ModelTask.Embedding(Pooling.Mean))
            val e = runCatching { m.rerank("q", listOf("d")) }.exceptionOrNull()
            assertTrue("$e", e is IllegalStateException)
            assertTrue(e!!.message, e.message!!.contains("ModelTask.Rerank"))
            assertTrue(e.message, e.message!!.contains("ModelTask.Embedding"))
            assertTrue("no request made", engine.model.requests.isEmpty())

            // and a reranker can't embed
            val r = model(FakeEngine(), ModelTask.Rerank)
            val e2 = runCatching { r.embed("x") }.exceptionOrNull()
            assertTrue("$e2", e2 is IllegalStateException && e2.message!!.contains("ModelTask.Embedding"))
        }

    @Test
    fun inputPastTheBatchIsInputTooLong() =
        runTest {
            for (task in listOf(ModelTask.Embedding(), ModelTask.Rerank)) {
                val engine = FakeEngine()
                // server-context.cpp's send_error for an input that can't be split, ERROR_TYPE_SERVER
                val message =
                    "input (600 tokens) is too large to process. increase the physical batch size " +
                        "(current batch size: 512)"
                engine.model.onRequest = { r ->
                    r.chunk("""{"error":{"code":500,"message":"$message","type":"server_error"}}""", 500)
                    r.done()
                }
                val m = model(engine, task)
                val e =
                    runCatching {
                        if (task is ModelTask.Rerank) m.rerank("q", listOf("d")) else m.embed("x")
                    }.exceptionOrNull()
                if (e !is LlamaBroException.InputTooLong) fail("$task: $e")
                e as LlamaBroException.InputTooLong
                assertEquals(600, e.tokens)
                assertEquals(512, e.limit)
                assertTrue(e.message, e.message!!.contains("microBatchSize"))
            }
        }
}
