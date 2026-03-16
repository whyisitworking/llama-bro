package com.suhel.llamabro.sdk.util

import com.suhel.llamabro.sdk.internal.PromptFormatter
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
        // ChatML doesn't have a BOS/EOS in our config, so it should be same as before
        assertEquals(
            "<|im_start|>assistant\nHi there<|im_end|>\n",
            fmt.format(Message.Assistant("Hi there"))
        )
    }

    // ── Llama3 ──────────────────────────────────────────────────────────────

    @Test
    fun `Llama3 includes BOS in initialization`() {
        val fmt = PromptFormatter(PromptFormats.Llama3)
        val prompt = fmt.bos() + fmt.system("Be helpful")
        assertEquals(
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nBe helpful<|eot_id|>",
            prompt
        )
    }

    @Test
    fun `Llama3 wraps user message correctly`() {
        val fmt = PromptFormatter(PromptFormats.Llama3)
        assertEquals(
            "<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>",
            fmt.format(Message.User("Hello"))
        )
    }

    // ── Mistral ─────────────────────────────────────────────────────────────

    @Test
    fun `Mistral includes BOS and EOS`() {
        val fmt = PromptFormatter(PromptFormats.Mistral)
        assertEquals("<s>", fmt.bos())
        assertEquals("</s>", fmt.eos())
        
        assertEquals(
            "Response</s>",
            fmt.format(Message.Assistant("Response"))
        )
    }

    // ── Gemma3 ──────────────────────────────────────────────────────────────

    @Test
    fun `Gemma3 includes BOS`() {
        val fmt = PromptFormatter(PromptFormats.Gemma3)
        assertEquals("<bos>", fmt.bos())
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    fun `custom prompt format with BOS and EOS`() {
        val custom = PromptFormat(
            bos = "[BOS]",
            eos = "[EOS]",
            systemPrefix = "[SYS]", systemSuffix = "[/SYS]",
            userPrefix = "[U]", userSuffix = "[/U]",
            assistantPrefix = "[A]", assistantSuffix = "[/A]",
        )
        val fmt = PromptFormatter(custom)
        assertEquals("[BOS]", fmt.bos())
        assertEquals("[U]hi[/U]", fmt.format(Message.User("hi")))
        assertEquals("[A]ok[/A][EOS]", fmt.format(Message.Assistant("ok")))
    }
}
