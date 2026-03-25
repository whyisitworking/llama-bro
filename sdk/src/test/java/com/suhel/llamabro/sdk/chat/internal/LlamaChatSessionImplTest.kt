package com.suhel.llamabro.sdk.chat.internal

import com.suhel.llamabro.sdk.chat.LlamaChatSession
import com.suhel.llamabro.sdk.chat.CompletionResult
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
import com.suhel.llamabro.sdk.toolcall.ToolCall
import com.suhel.llamabro.sdk.toolcall.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LlamaChatSessionImpl], exercising the full lexing -> semantic -> timeline
 * pipeline against a fake [LlamaSession] that emits pre-scripted token sequences.
 */
class LlamaChatSessionImplTest {

    // -- Test double -------------------------------------------------------

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

        override suspend fun createChatSession(
            systemPrompt: String,
            toolCaller: (suspend (List<ToolCall>) -> List<ToolResult>)?,
        ): LlamaChatSession =
            LlamaChatSessionImpl(this, systemPrompt, toolCaller)

        override fun createChatSessionFlow(
            systemPrompt: String,
            toolCaller: (suspend (List<ToolCall>) -> List<ToolResult>)?,
        ): Flow<ResourceState<LlamaChatSession>> =
            flow { emit(ResourceState.Success(createChatSession(systemPrompt, toolCaller))) }
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

        /** Helper: extract text from a CompletionResult's events. */
        private fun List<ChatEvent.AssistantEvent.Part>.text(): String =
            filterIsInstance<ChatEvent.AssistantEvent.Part.TextPart>()
                .joinToString("") { it.content }

        /** Helper: extract thinking text from a CompletionResult's events. */
        private fun List<ChatEvent.AssistantEvent.Part>.thinkingText(): String =
            filterIsInstance<ChatEvent.AssistantEvent.Part.ThinkingPart>()
                .joinToString("") { it.content }

        /** Helper: get events from any CompletionResult variant. */
        private fun CompletionResult.events(): List<ChatEvent.AssistantEvent.Part> = when (this) {
            is CompletionResult.Streaming -> events
            is CompletionResult.Complete -> events
            is CompletionResult.Error -> emptyList()
        }
    }

    // -- Timeline accumulation ---------------------------------------------

    @Test
    fun `text tokens accumulate across intermediary snapshots`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", " ", "world")), "")
        val results = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList()

        val last = results.last()
        assertTrue(last is CompletionResult.Complete)
        assertEquals("Hello world", last.events().text())
    }

    @Test
    fun `snapshots are emitted progressively — one per semantic chunk`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("A", "B")), "")
        val results = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList()

        // Each text chunk emits an intermediate Streaming, plus a final Complete
        assertTrue("Expected at least 3 results", results.size >= 3)
        assertEquals("A", results[0].events().text())
        assertEquals("AB", results[1].events().text())
        assertTrue(results.last() is CompletionResult.Complete)
    }

    @Test
    fun `empty token list produces a single complete result with empty content`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(emptyList()), "")
        val results = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList()

        assertEquals(1, results.size)
        assertTrue(results[0] is CompletionResult.Complete)
        assertEquals("", results[0].events().text())
    }

    // -- Thinking tag handling ---------------------------------------------

    @Test
    fun `thinking tags correctly partition text into thinking and content parts`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<think>", "reasoning", "</think>", "answer"),
            loadableModel = thinkingModel()
        )
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertEquals("reasoning", last.events().thinkingText())
        assertEquals("answer", last.events().text())
    }

    @Test
    fun `thinking tag split across token boundaries is correctly assembled`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<thi", "nk>", "deep thought", "</thi", "nk>", "visible"),
            loadableModel = thinkingModel()
        )
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertEquals("deep thought", last.events().thinkingText())
        assertEquals("visible", last.events().text())
    }

    @Test
    fun `text before thinking tag is classified as content`() = runTest {
        val fake = FakeSession(
            tokens = listOf("preamble", "<think>", "thought", "</think>", "answer"),
            loadableModel = thinkingModel()
        )
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertEquals("thought", last.events().thinkingText())
        assertEquals("preambleanswer", last.events().text())
    }

    @Test
    fun `thinking-only output has empty text`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<think>", "thought", "</think>"),
            loadableModel = thinkingModel()
        )
        val session = LlamaChatSessionImpl(fake, "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertEquals("thought", last.events().thinkingText())
        assertEquals("", last.events().text())
    }

    // -- Prompt forwarding -------------------------------------------------

    @Test
    fun `completion adds a formatted user prompt to the session`() = runTest {
        val fake = FakeSession(listOf("ok"))
        val session = LlamaChatSessionImpl(fake, "")
        session.completion(ChatEvent.UserEvent("Hi", think = false)).toList()

        assertTrue("Expected a user prompt to be added", fake.addedPrompts.isNotEmpty())
        assertTrue(
            "Prompt should start with ChatML user prefix",
            fake.addedPrompts[0].startsWith("<|im_start|>user\n")
        )
    }

    // -- History replay ----------------------------------------------------

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

    // -- Tokens per second -------------------------------------------------

    @Test
    fun `tokensPerSecond is positive for non-empty generation`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(listOf("Hello", " ", "world")), "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertTrue(last is CompletionResult.Complete)
        val complete = last as CompletionResult.Complete
        assertTrue(complete.tokensPerSecond >= 0f)
    }

    @Test
    fun `tokensPerSecond is zero for empty generation`() = runTest {
        val session = LlamaChatSessionImpl(FakeSession(emptyList()), "")
        val last = session.completion(ChatEvent.UserEvent("Hi", think = false)).toList().last()

        assertTrue(last is CompletionResult.Complete)
        val complete = last as CompletionResult.Complete
        assertEquals(0f, complete.tokensPerSecond)
    }
}
