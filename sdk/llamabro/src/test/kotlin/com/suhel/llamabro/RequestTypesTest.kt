package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.Reasoning
import com.suhel.llamabro.chat.ReasoningFormat
import com.suhel.llamabro.chat.ResponseFormat
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.chat.Tool
import com.suhel.llamabro.chat.ToolCall
import com.suhel.llamabro.chat.ToolChoice
import com.suhel.llamabro.internal.ChatJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Request types built through builders, with the golden bodies unchanged (F37, F43). */
class RequestTypesTest {
    private fun body(r: ChatRequest) = Json.parseToJsonElement(ChatJson.encode(r).body) as JsonObject

    /** The golden requests (recorded before the builders): byte-identical bodies. */
    @Test
    fun bodiesMatchTheGolden() {
        val golden = Json.parseToJsonElement(javaClass.getResource("/p1-bodies.json")!!.readText()) as JsonObject
        val weather =
            Tool.function(
                "get_weather",
                "Weather",
                """{"type":"object","properties":{"city":{"type":"string"}}}""",
            )
        val cases =
            mapOf(
                "minimal" to ChatRequest { messages(Message.user("hi")) },
                "image" to
                    ChatRequest {
                        messages(
                            Message.user(Content.Text("what?"), Content.Image(byteArrayOf(1, 2, 3), "image/png")),
                        )
                    },
                "allSampling" to
                    ChatRequest {
                        messages(Message.user("hi"))
                        sampling {
                            temperature = 0.7f
                            topK = 40
                            topP = 0.9f
                            minP = 0.05f
                            presencePenalty = 0.1f
                            frequencyPenalty =
                                0.2f
                            repeatPenalty = 1.1f
                            repeatLastN = 64
                            seed = 5
                            samplers = listOf("top_k", "temperature")
                            maxTokens =
                                32
                        }
                    },
                "toolsReasoningFormat" to
                    ChatRequest {
                        messages(
                            Message.system("sys"),
                            Message.user("weather?"),
                            Message.Assistant(
                                content = null,
                                reasoning = "think",
                                toolCalls = listOf(ToolCall("c1", "get_weather", """{"city":"Paris"}""")),
                            ),
                            Message.ToolResult("c1", "sunny"),
                            Message.Assistant(content = "It is sunny."),
                        )
                        tools(weather)
                        toolChoice = ToolChoice.Function("get_weather")
                        parallelToolCalls = false
                        responseFormat = ResponseFormat.JsonSchema("""{"type":"object"}""", name = "x")
                        reasoning {
                            format = ReasoningFormat.DeepSeek
                            budgetTokens = 64
                            enableThinking = false
                        }
                        stop("END", "STOP")
                    },
                "choicesAuto" to
                    ChatRequest {
                        messages(Message.user("hi"))
                        tools(weather)
                        toolChoice = ToolChoice.Auto
                        responseFormat = ResponseFormat.JsonObject
                        reasoning { format = ReasoningFormat.Auto }
                    },
                "choicesNone" to
                    ChatRequest {
                        messages(Message.user("hi"))
                        toolChoice = ToolChoice.None
                        reasoning {
                            format = ReasoningFormat.None
                            enableThinking = true
                        }
                    },
                "choicesRequired" to
                    ChatRequest {
                        messages(Message.user("hi"))
                        toolChoice = ToolChoice.Required
                        responseFormat = ResponseFormat.JsonSchema("""{"type":"string"}""")
                    },
                "extraTopLevel" to
                    ChatRequest {
                        messages(Message.user("hi"))
                        sampling { temperature = 0.5f }
                        extra =
                            """{"temperature":0.1,"n_probs":3}"""
                    },
            )
        for ((name, request) in cases) {
            // the one deliberate change since the golden (D84): a picture is a placeholder the native side fills
            val expected =
                golden
                    .getValue(
                        name,
                    ).jsonPrimitive.content
                    .replace("data:image/png;base64,AQID", "lb-attachment:0")
            assertEquals(name, expected, ChatJson.encode(request).body)
        }
        // the other deliberate change (F43): the golden's encoder replaced the whole object and lost enable_thinking
        val nested =
            ChatJson
                .encode(
                    ChatRequest {
                        messages(Message.user("hi"))
                        reasoning { enableThinking = false }
                        extra =
                            """{"chat_template_kwargs":{"foo":1}}"""
                    },
                ).body
        assertEquals(
            golden
                .getValue(
                    "extraNested",
                ).jsonPrimitive.content
                .replace("""{"foo":1}""", """{"enable_thinking":false,"foo":1}"""),
            nested,
        )
    }

    /**
     * Every field of llama-server's completion schema at the pinned build is typed, owned by the SDK, or listed as
     * reachable only through `extra`, with the reason. A new upstream field fails this test on an upgrade (F31).
     */
    @Test
    fun everySchemaSamplingFieldMapped() {
        val source = File("../native/third_party/llama.cpp/tools/server/server-schema.cpp").readText()
        val start = source.indexOf("make_llama_cmpl_schema(")
        val end = source.indexOf("eval_llama_cmpl_schema(", start)
        val code =
            source
                .substring(start, end)
                .lines()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
        val upstream = Regex("""add\(\(new field_\w+\("([a-z_.]+)"""").findAll(code).map { it.groupValues[1] }.toSet()
        assertTrue("schema parse found only ${upstream.size} fields", upstream.size >= 60)

        val typed =
            Sampling.wireNames - "max_tokens" + "n_predict" + // max_tokens is the OpenAI name for n_predict
                setOf("stop", "reasoning_format", "reasoning_budget_tokens", "reasoning_budget_message")
        val sdkOwned = setOf("stream", "stream_options", "return_progress")
        val extraOnly =
            mapOf(
                "verbose" to "debug output",
                "timings_per_token" to "timings arrive with Done",
                "cache_prompt" to "on by default, as in llama-server",
                "return_tokens" to "token ids are not typed (tokenize)",
                "sse_ping_interval" to "HTTP keep-alive, no HTTP layer here",
                "n_indent" to "infill (T6)",
                "n_keep" to "context shift is off (D17)",
                "n_discard" to "context shift is off (D17)",
                "n_cmpl" to "multiple completions are not typed",
                "n_cache_reuse" to "cache reuse is a model setting",
                "t_max_predict_ms" to "time limit; cancel the flow instead",
                "response_fields" to "shapes non-streamed output; chat always streams",
                "n_probs" to "logprobs are not typed",
                "post_sampling_probs" to "logprobs are not typed",
                "lora" to "LoRA adapters (T6 route)",
                "json_schema" to "native endpoint form; chat uses responseFormat",
                "grammar_lazy" to "set by the chat template",
                "continue_final_message" to "assistant prefill is not typed",
                "echo" to "completion endpoint only",
                "preserved_tokens" to "set by the chat template",
                "grammar_triggers" to "set by the chat template",
                "reasoning_control" to "Conversation.endReasoning uses the control endpoint",
                "reasoning_budget_start_tag" to "set by the chat template",
                "reasoning_budget_end_tags" to "set by the chat template",
                "speculative.n_max" to "speculative decoding is a model setting",
                "speculative.n_min" to "speculative decoding is a model setting",
                "speculative.p_min" to "speculative decoding is a model setting",
                "speculative.type" to "speculative decoding is a model setting",
                "speculative.ngram_size_n" to "speculative decoding is a model setting",
                "speculative.ngram_size_m" to "speculative decoding is a model setting",
                "speculative.ngram_min_hits" to "speculative decoding is a model setting",
            )
        val known = typed + sdkOwned + extraOnly.keys
        assertEquals("upstream fields not classified", emptySet<String>(), upstream - known)
        assertEquals("classified fields upstream doesn't have", emptySet<String>(), known - upstream)
    }

    @Test
    fun newSamplingFieldsUseLlamaServerNames() {
        val b =
            body(
                ChatRequest {
                    messages(Message.user("hi"))
                    sampling {
                        topNSigma = 1.5f
                        xtcProbability = 0.5f
                        xtcThreshold = 0.1f
                        typicalP = 0.9f
                        dynatempRange = 0.3f
                        dynatempExponent = 1.2f
                        dryMultiplier = 0.8f
                        dryBase = 1.75f
                        dryAllowedLength = 2
                        dryPenaltyLastN = -1
                        drySequenceBreakers =
                            listOf("\n")
                        mirostat = 2
                        mirostatTau = 5f
                        mirostatEta = 0.1f
                        adaptiveTarget = 0.5f
                        adaptiveDecay = 0.9f
                        minKeep = 2
                        ignoreEos = true
                        backendSampling = false
                        logitBias = mapOf("15043" to 2f, "Hello" to Float.NEGATIVE_INFINITY)
                    }
                },
            )
        val expected =
            mapOf(
                "top_n_sigma" to "1.5",
                "xtc_probability" to "0.5",
                "xtc_threshold" to "0.1",
                "typical_p" to "0.9",
                "dynatemp_range" to "0.3",
                "dynatemp_exponent" to "1.2",
                "dry_multiplier" to "0.8",
                "dry_base" to "1.75",
                "dry_allowed_length" to "2",
                "dry_penalty_last_n" to "-1",
                "dry_sequence_breakers" to """["\n"]""",
                "mirostat" to "2",
                "mirostat_tau" to "5.0",
                "mirostat_eta" to "0.1",
                "adaptive_target" to "0.5",
                "adaptive_decay" to "0.9",
                "min_keep" to "2",
                "ignore_eos" to "true",
                "backend_sampling" to "false",
                // llama-server reads `false` as -infinity (a ban); JSON has no infinity
                "logit_bias" to """{"15043":2.0,"Hello":false}""",
            )
        for ((k, v) in expected) assertEquals(k, v, b[k].toString())
    }

    @Test
    fun logitBiasRejectsNanAndPositiveInfinity() {
        assertThrows(IllegalArgumentException::class.java) { Sampling { logitBias = mapOf("a" to Float.NaN) } }
        assertThrows(IllegalArgumentException::class.java) {
            Sampling {
                logitBias =
                    mapOf("a" to Float.POSITIVE_INFINITY)
            }
        }
    }

    @Test
    fun extraMergesDeep() {
        val b =
            body(
                ChatRequest {
                    messages(Message.user("hi"))
                    reasoning { enableThinking = false }
                    extra = """{"chat_template_kwargs":{"foo":{"a":1}},"n_probs":2}"""
                },
            )
        assertEquals("""{"enable_thinking":false,"foo":{"a":1}}""", b["chat_template_kwargs"].toString())
        assertEquals("2", b["n_probs"].toString())
    }

    @Test
    fun extraNullRemovesKey() {
        val b =
            body(
                ChatRequest {
                    messages(Message.user("hi"))
                    sampling {
                        temperature = 0.5f
                        topK = 10
                    }
                    reasoning { enableThinking = false }
                    extra = """{"top_k":null,"chat_template_kwargs":{"enable_thinking":null}}"""
                },
            )
        assertFalse(b.containsKey("top_k"))
        assertEquals("0.5", b["temperature"].toString())
        assertEquals("{}", b["chat_template_kwargs"].toString())
    }

    @Test
    fun extraRejectsWhatTheSdkOwns() {
        for (key in listOf("stream", "stream_options", "return_progress")) {
            val e =
                assertThrows(IllegalArgumentException::class.java) {
                    ChatRequest {
                        messages(Message.user("hi"))
                        extra =
                            """{"$key":false}"""
                    }
                }
            assertTrue(e.message, e.message!!.contains(key))
        }
        assertThrows(IllegalArgumentException::class.java) { ChatRequest { extra = "[1]" } }
        assertThrows(IllegalArgumentException::class.java) { ChatRequest { extra = "{nope" } }
    }

    @Test
    fun valueSemanticsAndCopy() {
        val a =
            Sampling {
                temperature = 0.2f
                seed = 1
            }
        assertEquals(
            Sampling {
                seed = 1
                temperature = 0.2f
            },
            a,
        )
        assertEquals(
            a.hashCode(),
            Sampling {
                seed = 1
                temperature = 0.2f
            }.hashCode(),
        )
        val b = a.copy { maxTokens = 8 }
        assertEquals(8, b.maxTokens)
        assertEquals(0.2f, b.temperature)
        assertEquals(null, a.maxTokens) // the original is unchanged
        assertNotEquals(a, b)
        assertEquals(Sampling.Default, Sampling { })
        assertTrue(a.toString(), a.toString().contains("temperature=0.2"))

        val r =
            ChatRequest {
                messages(Message.user("hi"))
                sampling { temperature = 0.1f }
            }
        val r2 = r.copy { stop("x") }
        assertEquals(listOf("x"), r2.stop)
        assertEquals(r.sampling, r2.sampling)
        assertEquals(
            r,
            ChatRequest {
                messages(Message.user("hi"))
                sampling { temperature = 0.1f }
            },
        )
        assertEquals(Reasoning { enableThinking = false }, Reasoning.Default.copy { enableThinking = false })
    }

    @Test
    fun builderBlocksLayerOnTheCurrentValue() {
        val r =
            ChatRequest {
                sampling { temperature = 0.3f }
                sampling { topK = 5 } // adds to the first block, like the DSLs in kotlinx
                reasoning { budgetTokens = 10 }
                reasoning { budgetMessage = "stop thinking" }
            }
        assertEquals(0.3f, r.sampling.temperature)
        assertEquals(5, r.sampling.topK)
        assertEquals(10, r.reasoning.budgetTokens)
        assertEquals(""""stop thinking"""", body(r)["reasoning_budget_message"].toString())
    }
}
