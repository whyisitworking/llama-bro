package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.internal.ChatStreamDecoder
import com.suhel.llamabro.internal.SseParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes upstream llama-server's own stream (fixtures, see their README) and compares with compare.py's rebuild. */
class ChatStreamDecoderTest {
    private fun resource(name: String): String = javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun decode(fixture: String): List<ChatEvent> {
        val parser = SseParser()
        val decoder = ChatStreamDecoder()
        val events = mutableListOf<ChatEvent>()
        resource("$fixture.jsonl").lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val data = (Json.parseToJsonElement(line) as JsonObject)["data"]!!.jsonPrimitive.content
            parser.feed(data.toByteArray()).filter { it != "[DONE]" }.forEach { events += decoder.accept(it) }
        }
        events += decoder.finish()
        return events
    }

    private fun check(fixture: String) {
        val events = decode(fixture)
        val expected = Json.parseToJsonElement(resource("$fixture.expected.json")) as JsonObject
        assertEquals(
            expected["content"]!!.jsonPrimitive.content,
            events
                .filterIsInstance<ChatEvent.ContentDelta>()
                .joinToString(
                    "",
                ) {
                    it.delta
                },
        )
        assertEquals(
            expected["reasoning_content"]!!.jsonPrimitive.content,
            events
                .filterIsInstance<ChatEvent.ReasoningDelta>()
                .joinToString(
                    "",
                ) {
                    it.delta
                },
        )
        val calls = events.filterIsInstance<ChatEvent.ToolCallReceived>().map { it.call.name to it.call.argumentsJson }
        val expectedCalls =
            (expected["tool_calls"] as JsonArray).map {
                val o = it as JsonObject
                o["name"]!!.jsonPrimitive.content to o["arguments"]!!.jsonPrimitive.content
            }
        assertEquals(expectedCalls, calls)
        events.filterIsInstance<ChatEvent.ToolCallReceived>().forEach { assertTrue(it.call.id.isNotEmpty()) }
        val done = events.last() as ChatEvent.Done
        val reason =
            when (val r = expected["finish_reason"]) {
                null, JsonNull -> {
                    FinishReason.Stop
                }

                else -> {
                    when (r.jsonPrimitive.content) {
                        "length" -> FinishReason.Length
                        "tool_calls" -> FinishReason.ToolCalls
                        else -> FinishReason.Stop
                    }
                }
            }
        assertEquals(reason, done.reason)
        // tool calls come together, just before Done
        val firstCall = events.indexOfFirst { it is ChatEvent.ToolCallReceived }
        if (firstCall >= 0) {
            assertTrue(events.subList(firstCall, events.size - 1).all { it is ChatEvent.ToolCallReceived })
        }
        assertTrue(done.timings != null)
    }

    @Test fun plainMatchesUpstreamRebuild() = check("t2-01-plain")

    @Test fun thinkingMatchesUpstreamRebuild() = check("t2-06-thinking")

    @Test fun toolMatchesUpstreamRebuild() = check("t2-08-tool")

    @Test fun toolParallelMatchesUpstreamRebuild() = check("t2-09-tool-parallel")

    @Test fun jsonSchemaMatchesUpstreamRebuild() = check("t2-12-json-schema")

    @Test
    fun progressUsageAndId() {
        val d = ChatStreamDecoder()
        val p =
            d.accept(
                """{"choices":[{"finish_reason":null,"index":0,"delta":{"role":"assistant","content":null}}],"id":"chatcmpl-1","prompt_progress":{"total":100,"cache":20,"processed":60,"time_ms":15}}""",
            )
        assertEquals(listOf(ChatEvent.PromptProgress(processed = 60, total = 100, cached = 20, timeMs = 15)), p)
        assertEquals("chatcmpl-1", d.completionId)
        d.accept(
            """{"choices":[{"finish_reason":"length","index":0,"delta":{}}],"id":"chatcmpl-1","timings":{"cache_n":20,"prompt_n":80,"prompt_ms":1,"prompt_per_second":50.5,"predicted_n":5,"predicted_ms":1,"predicted_per_second":9.5,"draft_n":4,"draft_n_accepted":3}}""",
        )
        d.accept(
            """{"choices":[],"id":"chatcmpl-1","usage":{"completion_tokens":5,"prompt_tokens":100,"total_tokens":105}}""",
        )
        val done = d.finish().single() as ChatEvent.Done
        assertEquals(FinishReason.Length, done.reason)
        assertEquals(
            com.suhel.llamabro.chat
                .Timings(80, 20, 50.5, 5, 9.5, 4, 3),
            done.timings,
        )
        assertEquals(
            com.suhel.llamabro.chat
                .Usage(100, 5),
            done.usage,
        )
    }
}
