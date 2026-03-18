package com.suhel.llamabro.sdk.util

import com.suhel.llamabro.sdk.internal.Prompter
import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.PromptFormat
import com.suhel.llamabro.sdk.model.PromptFormats
import org.junit.Assert.assertEquals
import org.junit.Test

class PrompterTest {

    // ── ChatML ──────────────────────────────────────────────────────────────

    @Test
    fun `ChatML wraps user message correctly`() {
        val fmt = Prompter(PromptFormats.ChatML)
        assertEquals(
            "<|im_start|>user\nHello<|im_end|>",
            fmt.format(Message.User("Hello"))
        )
    }

    @Test
    fun `ChatML wraps assistant message correctly`() {
        val fmt = Prompter(PromptFormats.ChatML)
        assertEquals(
            "<|im_start|>assistant\nHi there<|im_end|>",
            fmt.format(Message.Assistant("Hi there"))
        )
    }

    @Test
    fun `ChatML turn lifecycle produces symmetric open and close`() {
        val fmt = Prompter(PromptFormats.ChatML)
        assertEquals("<|im_start|>assistant\n", fmt.assistantStart())
        assertEquals("<|im_end|>", fmt.assistantEnd())
        // assistantStart() + content + assistantEnd() == assistant(content)
        val content = "Hello world"
        assertEquals(
            fmt.assistant(content),
            fmt.assistantStart() + content + fmt.assistantEnd()
        )
    }

    @Test
    fun `ChatML shouldAddSpecial is true when BOS is null`() {
        val fmt = Prompter(PromptFormats.ChatML)
        assertEquals(true, fmt.shouldAddSpecial())
    }

    // ── Llama3 ──────────────────────────────────────────────────────────────

    @Test
    fun `Llama3 includes BOS in initialization`() {
        val fmt = Prompter(PromptFormats.Llama3)
        val prompt = fmt.system("Be helpful")
        assertEquals(
            "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nBe helpful<|eot_id|>",
            prompt
        )
    }

    @Test
    fun `Llama3 wraps user message correctly`() {
        val fmt = Prompter(PromptFormats.Llama3)
        assertEquals(
            "<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>",
            fmt.format(Message.User("Hello"))
        )
    }

    @Test
    fun `Llama3 turn lifecycle produces symmetric open and close`() {
        val fmt = Prompter(PromptFormats.Llama3)
        assertEquals("<|start_header_id|>assistant<|end_header_id|>\n\n", fmt.assistantStart())
        assertEquals("<|eot_id|>", fmt.assistantEnd())
        val content = "Hello"
        assertEquals(
            fmt.assistant(content),
            fmt.assistantStart() + content + fmt.assistantEnd()
        )
    }

    @Test
    fun `Llama3 shouldAddSpecial is false when BOS is provided`() {
        val fmt = Prompter(PromptFormats.Llama3)
        assertEquals(false, fmt.shouldAddSpecial())
    }

    // ── Mistral ─────────────────────────────────────────────────────────────

    @Test
    fun `Mistral includes BOS and EOS`() {
        val fmt = Prompter(PromptFormats.Mistral)
        assertEquals("<s>", fmt.bos())
        assertEquals("</s>", fmt.eos())

        assertEquals(
            "Response</s>",
            fmt.format(Message.Assistant("Response"))
        )
    }

    @Test
    fun `Mistral turn lifecycle with empty prefix and suffix`() {
        val fmt = Prompter(PromptFormats.Mistral)
        assertEquals("", fmt.assistantStart())
        assertEquals("</s>", fmt.assistantEnd())
        val content = "Hello"
        assertEquals(
            fmt.assistant(content),
            fmt.assistantStart() + content + fmt.assistantEnd()
        )
    }

    // ── Gemma3 ──────────────────────────────────────────────────────────────

    @Test
    fun `Gemma3 includes BOS`() {
        val fmt = Prompter(PromptFormats.Gemma3)
        assertEquals("<bos>", fmt.bos())
    }

    @Test
    fun `Gemma3 turn lifecycle produces symmetric open and close`() {
        val fmt = Prompter(PromptFormats.Gemma3)
        assertEquals("\n<start_of_turn>model\n", fmt.assistantStart())
        assertEquals("<end_of_turn>", fmt.assistantEnd())
        val content = "Hello"
        assertEquals(
            fmt.assistant(content),
            fmt.assistantStart() + content + fmt.assistantEnd()
        )
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
        val fmt = Prompter(custom)
        assertEquals("[BOS]", fmt.bos())
        assertEquals("[U]hi[/U]", fmt.format(Message.User("hi")))
        assertEquals("[A]ok[/A][EOS]", fmt.format(Message.Assistant("ok")))
    }

    @Test
    fun `shouldAddSpecial depends only on bos, not eos`() {
        val eosOnly = PromptFormat(
            bos = null, eos = "[EOS]",
            systemPrefix = "", systemSuffix = "",
            userPrefix = "", userSuffix = "",
            assistantPrefix = "", assistantSuffix = "",
        )
        // BOS is null, so tokenizer should add its native BOS regardless of eos
        assertEquals(true, Prompter(eosOnly).shouldAddSpecial())
    }

    @Test
    fun `assistant message with thinking block`() {
        val fmt = Prompter(PromptFormats.ChatML)
        assertEquals(
            "<|im_start|>assistant\n<think>\nreasoning</think>\nanswer<|im_end|>",
            fmt.assistant("answer", thinking = "reasoning")
        )
    }
}
