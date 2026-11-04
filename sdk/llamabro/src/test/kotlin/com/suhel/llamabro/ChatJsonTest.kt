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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatJsonTest {
    private fun body(r: ChatRequest) = Json.parseToJsonElement(ChatJson.body(r)) as JsonObject

    @Test
    fun nullSamplingFieldsOmitted() {
        val b = body(ChatRequest(listOf(Message.user("hi"))))
        for (k in listOf("temperature", "top_k", "top_p", "min_p", "presence_penalty", "frequency_penalty",
            "repeat_penalty", "repeat_last_n", "seed", "samplers", "max_tokens", "tools", "tool_choice",
            "parallel_tool_calls", "response_format", "stop", "reasoning_format", "reasoning_budget_tokens", "chat_template_kwargs")) {
            assertFalse(k, b.containsKey(k))
        }
        assertEquals("true", b["stream"].toString())
        assertEquals("true", b["return_progress"].toString())
        assertEquals("""{"include_usage":true}""", b["stream_options"].toString())
        assertEquals("""[{"role":"user","content":"hi"}]""", b["messages"].toString())
    }

    @Test
    fun imageBecomesDataUrl() {
        val b = body(ChatRequest(listOf(Message.user(Content.Text("what?"), Content.Image(byteArrayOf(1, 2, 3))))))
        val parts = b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("""{"type":"text","text":"what?"}""", parts[0].toString())
        val url = parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(url, url.startsWith("data:image/jpeg;base64,"))
        assertEquals("data:image/jpeg;base64,AQID", url)
    }

    @Test
    fun samplersFieldSentWhenSet() {
        val b = body(ChatRequest(listOf(Message.user("hi")), sampling = Sampling(samplers = listOf("top_k", "temperature"), temperature = 0.7f, maxTokens = 32, seed = 5)))
        assertEquals("""["top_k","temperature"]""", b["samplers"].toString())
        assertEquals("0.7", b["temperature"].toString())
        assertEquals("32", b["max_tokens"].toString())
        assertEquals("5", b["seed"].toString())
    }

    @Test
    fun extraJsonMergedLast() {
        val b = body(ChatRequest(listOf(Message.user("hi")), sampling = Sampling(temperature = 0.5f), extraJson = """{"temperature":0.1,"n_probs":3}"""))
        assertEquals("0.1", b["temperature"].toString())
        assertEquals("3", b["n_probs"].toString())
    }

    @Test
    fun toolsReasoningAndFormatFields() {
        val b = body(ChatRequest(
            messages = listOf(
                Message.system("sys"),
                Message.user("weather?"),
                Message.Assistant(content = null, reasoning = "think", toolCalls = listOf(ToolCall("c1", "get_weather", """{"city":"Paris"}"""))),
                Message.ToolResult("c1", "sunny"),
            ),
            tools = listOf(Tool.function("get_weather", "Weather", """{"type":"object","properties":{"city":{"type":"string"}}}""")),
            toolChoice = ToolChoice.Function("get_weather"),
            parallelToolCalls = false,
            responseFormat = ResponseFormat.JsonSchema("""{"type":"object"}""", name = "x"),
            reasoning = Reasoning(format = ReasoningFormat.DeepSeek, budgetTokens = 64, enableThinking = false),
            stop = listOf("END"),
        ))
        val m = b["messages"]!!.jsonArray
        assertEquals("""{"role":"system","content":"sys"}""", m[0].toString())
        assertEquals("""{"role":"assistant","reasoning_content":"think","tool_calls":[{"id":"c1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}]}""", m[2].toString())
        assertEquals("""{"role":"tool","tool_call_id":"c1","content":"sunny"}""", m[3].toString())
        assertEquals("""[{"type":"function","function":{"name":"get_weather","description":"Weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}]""", b["tools"].toString())
        assertEquals("""{"type":"function","function":{"name":"get_weather"}}""", b["tool_choice"].toString())
        assertEquals("false", b["parallel_tool_calls"].toString())
        assertEquals("""{"type":"json_schema","json_schema":{"name":"x","schema":{"type":"object"}}}""", b["response_format"].toString())
        assertEquals("\"deepseek\"", b["reasoning_format"].toString())
        assertEquals("64", b["reasoning_budget_tokens"].toString())
        assertEquals("""{"enable_thinking":false}""", b["chat_template_kwargs"].toString())
        assertEquals("""["END"]""", b["stop"].toString())
    }
}
