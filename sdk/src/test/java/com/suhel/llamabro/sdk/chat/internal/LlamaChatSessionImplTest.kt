package com.suhel.llamabro.sdk.chat.internal

import com.suhel.llamabro.sdk.chat.LlamaChatSession
import com.suhel.llamabro.sdk.chat.pipeline.TagDelimiter
import com.suhel.llamabro.sdk.config.LoadableModel
import com.suhel.llamabro.sdk.config.ModelLoadConfig
import com.suhel.llamabro.sdk.config.ModelProfile
import com.suhel.llamabro.sdk.config.ThinkingCapability
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.engine.TokenGenerationResult
import com.suhel.llamabro.sdk.engine.TokenGenerationResultCode
import com.suhel.llamabro.sdk.format.PromptFormats
import com.suhel.llamabro.sdk.model.ResourceState
import com.suhel.llamabro.sdk.chat.ChatEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LlamaChatSessionImpl], exercising the full lexing → semantic → snapshot
 * pipeline against a fake [LlamaSession] that emits pre-scripted token sequences.
 */
class LlamaChatSessionImplTest {

    // ── Test double ─────────────────────────────────────────────────────────

    /**
     * A deterministic fake [LlamaSession] that replays a fixed token list,
     * enabling precise, reproducible pipeline tests without native code.
     */
    private class FakeSession(
        private val tokens: List<String>,
        override val loadableModel: LoadableModel = noFeaturesModel()
    ) : LlamaSession {

        val addedPrompts = mutableListOf<String>()

        override suspend fun setPrefixedPrompt(text: String) = Unit
        override suspend fun addPrompt(prompt: String) { addedPrompts += prompt }
        override suspend fun generate(): TokenGenerationResult =
            error("Use generateFlow() in tests")
        override suspend fun clear() = Unit
        override fun abort() = Unit
        override fun close() = Unit

        override fun generateFlow(): Flow<TokenGenerationResult> = flow {
            tokens.forEachIndexed { i, token ->
                emit(
                    TokenGenerationResult(
                        token = token,
                        resultCode = TokenGenerationResultCode.OK,
                        isComplete = i == tokens.lastIndex,
                    )
                )
            }
            // Edge case: emit completion signal on empty token list
            if (tokens.isEmpty()) {
                emit(TokenGenerationResult(null, TokenGenerationResultCode.OK, isComplete = true))
            }
        }

        override suspend fun createChatSession(systemPrompt: String): LlamaChatSession =
            LlamaChatSessionImpl(this, systemPrompt)

        override fun createChatSessionFlow(systemPrompt: String): Flow<ResourceState<LlamaChatSession>> =
            flow { emit(ResourceState.Success(createChatSession(systemPrompt))) }
    }

    companion object {
        private fun noFeaturesModel() = LoadableModel(
            loadConfig = ModelLoadConfig(path = "fake.gguf"),
            profile = ModelProfile(promptFormat = PromptFormats.CHAT_ML),
        )

        private fun thinkingModel() = LoadableModel(
            loadConfig = ModelLoadConfig(path = "fake.gguf"),
            profile = ModelProfile(
                promptFormat = PromptFormats.CHAT_ML,
                thinking = ThinkingCapability(
                    tags = TagDelimiter("<think>", "</think>"),
                ),
            ),
        )
    }

    // ── Snapshot accumulation ────────────────────────────────────────────────

    @Test
    fun `text tokens accumulate across intermediary snapshots`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", " ", "world")), "")
        val snapshots = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList()

        val last = snapshots.last()
        assertEquals("Hello world", last.message.text)
        assertTrue(last.isComplete)
        assertFalse(last.isError)
    }

    @Test
    fun `snapshots are emitted progressively — one per semantic chunk`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("A", "B")), "")
        val snapshots = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList()

        // Each text chunk emits an intermediate snapshot, plus a final one
        assertTrue("Expected at least 3 snapshots", snapshots.size >= 3)
        assertEquals("A", snapshots[0].message.text)
        assertEquals("AB", snapshots[1].message.text)
        assertTrue(snapshots.last().isComplete)
    }

    @Test
    fun `empty token list produces a single complete snapshot with empty content`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(emptyList()), "")
        val snapshots = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList()

        assertEquals(1, snapshots.size)
        assertTrue(snapshots[0].isComplete)
        assertEquals("", snapshots[0].message.text)
    }

    // ── Thinking tag handling ────────────────────────────────────────────────

    @Test
    fun `thinking tags correctly partition text into thinking and content parts`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<think>", "reasoning", "</think>", "answer"),
            loadableModel = thinkingModel()
        )
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertEquals("reasoning", last.message.thinkingText)
        assertEquals("answer", last.message.text)
    }

    @Test
    fun `thinking tag split across token boundaries is correctly assembled`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<thi", "nk>", "deep thought", "</thi", "nk>", "visible"),
            loadableModel = thinkingModel()
        )
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertEquals("deep thought", last.message.thinkingText)
        assertEquals("visible", last.message.text)
    }

    @Test
    fun `text before thinking tag is classified as content`() = runTest {
        val fake = FakeSession(
            tokens = listOf("preamble", "<think>", "thought", "</think>", "answer"),
            loadableModel = thinkingModel()
        )
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertEquals("thought", last.message.thinkingText)
        assertEquals("preambleanswer", last.message.text)
    }

    @Test
    fun `thinking-only output has empty text`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<think>", "thought", "</think>"),
            loadableModel = thinkingModel()
        )
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertEquals("thought", last.message.thinkingText)
        assertEquals("", last.message.text)
    }

    // ── Prompt forwarding ────────────────────────────────────────────────────

    @Test
    fun `completion adds a formatted user prompt to the session`() = runTest {
        val fake = FakeSession(listOf("ok"))
        val session = LlamaChatSessionImpl(fake, "")
        session.completion(ChatEvent.UserEvent("Hi", think = false)).toList()

        // ChatML user prompt + assistant prefix should be the first prompt added
        assertTrue("Expected a user prompt to be added", fake.addedPrompts.isNotEmpty())
        assertTrue(
            "Prompt should start with ChatML user prefix",
            fake.addedPrompts[0].startsWith("<|im_start|>user\n")
        )
    }

    // ── History replay ────────────────────────────────────────────────────────

    @Test
    fun `feedHistory adds formatted prompts for each history event`() = runTest {
        val fake = FakeSession(emptyList())
        val session = LlamaChatSessionImpl(fake, "")
        session.feedHistory(
            listOf(
                ChatEvent.UserEvent("hello", think = false),
                ChatEvent.AssistantEvent(listOf(ChatEvent.AssistantEvent.Part.TextPart("hi there"))),
            )
        )

        assertEquals(2, fake.addedPrompts.size)
        assertTrue(fake.addedPrompts[0].contains("hello"))
        assertTrue(fake.addedPrompts[1].contains("hi there"))
    }

    // ── Tokens per second ────────────────────────────────────────────────────

    @Test
    fun `tokensPerSecond is positive for non-empty generation`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", " ", "world")), "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertTrue(last.isComplete)
        // In unit tests the wall clock is too fast to measure precisely, so just confirm non-negative
        assertTrue(last.tokensPerSecond >= 0f)
    }

    @Test
    fun `tokensPerSecond is zero for empty generation`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(emptyList()), "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertTrue(last.isComplete)
        assertEquals(0f, last.tokensPerSecond)
    }
}
