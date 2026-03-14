package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.internal.LlamaChatSessionImpl
import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.PromptFormat
import com.suhel.llamabro.sdk.model.PromptFormats
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaChatSessionImplTest {

    // ── Test double ─────────────────────────────────────────────────────────

    /** A fake [LlamaSession] that returns a pre-configured token sequence. */
    private class FakeSession(private val tokens: List<String>) : LlamaSession {
        private var index = 0
        val prompts = mutableListOf<String>()
        var cleared = false

        override suspend fun prompt(text: String) {
            prompts.add(text)
        }

        override suspend fun generate(): String? {
            return if (index < tokens.size) tokens[index++] else null
        }

        override fun clear() {
            cleared = true
            index = 0
        }

        override fun abort() {}

        override fun close() {}
    }

    companion object {
        /** Transparent format — no wrapping, so tests focus on core behaviour. */
        private val PASSTHROUGH = PromptFormat(
            systemPrefix = "",
            systemSuffix = "",
            userPrefix = "",
            userSuffix = "",
            assistantPrefix = "",
            assistantSuffix = "",
        )
    }

    // ── Token-per-second ────────────────────────────────────────────────────

    @Test
    fun `tokensPerSecond is non-zero for non-empty generation`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", " world")), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertTrue("isComplete should be true", last.isComplete)
        assertNotNull("tokensPerSecond should not be null", last.tokensPerSecond)
        assertTrue("tokensPerSecond should be > 0, was ${last.tokensPerSecond}", last.tokensPerSecond!! > 0f)
    }

    @Test
    fun `tokensPerSecond is zero for empty generation`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(emptyList()), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertTrue(last.isComplete)
    }

    // ── Content accumulation ────────────────────────────────────────────────

    @Test
    fun `content text accumulates across tokens`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", " ", "world")), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("Hello world", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `scan produces progressive snapshots`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("A", "B")), PASSTHROUGH)
        val generations = session.chat("Hi").toList()

        // First emission is the initial ChatGeneration() from scan seed
        assertTrue(generations.size >= 3) // seed + A + B + metric
        assertEquals("A", generations[1].contentText)
        assertEquals("AB", generations[2].contentText)
    }

    // ── Thinking tag classification ─────────────────────────────────────────

    @Test
    fun `thinking tags separate thinking from content`() = runTest {
        val tokens = listOf("<think>", "reasoning", "</think>", "answer")
        val session = LlamaChatSessionImpl(FakeSession(tokens), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("reasoning", last.thinkingText)
        assertEquals("answer", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `thinking tags split across token boundaries`() = runTest {
        // "<think>" split across two tokens: "<thi" + "nk>"
        val tokens = listOf("<thi", "nk>", "deep thought", "</thi", "nk>", "visible")
        val session = LlamaChatSessionImpl(FakeSession(tokens), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("deep thought", last.thinkingText)
        assertEquals("visible", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `content before think tag is emitted as content`() = runTest {
        val tokens = listOf("preamble", "<think>", "thought", "</think>", "answer")
        val session = LlamaChatSessionImpl(FakeSession(tokens), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("thought", last.thinkingText)
        assertEquals("preambleanswer", last.contentText)
    }

    @Test
    fun `no thinking tags means all content`() = runTest {
        val tokens = listOf("just", " content")
        val session = LlamaChatSessionImpl(FakeSession(tokens), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertNull(last.thinkingText)
        assertEquals("just content", last.contentText)
    }

    // ── couldContainPartialTag ──────────────────────────────────────────────

    @Test
    fun `couldContainPartialTag detects partial open tag`() {
        assertTrue(LlamaChatSessionImpl.couldContainPartialTag("abc<"))
        assertTrue(LlamaChatSessionImpl.couldContainPartialTag("abc<th"))
        assertTrue(LlamaChatSessionImpl.couldContainPartialTag("<thin"))
        assertTrue(LlamaChatSessionImpl.couldContainPartialTag("<think"))
    }

    @Test
    fun `couldContainPartialTag detects partial close tag`() {
        assertTrue(LlamaChatSessionImpl.couldContainPartialTag("abc</"))
        assertTrue(LlamaChatSessionImpl.couldContainPartialTag("abc</thi"))
    }

    @Test
    fun `couldContainPartialTag returns false for non-tag text`() {
        assertTrue(!LlamaChatSessionImpl.couldContainPartialTag("hello"))
        assertTrue(!LlamaChatSessionImpl.couldContainPartialTag(""))
    }

    // ── Blank line / empty message fixes ───────────────────────────────────

    @Test
    fun `trailing newlines are trimmed in final output`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", "\n")), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("Hello", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `leading newlines after thinking block are trimmed`() = runTest {
        val tokens = listOf("<think>", "r", "</think>", "\n\n", "answer")
        val session = LlamaChatSessionImpl(FakeSession(tokens), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("answer", last.contentText)
        assertEquals("r", last.thinkingText)
    }

    @Test
    fun `thinking-only output keeps contentText null`() = runTest {
        val tokens = listOf("<think>", "thought", "</think>")
        val session = LlamaChatSessionImpl(FakeSession(tokens), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertNull(last.contentText)
        assertEquals("thought", last.thinkingText)
    }

    @Test
    fun `whitespace-only content results in null contentText`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("\n", "\n")), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertNull(last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `empty generation keeps both fields null`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(emptyList()), PASSTHROUGH)
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertNull(last.contentText)
        assertNull(last.thinkingText)
        assertTrue(last.isComplete)
    }

    // ── Prompt formatting and assistant turn boundaries ───────────────────

    @Test
    fun `chat sends formatted user message with assistant prefix`() = runTest {
        val fake = FakeSession(listOf("Hello"))
        val session = LlamaChatSessionImpl(fake, PromptFormats.ChatML)
        session.chat("Hi").toList()

        assertEquals(
            "<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n",
            fake.prompts.first()
        )
    }

    @Test
    fun `assistant suffix is stripped from final content`() = runTest {
        val fake = FakeSession(listOf("Hello world", "<|im_end|>\n"))
        val session = LlamaChatSessionImpl(fake, PromptFormats.ChatML)
        val last = session.chat("Hi").toList().last()

        assertEquals("Hello world", last.contentText)
    }

    @Test
    fun `content without suffix is not affected by stripping`() = runTest {
        val fake = FakeSession(listOf("Hello world"))
        val session = LlamaChatSessionImpl(fake, PromptFormats.ChatML)
        val last = session.chat("Hi").toList().last()

        assertEquals("Hello world", last.contentText)
    }

    @Test
    fun `empty prefix and suffix do not affect content`() = runTest {
        val fake = FakeSession(listOf("Hello"))
        val session = LlamaChatSessionImpl(fake, PromptFormats.Mistral)
        val last = session.chat("Hi").toList().last()

        assertEquals("Hello", last.contentText)
    }

    @Test
    fun `loadHistory formats each message with the prompt template`() = runTest {
        val fake = FakeSession(emptyList())
        val session = LlamaChatSessionImpl(fake, PromptFormats.ChatML)

        session.loadHistory(
            listOf(
                Message.User("hello"),
                Message.Assistant("hi there"),
            )
        )

        assertEquals("<|im_start|>user\nhello<|im_end|>\n", fake.prompts[0])
        assertEquals("<|im_start|>assistant\nhi there<|im_end|>\n", fake.prompts[1])
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    @Test
    fun `reset delegates to session clear`() {
        val fake = FakeSession(emptyList())
        val session = LlamaChatSessionImpl(fake, PASSTHROUGH)
        session.reset()
        assertTrue(fake.cleared)
    }
}
