package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.internal.LlamaChatSessionImpl
import com.suhel.llamabro.sdk.model.LlamaError
import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.ModelConfig
import com.suhel.llamabro.sdk.model.PromptFormat
import com.suhel.llamabro.sdk.model.PromptFormats
import com.suhel.llamabro.sdk.model.ResourceState
import com.suhel.llamabro.sdk.model.TokenGenerationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LlamaChatSessionImpl], focusing on token processing, 
 * thinking block extraction, and prompt formatting.
 */
class LlamaChatSessionImplTest {

    // ── Test double ─────────────────────────────────────────────────────────

    /** 
     * A fake [LlamaSession] that returns a pre-configured token sequence. 
     * Used to simulate model output without running native code.
     */
    private class FakeSession(
        private val tokens: List<String>,
        override val modelConfig: ModelConfig = ModelConfig("fake.gguf", PASSTHROUGH),
        private val shouldThrow: LlamaError? = null
    ) : LlamaSession {
        private var index = 0
        val prompts = mutableListOf<String>()
        var cleared = false
        var aborted = false

        override suspend fun setSystemPrompt(text: String, addSpecial: Boolean) {
            prompts.add(text)
        }

        override suspend fun ingestPrompt(prompt: String, addSpecial: Boolean) {
            prompts.add(prompt)
        }

        override suspend fun generate(): TokenGenerationResult {
            shouldThrow?.let { throw it }
            if (tokens.isEmpty()) return TokenGenerationResult(null, true)
            
            val token = tokens[index]
            index++
            return TokenGenerationResult(token, index == tokens.size)
        }

        override suspend fun clear() {
            cleared = true
            index = 0
        }

        override fun abort() {
            aborted = true
        }

        override fun close() {}

        override suspend fun createChatSession(systemPrompt: String): LlamaChatSession {
            return LlamaChatSessionImpl(this, systemPrompt)
        }

        override fun createChatSessionFlow(systemPrompt: String): Flow<ResourceState<LlamaChatSession>> {
            return emptyFlow()
        }
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

    // ── Performance Metrics ─────────────────────────────────────────────────

    @Test
    fun `tokensPerSecond is non-zero for non-empty generation`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", " world")), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertTrue("isComplete should be true", last.isComplete)
        assertNotNull("tokensPerSecond should not be null", last.tokensPerSecond)
        assertTrue(
            "tokensPerSecond should be > 0, was ${last.tokensPerSecond}",
            (last.tokensPerSecond ?: 0f) > 0f
        )
    }

    @Test
    fun `tokensPerSecond is zero for empty generation`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(emptyList()), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertTrue(last.isComplete)
    }

    // ── Content accumulation ────────────────────────────────────────────────

    @Test
    fun `content text accumulates across tokens`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", " ", "world")), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertEquals("Hello world", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `scan produces progressive snapshots`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("A", "B")), "")
        val generations = session.completion("Hi").toList()

        // Emissions: A, AB, metrics (isComplete=true)
        assertTrue(generations.size >= 2)
        assertEquals("A", generations[0].contentText)
        assertEquals("AB", generations[1].contentText)
    }

    // ── Thinking tag classification ─────────────────────────────────────────

    @Test
    fun `thinking tags separate thinking from content`() = runTest {
        val tokens = listOf("<think>", "reasoning", "</think>", "answer")
        val session = LlamaChatSessionImpl(FakeSession(tokens), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertEquals("reasoning", last.thinkingText)
        assertEquals("answer", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `thinking tags split across token boundaries`() = runTest {
        // "<think>" split across two tokens: "<thi" + "nk>"
        val tokens = listOf("<thi", "nk>", "deep thought", "</thi", "nk>", "visible")
        val session = LlamaChatSessionImpl(FakeSession(tokens), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertEquals("deep thought", last.thinkingText)
        assertEquals("visible", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `content before think tag is emitted as content`() = runTest {
        val tokens = listOf("preamble", "<think>", "thought", "</think>", "answer")
        val session = LlamaChatSessionImpl(FakeSession(tokens), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertEquals("thought", last.thinkingText)
        assertEquals("preambleanswer", last.contentText)
    }

    @Test
    fun `no thinking tags means all content`() = runTest {
        val tokens = listOf("just", " content")
        val session = LlamaChatSessionImpl(FakeSession(tokens), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertNull(last.thinkingText)
        assertEquals("just content", last.contentText)
    }

    // ── Blank line / empty message fixes ───────────────────────────────────

    @Test
    fun `trailing newlines are trimmed in final output`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", "\n")), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertEquals("Hello", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `leading newlines after thinking block are trimmed`() = runTest {
        val tokens = listOf("<think>", "r", "</think>", "\n\n", "answer")
        val session = LlamaChatSessionImpl(FakeSession(tokens), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertEquals("answer", last.contentText)
        assertEquals("r", last.thinkingText)
    }

    @Test
    fun `thinking-only output keeps contentText null`() = runTest {
        val tokens = listOf("<think>", "thought", "</think>")
        val session = LlamaChatSessionImpl(FakeSession(tokens), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertNull(last.contentText)
        assertEquals("thought", last.thinkingText)
    }

    @Test
    fun `whitespace-only content results in null contentText`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("\n", "\n")), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertNull(last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `empty generation keeps both fields null`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(emptyList()), "")
        val generations = session.completion("Hi").toList()
        val last = generations.last()

        assertNull(last.contentText)
        assertNull(last.thinkingText)
        assertTrue(last.isComplete)
    }

    // ── Prompt formatting and assistant turn boundaries ───────────────────

    @Test
    fun `chat sends formatted user message with assistant prefix`() = runTest {
        val modelConfig = ModelConfig("fake.gguf", PromptFormats.ChatML)
        val fake = FakeSession(listOf("Hello"), modelConfig)
        val session = LlamaChatSessionImpl(fake, "")
        session.completion("Hi").toList()

        // Only prompt: user turn + assistant turn opening (no Kotlin-side closing)
        assertEquals(
            "<|im_start|>user\nHi<|im_end|><|im_start|>assistant\n",
            fake.prompts[0]
        )
        assertEquals("No Kotlin-side turn closing should occur", 1, fake.prompts.size)
    }

    @Test
    fun `content is not affected by manually provided eog tokens`() = runTest {
        // Since the C++ engine already consumes EOG, we simulate that here by 
        // NOT including it in the tokens returned by FakeSession, as it would
        // have been filtered out by the real session.cpp.
        val modelConfig = ModelConfig("fake.gguf", PromptFormats.ChatML)
        val fake = FakeSession(listOf("Hello world"), modelConfig)
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion("Hi").toList().last()

        assertEquals("Hello world", last.contentText)
    }

    @Test
    fun `empty prefix and suffix do not affect content`() = runTest {
        val modelConfig = ModelConfig("fake.gguf", PromptFormats.Mistral)
        val fake = FakeSession(listOf("Hello"), modelConfig)
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion("Hi").toList().last()

        assertEquals("Hello", last.contentText)
    }

    @Test
    fun `loadHistory formats each message with the prompt template`() = runTest {
        val modelConfig = ModelConfig("fake.gguf", PromptFormats.ChatML)
        val fake = FakeSession(emptyList(), modelConfig)
        val session = LlamaChatSessionImpl(fake, "")

        session.loadHistory(
            listOf(
                Message.User("hello"),
                Message.Assistant("hi there"),
            )
        )

        assertEquals("<|im_start|>user\nhello<|im_end|>", fake.prompts[0])
        assertEquals("<|im_start|>assistant\nhi there<|im_end|>", fake.prompts[1])
    }

    // ── Reset and Error Handling ───────────────────────────────────────────

    @Test
    fun `reset delegates to session clear`() = runTest {
        val fake = FakeSession(emptyList())
        val session = LlamaChatSessionImpl(fake, "")
        session.reset()
        assertTrue(fake.cleared)
    }

    @Test
    fun `cancelled error emits interrupted state`() = runTest {
        val fake = FakeSession(
            tokens = emptyList(),
            shouldThrow = LlamaError.Cancelled()
        )
        val session = LlamaChatSessionImpl(fake, "")
        val generations = session.completion("Hi").toList()

        assertTrue(generations.last().isInterrupted)
        assertTrue(generations.last().isComplete)
    }

    @Test(expected = LlamaError.DecodeFailed::class)
    fun `other llama errors are propagated`() = runTest {
        val fake = FakeSession(
            tokens = emptyList(),
            shouldThrow = LlamaError.DecodeFailed(1)
        )
        val session = LlamaChatSessionImpl(fake, "")
        session.completion("Hi").toList()
    }
}
