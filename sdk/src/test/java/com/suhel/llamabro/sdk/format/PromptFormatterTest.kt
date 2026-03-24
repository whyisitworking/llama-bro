package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.chat.pipeline.ThinkingMarker
import com.suhel.llamabro.sdk.config.ModelDefinition
import com.suhel.llamabro.sdk.config.ModelLoadConfig
import com.suhel.llamabro.sdk.models.ChatEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [PromptFormatter].
 *
 * Verifies that each [ChatEvent] variant is serialized into the correct wire
 * format for a given [PromptFormat], without any native code involvement.
 */
class PromptFormatterTest {

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** A transparent format — no wrapping — so tests focus on structural logic only. */
    private val passthrough = PromptFormat(
        systemPrefix = "",
        userPrefix = "",
        assistantPrefix = "",
        endOfTurn = "",
        emitAssistantPrefixOnGeneration = false,
        stopStrings = emptyList()
    )

    private fun modelWith(format: PromptFormat) = ModelDefinition(
        loadConfig = ModelLoadConfig(path = "fake.gguf"),
        promptFormat = format,
    )

    private fun modelWithThinking(format: PromptFormat, open: String = "<think>", close: String = "</think>") =
        ModelDefinition(
            loadConfig = ModelLoadConfig(path = "fake.gguf"),
            promptFormat = format,
            features = listOf(ThinkingMarker(open = open, close = close))
        )

    // ── System event ────────────────────────────────────────────────────────

    @Test
    fun `system event wraps content with systemPrefix and endOfTurn`() {
        val format = PromptFormat(
            systemPrefix = "<|im_start|>system\n",
            userPrefix = "",
            assistantPrefix = "",
            endOfTurn = "<|im_end|>\n",
            emitAssistantPrefixOnGeneration = false,
        )
        val formatter = PromptFormatter(modelWith(format))
        assertEquals(
            "<|im_start|>system\nYou are helpful.<|im_end|>\n",
            formatter.formatTurn(ChatEvent.SystemEvent("You are helpful."))
        )
    }

    // ── User event ──────────────────────────────────────────────────────────

    @Test
    fun `user event wraps content and emits assistant prefix when configured`() {
        val formatter = PromptFormatter(modelWith(PromptFormats.CHAT_ML))
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = false))
        assertEquals(
            "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n",
            result
        )
    }

    @Test
    fun `user event with think=true appends thinking marker open tag`() {
        val model = modelWithThinking(PromptFormats.CHAT_ML)
        val formatter = PromptFormatter(model)
        val result = formatter.formatTurn(ChatEvent.UserEvent("Solve this", think = true))
        // Should end with assistantPrefix + thinking open tag
        val expected = "<|im_start|>user\nSolve this<|im_end|>\n<|im_start|>assistant\n<think>\n"
        assertEquals(expected, result)
    }

    @Test
    fun `user event with think=false does not inject thinking marker`() {
        val model = modelWithThinking(PromptFormats.CHAT_ML)
        val formatter = PromptFormatter(model)
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = false))
        assert(!result.contains("<think>")) {
            "Expected no thinking tag, but got: $result"
        }
    }

    // ── Assistant event ──────────────────────────────────────────────────────

    @Test
    fun `assistant event with single text part formats correctly in ChatML`() {
        val formatter = PromptFormatter(modelWith(PromptFormats.CHAT_ML))
        val event = ChatEvent.AssistantEvent(parts = listOf(ChatEvent.AssistantEvent.Part.TextPart("Hi there")))
        val result = formatter.formatTurn(event)
        assertEquals("Hi there<|im_end|>\n", result)
    }

    @Test
    fun `assistant event with no text parts emits only endOfTurn`() {
        val formatter = PromptFormatter(modelWith(PromptFormats.CHAT_ML))
        val event = ChatEvent.AssistantEvent(parts = emptyList())
        val result = formatter.formatTurn(event)
        assertEquals("<|im_end|>\n", result)
    }

    // ── Llama3 format ────────────────────────────────────────────────────────

    @Test
    fun `Llama3 user event wraps with correct header tokens`() {
        val formatter = PromptFormatter(modelWith(PromptFormats.LLAMA_3))
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = false))
        assertEquals(
            "<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
            result
        )
    }

    // ── Nemotron format ──────────────────────────────────────────────────────

    @Test
    fun `Nemotron system event uses extra_id_0 sentinel`() {
        val formatter = PromptFormatter(modelWith(PromptFormats.NEMOTRON))
        val result = formatter.formatTurn(ChatEvent.SystemEvent("You are helpful."))
        assertEquals("<extra_id_0>System\nYou are helpful.\n", result)
    }

    @Test
    fun `Nemotron user event uses extra_id_1 sentinel`() {
        val formatter = PromptFormatter(modelWith(PromptFormats.NEMOTRON))
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = false))
        assertEquals("<extra_id_1>User\nHello\n<extra_id_1>Assistant\n", result)
    }
}
