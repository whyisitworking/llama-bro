package com.suhel.llamabro.sdk.util

import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.PromptFormat
import com.suhel.llamabro.sdk.model.PromptFormats
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptFormatterTest {

    // ── ChatML ──────────────────────────────────────────────────────────────

    @Test
    fun `ChatML wraps user message correctly`() {
        val fmt = PromptFormatter(PromptFormats.ChatML)
        assertEquals(
            "<|im_start|>user\nHello<|im_end|>\n",
            fmt.format(Message.User("Hello"))
        )
    }

    @Test
    fun `ChatML wraps assistant message correctly`() {
        val fmt = PromptFormatter(PromptFormats.ChatML)
        assertEquals(
            "<|im_start|>assistant\nHi there<|im_end|>\n",
            fmt.format(Message.Assistant("Hi there"))
        )
    }

    @Test
    fun `ChatML wraps system message correctly`() {
        val fmt = PromptFormatter(PromptFormats.ChatML)
        assertEquals(
            "<|im_start|>system\nYou are helpful<|im_end|>\n",
            fmt.system("You are helpful")
        )
    }

    // ── Llama3 ──────────────────────────────────────────────────────────────

    @Test
    fun `Llama3 wraps user message correctly`() {
        val fmt = PromptFormatter(PromptFormats.Llama3)
        assertEquals(
            "<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>",
            fmt.format(Message.User("Hello"))
        )
    }

    @Test
    fun `Llama3 wraps system message correctly`() {
        val fmt = PromptFormatter(PromptFormats.Llama3)
        assertEquals(
            "<|start_header_id|>system<|end_header_id|>\n\nBe concise<|eot_id|>",
            fmt.system("Be concise")
        )
    }

    // ── Gemma3 ──────────────────────────────────────────────────────────────

    @Test
    fun `Gemma3 wraps user message correctly`() {
        val fmt = PromptFormatter(PromptFormats.Gemma3)
        assertEquals(
            "<start_of_turn>user\nHello<end_of_turn>\n",
            fmt.format(Message.User("Hello"))
        )
    }

    @Test
    fun `Gemma3 uses model role for assistant`() {
        val fmt = PromptFormatter(PromptFormats.Gemma3)
        assertEquals(
            "<start_of_turn>model\nResponse<end_of_turn>\n",
            fmt.format(Message.Assistant("Response"))
        )
    }

    // ── Mistral ─────────────────────────────────────────────────────────────

    @Test
    fun `Mistral wraps user message correctly`() {
        val fmt = PromptFormatter(PromptFormats.Mistral)
        assertEquals(
            "[INST] Hello [/INST]",
            fmt.format(Message.User("Hello"))
        )
    }

    @Test
    fun `Mistral assistant has no prefix or suffix`() {
        val fmt = PromptFormatter(PromptFormats.Mistral)
        assertEquals("Response", fmt.format(Message.Assistant("Response")))
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    fun `empty content is valid`() {
        val fmt = PromptFormatter(PromptFormats.ChatML)
        assertEquals(
            "<|im_start|>user\n<|im_end|>\n",
            fmt.format(Message.User(""))
        )
    }

    @Test
    fun `custom prompt format`() {
        val custom = PromptFormat(
            systemPrefix = "[SYS]", systemSuffix = "[/SYS]",
            userPrefix = "[U]", userSuffix = "[/U]",
            assistantPrefix = "[A]", assistantSuffix = "[/A]",
        )
        val fmt = PromptFormatter(custom)
        assertEquals("[U]hi[/U]", fmt.format(Message.User("hi")))
        assertEquals("[A]ok[/A]", fmt.format(Message.Assistant("ok")))
        assertEquals("[SYS]sys[/SYS]", fmt.system("sys"))
    }
}
