package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.Reasoning
import com.suhel.llamabro.chat.ResponseFormat
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.chat.Tool
import com.suhel.llamabro.conversation.compact
import com.suhel.llamabro.internal.ChatStreamDecoder
import com.suhel.llamabro.internal.Engine
import com.suhel.llamabro.internal.EngineModel
import com.suhel.llamabro.internal.EngineRequest
import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.NativeError
import com.suhel.llamabro.internal.RequestSink
import com.suhel.llamabro.internal.SseParser
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Host end-to-end, differential: the Kotlin API over JNI over the real native core (this machine, CPU) runs a
 * scenario (typed chat, raw endpoints, an agent Conversation with tools, compaction). Every request it makes is
 * recorded, then replayed in the same order to upstream llama-server built from the same tree, started by
 * sdk/scripts/check.sh with the same settings. Each pair must decode to the same thing, prompt-cache counters
 * included, so the whole stack is checked against upstream, not against our own expectations.
 */
class HostNativeTest {
    private class Record(val endpoint: String, val params: String?, val body: String) {
        val bytes = ByteArrayOutputStream()
        var status = 0

        @Volatile var terminal = false
    }

    /** Wraps the real engine and keeps every request and its raw response, in start order. */
    private class RecordingEngine(private val inner: Engine) : Engine by inner {
        val records = mutableListOf<Record>()

        override fun load(configJson: String, onProgress: (Float) -> Unit): EngineModel {
            val model = inner.load(configJson, onProgress)
            return object : EngineModel by model {
                override fun request(
                    endpoint: String,
                    paramsJson: String?,
                    body: ByteArray,
                    sink: RequestSink,
                ): EngineRequest {
                    val r = Record(endpoint, paramsJson, String(body, Charsets.UTF_8))
                    synchronized(records) { records += r }
                    return model.request(
                        endpoint,
                        paramsJson,
                        body,
                        object : RequestSink {
                            override fun onChunk(httpStatus: Int, data: ByteArray) {
                                synchronized(r) {
                                    r.status = httpStatus
                                    r.bytes.write(data)
                                }
                                sink.onChunk(httpStatus, data)
                            }

                            override fun onDone() {
                                r.terminal = true
                                sink.onDone()
                            }

                            override fun onCancelled() {
                                r.terminal = true
                                sink.onCancelled()
                            }
                        },
                    )
                }
            }
        }
    }

    private val paths =
        mapOf(
            "chat" to "/v1/chat/completions",
            "tokenize" to "/tokenize",
            "detokenize" to "/detokenize",
            "apply_template" to "/apply-template",
            "count_tokens" to "/v1/chat/completions/input_tokens",
        )

    private fun post(server: String, endpoint: String, body: String): Pair<Int, ByteArray> {
        val c = URL(server + paths.getValue(endpoint)).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = c.responseCode
        val bytes = (if (status >= 400) c.errorStream else c.inputStream).use { it.readBytes() }
        return status to bytes
    }

    /** What a response means, without the fields that differ per run (ids, timestamps, speeds). */
    private fun normalize(body: String, status: Int, bytes: ByteArray): String {
        val stream =
            runCatching {
                ((Json.parseToJsonElement(body) as JsonObject)["stream"] as? JsonPrimitive)?.booleanOrNull
            }.getOrNull() ==
                true
        if (stream && status < 400) {
            val decoder = ChatStreamDecoder()
            val events =
                SseParser().feed(bytes).filter { it != "[DONE]" }.flatMap { decoder.accept(it) } + decoder.finish()
            val done = events.last() as ChatEvent.Done
            return listOf(
                "content=" + events.filterIsInstance<ChatEvent.ContentDelta>().joinToString("") { it.delta },
                "reasoning=" + events.filterIsInstance<ChatEvent.ReasoningDelta>().joinToString("") { it.delta },
                "calls=" +
                    events.filterIsInstance<ChatEvent.ToolCallReceived>().map { it.call.name to it.call.argumentsJson },
                "finish=${done.reason} usage=${done.usage}",
                "prompt=${done.timings?.promptTokens} cached=${done.timings?.cachedTokens} predicted=${done.timings?.predictedTokens}",
            ).joinToString("\n")
        }
        val text = String(bytes, Charsets.UTF_8)
        // A pre-encode (`n_predict: 0`, no sampling, as tools/ui sends it) still samples one token, with a random
        // seed on both sides (F52): compare its cache counters, not that token.
        val preEncode = "\"n_predict\":0" in body
        return "status=$status " + (
            runCatching {
                val e = strip(Json.parseToJsonElement(text))
                (if (preEncode && e is JsonObject) JsonObject(e.filterKeys { it != "choices" }) else e).toString()
            }.getOrDefault(text)
        )
    }

    private fun strip(e: JsonElement): JsonElement =
        if (e is JsonObject) {
            JsonObject(
                e.filterKeys { it !in setOf("id", "created", "system_fingerprint") }.mapValues { (k, v) ->
                    if (k == "timings" && v is JsonObject) {
                        JsonObject(v.filterKeys { it in setOf("prompt_n", "cache_n", "predicted_n") })
                    } else {
                        v
                    }
                },
            )
        } else {
            e
        }

    @Test
    fun nativeTrafficMatchesLlamaServer() =
        runBlocking {
            val lib = System.getProperty("llamabro.host.lib")
            val server = System.getProperty("llamabro.host.server")
            val modelPath = System.getProperty("llamabro.host.model")
            assumeTrue(
                "host end-to-end runs from sdk/scripts/check.sh",
                lib != null && server != null && modelPath != null,
            )
            val mmproj = System.getProperty("llamabro.host.mmproj")

            val engine = RecordingEngine(JniEngine)
            val llama = LlamaBro(LlamaBroRuntime(engine, tempDir(), lib, LlamaBroConfig.Default))
            val model =
                llama.loadModel(ModelSource.file(modelPath) { this.mmproj = mmproj }) {
                    contextSize =
                        ContextSize.Fixed(8192)
                }
            val greedy =
                Sampling {
                    temperature = 0f
                    seed = 1
                    maxTokens = 48
                }
            val noThink = Reasoning { enableThinking = false }
            val weather =
                Tool.function(
                    "get_weather",
                    "Current weather for a city",
                    """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
                )

            // typed chat
            model
                .chat(
                    ChatRequest {
                        messages(Message.system("Be brief."), Message.user("Capital of Japan?"))
                        sampling =
                            greedy
                        reasoning = noThink
                    },
                ).collect()
            model
                .chat(
                    ChatRequest {
                        messages(Message.user("Is 51 prime?"))
                        sampling = greedy.copy { maxTokens = 96 }
                        reasoning {
                            enableThinking =
                                true
                        }
                    },
                ).collect()
            model
                .chat(
                    ChatRequest {
                        messages(Message.user("Weather in Paris?"))
                        tools(weather)
                        sampling = greedy
                        reasoning =
                            noThink
                    },
                ).collect()
            model
                .chat(
                    ChatRequest {
                        messages(Message.user("A person named Bob aged 30."))
                        sampling = greedy
                        reasoning = noThink
                        responseFormat =
                            ResponseFormat.JsonSchema(
                                """{"type":"object","properties":{"name":{"type":"string"},"age":{"type":"integer"}},"required":["name","age"]}""",
                            )
                    },
                ).collect()
            model
                .chat(
                    ChatRequest {
                        messages(Message.user("Name a colour and a fruit."))
                        reasoning = noThink
                        sampling {
                            temperature = 0.8f
                            seed = 7
                            topK = 40
                            topP = 0.9f
                            maxTokens = 32
                        }
                    },
                ).collect()
            if (mmproj != null) {
                val dataUrl =
                    File("../native/tests/contract/t2.jsonl")
                        .readLines()
                        .first { "t2-14-image" in it }
                        .substringAfter("base64,")
                        .substringBefore("\"")
                val described =
                    model
                        .chat(
                            ChatRequest {
                                messages(
                                    Message.user(
                                        Content.Text("Describe the colours."),
                                        Content.Image(Base64.getDecoder().decode(dataUrl), "image/png"),
                                    ),
                                )
                                sampling = greedy
                                reasoning = noThink
                            },
                        ).toList()
                        .filterIsInstance<ChatEvent.ContentDelta>()
                        .joinToString("") { it.delta }
                        .lowercase()
                // the picture is red on the left and blue on the right: matching upstream is not enough, since both can
                // be wrong together (F119, F120)
                // whole words, red before blue: "coloured" contains "red"
                val red = Regex("\\bred\\b").find(described)?.range?.first
                val blue = Regex("\\bblue\\b").find(described)?.range?.first
                assertTrue("the model didn't see the picture: $described", red != null && blue != null && red < blue)
            }
            // raw endpoints
            model.rawRequest(Endpoint.Tokenize, """{"content":"Hello, wörld! 😀","with_pieces":true}""").collect()
            model.rawRequest(Endpoint.ApplyTemplate, """{"messages":[{"role":"user","content":"Hi"}]}""").collect()
            model
                .rawRequest(
                    Endpoint.CountTokens,
                    """{"messages":[{"role":"user","content":"How long is this?"}]}""",
                ).collect()
            // an error: the prompt doesn't fit the 8,192-token context (llama-server's exceed_context_size_error)
            val tooLong =
                runCatching {
                    model
                        .chat(
                            ChatRequest {
                                messages(Message.user("word ".repeat(9000)))
                                sampling = greedy
                                reasoning =
                                    noThink
                            },
                        ).collect()
                }.exceptionOrNull()
            assertTrue("$tooLong", tooLong is LlamaBroException.ContextExceeded)

            // an agent conversation: two sends with a tool loop, a compaction, and a send on the compacted history
            val chat =
                model.conversation("You are terse. Use tools for weather.") {
                    // room for the compaction summary too: it uses the conversation's sampling, as in tools/ui
                    defaults {
                        sampling = greedy.copy { maxTokens = 200 }
                        reasoning = noThink
                    }
                    tool(
                        "get_weather",
                        "Current weather for a city",
                        """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""",
                    ) { args ->
                        "Sunny, 21 °C in ${args.string("city")}"
                    }
                }
            chat.send("What's the weather in Paris?").toList()
            chat.send("And in Rome?").toList()
            chat.compact(preservedSteps = 1).toList()
            // the background pre-encode must finish before the next request, or it'd be cut at a timing-dependent point
            val preEncode = {
                synchronized(
                    engine.records,
                ) { engine.records.lastOrNull { "\"n_predict\":0" in it.body } }
            }
            val t0 = System.currentTimeMillis()
            while ((preEncode()?.terminal != true) && System.currentTimeMillis() - t0 < 120_000) Thread.sleep(50)
            chat.send("Where were we?").toList()

            val records = synchronized(engine.records) { engine.records.filter { it.endpoint in paths && it.terminal } }
            assertTrue("expected the whole scenario recorded, got ${records.size}", records.size >= 15)
            model.close()

            // replay to upstream llama-server, same order, and compare
            val mismatches =
                records.mapIndexedNotNull { i, r ->
                    val (status, bytes) = post(server, r.endpoint, r.body)
                    val native = normalize(r.body, r.status, r.bytes.toByteArray())
                    val upstream = normalize(r.body, status, bytes)
                    if (native == upstream) {
                        null
                    } else {
                        "#$i ${r.endpoint} ${r.body.take(160)}\n  native:   $native\n  upstream: $upstream"
                    }
                }
            println("host end-to-end: ${records.size - mismatches.size}/${records.size} identical")
            assertEquals(mismatches.joinToString("\n"), 0, mismatches.size)
        }

    // --- load failures over the real core (PA T5, F51) -------------------------------------------------

    private fun hostLlama(): LlamaBro {
        val lib = System.getProperty("llamabro.host.lib")
        assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null)
        return LlamaBro(LlamaBroRuntime(JniEngine, tempDir(), lib, LlamaBroConfig.Default))
    }

    @Test
    fun loadReasonFileNotFound() =
        runBlocking {
            val llama = hostLlama()
            val e = runCatching { llama.loadModel(ModelSource.file("/nonexistent-llamabro.gguf")) }.exceptionOrNull()
            assertTrue(
                "$e",
                e is LlamaBroException.ModelLoadFailed &&
                    e.reason == LlamaBroException.ModelLoadFailed.Reason.FileNotFound,
            )
            assertTrue("${e!!.message}", e.message!!.contains("/nonexistent-llamabro.gguf"))
        }

    /** A cut-off download: upstream's reason reaches the message (it only logs it). */
    @Test
    fun loadFailureCarriesUpstreamReason() =
        runBlocking {
            val llama = hostLlama()
            val modelPath = System.getProperty("llamabro.host.model")
            assumeTrue(modelPath != null)
            val truncated = File(tempDir(), "truncated.gguf")
            File(modelPath).inputStream().use { input ->
                truncated.outputStream().use { it.write(input.readNBytes(20_000_000)) }
            }
            val e =
                runCatching {
                    llama.loadModel(ModelSource.file(truncated.path)) { contextSize = ContextSize.Fixed(512) }
                }.exceptionOrNull()
            assertTrue(
                "$e",
                e is LlamaBroException.ModelLoadFailed && e.reason == LlamaBroException.ModelLoadFailed.Reason.Other,
            )
            assertTrue("${e!!.message}", e.message!!.contains("not within the file bounds"))
        }

    // --- every route (PA T6, F53) ----------------------------------------------------------------------

    /** Each [Endpoint] reaches an upstream handler: any answer but the bridge's "unknown endpoint" will do. */
    @Test
    fun everyEndpointReachesAHandler() =
        runBlocking {
            val llama = hostLlama()
            val modelPath = System.getProperty("llamabro.host.model")
            assumeTrue(modelPath != null)
            val model = llama.loadModel(ModelSource.file(modelPath)) { contextSize = ContextSize.Fixed(512) }
            try {
                // "[]" is a bad body for every POST route, so nothing generates; GET routes ignore it. The whole cause
                // chain: coroutines' stack-trace recovery may rethrow a copy whose cause is the original (F53)
                suspend fun bridgeRejects(e: Endpoint) =
                    generateSequence(
                        runCatching { model.rawRequest(e, "[]").collect() }
                            .exceptionOrNull(),
                    ) { it.cause }.any { it is NativeError }
                val endpoints =
                    Endpoint::class.java.fields.filter { it.type == Endpoint::class.java }.map {
                        it.get(
                            null,
                        ) as Endpoint
                    }
                assertEquals(28, endpoints.size) // b12352 added /v1/audio/speech
                val unknown = endpoints.filter { bridgeRejects(it) }
                assertEquals("not in the C API's table: $unknown", emptyList<Endpoint>(), unknown)
                // the check can fail: a name the table lacks is rejected by the bridge
                val ctor =
                    Endpoint::class.java.getDeclaredConstructor(String::class.java, String::class.java).apply {
                        isAccessible =
                            true
                    }
                assertTrue(bridgeRejects(ctor.newInstance("no_such_route", "POST /nope")))
            } finally {
                model.closeAndJoin()
            }
        }
}
