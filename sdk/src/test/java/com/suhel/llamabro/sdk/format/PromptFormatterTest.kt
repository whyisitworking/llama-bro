package com.suhel.llamabro.sdk.format

import com.suhel.llamabro.sdk.chat.pipeline.TagDelimiter
import com.suhel.llamabro.sdk.config.ModelProfile
import com.suhel.llamabro.sdk.config.ThinkingCapability
import com.suhel.llamabro.sdk.config.ThinkingStrategy
import com.suhel.llamabro.sdk.chat.ChatEvent
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

    private fun profileWith(format: PromptFormat) = ModelProfile(
        promptFormat = format,
    )

    private fun profileWithThinking(
        format: PromptFormat,
        strategy: ThinkingStrategy = ThinkingStrategy.Prefill(
            forcePrefix = "<think>\n",
            suppressPrefix = "<think>\n\n</think>",
        ),
    ) = ModelProfile(
        promptFormat = format,
        thinking = ThinkingCapability(
            tags = TagDelimiter("<think>", "</think>"),
            strategy = strategy,
        ),
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
        val formatter = PromptFormatter(profileWith(format))
        assertEquals(
            "<|im_start|>system\nYou are helpful.<|im_end|>\n",
            formatter.formatTurn(ChatEvent.SystemEvent("You are helpful."))
        )
    }

    // ── User event ──────────────────────────────────────────────────────────

    @Test
    fun `user event wraps content and emits assistant prefix when configured`() {
        val formatter = PromptFormatter(profileWith(PromptFormats.CHAT_ML))
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = false))
        assertEquals(
            "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n",
            result
        )
    }

    @Test
    fun `user event with think=true and Prefill strategy inserts forcePrefix after assistant prefix`() {
        val profile = profileWithThinking(PromptFormats.CHAT_ML)
        val formatter = PromptFormatter(profile)
        val result = formatter.formatTurn(ChatEvent.UserEvent("Solve this", think = true))
        val expected = "<|im_start|>user\nSolve this<|im_end|>\n<|im_start|>assistant\n<think>\n"
        assertEquals(expected, result)
    }

    @Test
    fun `user event with think=false and Prefill strategy inserts suppressPrefix after assistant prefix`() {
        val profile = profileWithThinking(PromptFormats.CHAT_ML)
        val formatter = PromptFormatter(profile)
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = false))
        val expected = "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>"
        assertEquals(expected, result)
    }

    @Test
    fun `user event with think=true and SoftSwitch strategy appends enableDirective to content`() {
        val profile = profileWithThinking(
            PromptFormats.CHAT_ML,
            strategy = ThinkingStrategy.SoftSwitch(),
        )
        val formatter = PromptFormatter(profile)
        val result = formatter.formatTurn(ChatEvent.UserEvent("Solve this", think = true))
        val expected = "<|im_start|>user\nSolve this\n/think<|im_end|>\n<|im_start|>assistant\n"
        assertEquals(expected, result)
    }

    @Test
    fun `user event with think=false and SoftSwitch strategy appends disableDirective to content`() {
        val profile = profileWithThinking(
            PromptFormats.CHAT_ML,
            strategy = ThinkingStrategy.SoftSwitch(),
        )
        val formatter = PromptFormatter(profile)
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = false))
        val expected = "<|im_start|>user\nHello\n/no_think<|im_end|>\n<|im_start|>assistant\n"
        assertEquals(expected, result)
    }

    @Test
    fun `user event with think=true and None strategy does not inject anything`() {
        val profile = profileWithThinking(
            PromptFormats.CHAT_ML,
            strategy = ThinkingStrategy.None,
        )
        val formatter = PromptFormatter(profile)
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = true))
        assertEquals(
            "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n",
            result
        )
    }

    // ── Assistant event ──────────────────────────────────────────────────────

    @Test
    fun `assistant event with single text part formats correctly in ChatML`() {
        val formatter = PromptFormatter(profileWith(PromptFormats.CHAT_ML))
        val event = ChatEvent.AssistantEvent(parts = listOf(ChatEvent.AssistantEvent.Part.TextPart("Hi there")))
        val result = formatter.formatTurn(event)
        assertEquals("Hi there<|im_end|>\n", result)
    }

    @Test
    fun `assistant event with no text parts emits only endOfTurn`() {
        val formatter = PromptFormatter(profileWith(PromptFormats.CHAT_ML))
        val event = ChatEvent.AssistantEvent(parts = emptyList())
        val result = formatter.formatTurn(event)
        assertEquals("<|im_end|>\n", result)
    }

    // ── Llama3 format ────────────────────────────────────────────────────────

    @Test
    fun `Llama3 user event wraps with correct header tokens`() {
        val formatter = PromptFormatter(profileWith(PromptFormats.LLAMA_3))
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = false))
        assertEquals(
            "<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
            result
        )
    }

    // ── Nemotron format ──────────────────────────────────────────────────────

    @Test
    fun `Nemotron system event uses extra_id_0 sentinel`() {
        val formatter = PromptFormatter(profileWith(PromptFormats.NEMOTRON))
        val result = formatter.formatTurn(ChatEvent.SystemEvent("You are helpful."))
        assertEquals("<extra_id_0>System\nYou are helpful.\n", result)
    }

    @Test
    fun `Nemotron user event uses extra_id_1 sentinel`() {
        val formatter = PromptFormatter(profileWith(PromptFormats.NEMOTRON))
        val result = formatter.formatTurn(ChatEvent.UserEvent("Hello", think = false))
        assertEquals("<extra_id_1>User\nHello\n<extra_id_1>Assistant\n", result)
    }
}
