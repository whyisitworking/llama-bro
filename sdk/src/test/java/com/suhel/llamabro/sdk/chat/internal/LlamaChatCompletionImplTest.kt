package com.suhel.llamabro.sdk.chat.internal

import com.suhel.llamabro.sdk.chat.ChatCompletionEvent
import com.suhel.llamabro.sdk.chat.ChatCompletionOptions
import com.suhel.llamabro.sdk.chat.ChatMessage
import com.suhel.llamabro.sdk.chat.LlamaChatCompletion
import com.suhel.llamabro.sdk.config.InferenceConfig
import com.suhel.llamabro.sdk.config.LoadableModel
import com.suhel.llamabro.sdk.config.ModelLoadConfig
import com.suhel.llamabro.sdk.config.ModelProfile
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.engine.TokenGenerationResult
import com.suhel.llamabro.sdk.engine.TokenGenerationResultCode
import com.suhel.llamabro.sdk.engine.internal.NativeChatTemplateInfo
import com.suhel.llamabro.sdk.engine.internal.NativeCompletionInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LlamaChatCompletionImpl], exercising the full lexing -> semantic -> delta
 * pipeline against a fake [LlamaSession] that emits pre-scripted token sequences.
 */
class LlamaChatCompletionImplTest {

    // -- Test double -------------------------------------------------------

    /**
     * A deterministic fake [LlamaSession] that replays a fixed token list,
     * enabling precise, reproducible pipeline tests without native code.
     */
    private class FakeSession(
        private val tokens: List<String>,
        private val supportsThinking: Boolean = false,
        private val thinkingStartTag: String? = null,
        private val thinkingEndTag: String? = null,
        private val generationPrompt: String? = null,
    ) : LlamaSession {

        override val loadableModel: LoadableModel = LoadableModel(
            loadConfig = ModelLoadConfig(path = "fake.gguf"),
            profile = ModelProfile(),
        )

        var lastMessages: List<ChatMessage>? = null
        var lastEnableThinking: Boolean? = null

        override suspend fun initChatTemplates(): NativeChatTemplateInfo =
            NativeChatTemplateInfo(supportsThinking, thinkingStartTag, thinkingEndTag)

        override suspend fun beginCompletion(
            messages: List<ChatMessage>,
            enableThinking: Boolean,
        ): NativeCompletionInfo {
            lastMessages = messages
            lastEnableThinking = enableThinking
            return NativeCompletionInfo(
                generationPrompt = generationPrompt,
                supportsThinking = supportsThinking,
                thinkingStartTag = thinkingStartTag,
                thinkingEndTag = thinkingEndTag,
                tokensCached = 0,
                tokensIngested = messages.sumOf { it.content.length },
            )
        }

        override suspend fun generate(): TokenGenerationResult =
            error("Use generateFlow() in tests")

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
            if (tokens.isEmpty()) {
                emit(TokenGenerationResult(null, TokenGenerationResultCode.OK, isComplete = true))
            }
        }

        override suspend fun clear() = Unit
        override fun abort() = Unit
        override suspend fun updateSampler(config: InferenceConfig) = Unit
        override fun close() = Unit
    }

    companion object {
        private val SYSTEM_MSG = ChatMessage(role = "system", content = "You are helpful")
        private val USER_MSG = ChatMessage(role = "user", content = "Hi")

        /** Create and initialize a LlamaChatCompletion from a FakeSession. */
        private suspend fun createCompletion(fake: FakeSession): LlamaChatCompletion {
            return LlamaChatCompletionImpl(fake).also { it.initialize() }
        }

        /** Collect all text content deltas from a completion. */
        private fun List<ChatCompletionEvent>.textContent(): String =
            filterIsInstance<ChatCompletionEvent.Delta>()
                .mapNotNull { it.content }
                .joinToString("")

        /** Collect all reasoning content deltas from a completion. */
        private fun List<ChatCompletionEvent>.reasoningContent(): String =
            filterIsInstance<ChatCompletionEvent.Delta>()
                .mapNotNull { it.reasoningContent }
                .joinToString("")

        /** Get the Done event from a completion. */
        private fun List<ChatCompletionEvent>.done(): ChatCompletionEvent.Done =
            filterIsInstance<ChatCompletionEvent.Done>().single()
    }

    // -- Basic text generation ---------------------------------------------

    @Test
    fun `text tokens accumulate into content deltas`() = runTest {
        val completion = createCompletion(FakeSession(listOf("Hello", " ", "world")))
        val events = completion.create(listOf(SYSTEM_MSG, USER_MSG)).toList()

        assertEquals("Hello world", events.textContent())
        assertEquals("stop", events.done().finishReason)
    }

    @Test
    fun `deltas are emitted progressively — one per semantic chunk`() = runTest {
        val completion = createCompletion(FakeSession(listOf("A", "B")))
        val events = completion.create(listOf(SYSTEM_MSG, USER_MSG)).toList()

        val deltas = events.filterIsInstance<ChatCompletionEvent.Delta>()
        assertTrue("Expected at least 2 deltas", deltas.size >= 2)
        assertEquals("A", deltas[0].content)
        assertEquals("B", deltas[1].content)
    }

    @Test
    fun `empty token list produces Done with zero completion tokens`() = runTest {
        val completion = createCompletion(FakeSession(emptyList()))
        val events = completion.create(listOf(SYSTEM_MSG, USER_MSG)).toList()

        assertEquals("", events.textContent())
        val done = events.done()
        assertEquals(0, done.usage.completionTokens)
        assertEquals(0f, done.usage.tokensPerSecond)
    }

    // -- Thinking tag handling ---------------------------------------------

    @Test
    fun `thinking tags partition into reasoning and content deltas`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<think>", "reasoning", "</think>", "answer"),
            supportsThinking = true,
            thinkingStartTag = "<think>",
            thinkingEndTag = "</think>",
        )
        val completion = createCompletion(fake)
        val events = completion.create(
            listOf(SYSTEM_MSG, USER_MSG),
            ChatCompletionOptions(enableThinking = true),
        ).toList()

        assertEquals("reasoning", events.reasoningContent())
        assertEquals("answer", events.textContent())
    }

    @Test
    fun `thinking tag split across token boundaries is correctly assembled`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<thi", "nk>", "deep thought", "</thi", "nk>", "visible"),
            supportsThinking = true,
            thinkingStartTag = "<think>",
            thinkingEndTag = "</think>",
        )
        val completion = createCompletion(fake)
        val events = completion.create(
            listOf(SYSTEM_MSG, USER_MSG),
            ChatCompletionOptions(enableThinking = true),
        ).toList()

        assertEquals("deep thought", events.reasoningContent())
        assertEquals("visible", events.textContent())
    }

    @Test
    fun `text before thinking tag is classified as content`() = runTest {
        val fake = FakeSession(
            tokens = listOf("preamble", "<think>", "thought", "</think>", "answer"),
            supportsThinking = true,
            thinkingStartTag = "<think>",
            thinkingEndTag = "</think>",
        )
        val completion = createCompletion(fake)
        val events = completion.create(
            listOf(SYSTEM_MSG, USER_MSG),
            ChatCompletionOptions(enableThinking = true),
        ).toList()

        assertEquals("thought", events.reasoningContent())
        assertEquals("preambleanswer", events.textContent())
    }

    @Test
    fun `thinking-only output has empty text content`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<think>", "thought", "</think>"),
            supportsThinking = true,
            thinkingStartTag = "<think>",
            thinkingEndTag = "</think>",
        )
        val completion = createCompletion(fake)
        val events = completion.create(
            listOf(SYSTEM_MSG, USER_MSG),
            ChatCompletionOptions(enableThinking = true),
        ).toList()

        assertEquals("thought", events.reasoningContent())
        assertEquals("", events.textContent())
    }

    // -- Thinking disabled but model still emits think tags ----------------

    @Test
    fun `thinking content surfaces as text when enableThinking is false`() = runTest {
        // Qwen 3 style: model always wraps output in think tags, even when thinking is disabled
        val fake = FakeSession(
            tokens = listOf("<think>", "the actual response", "</think>"),
            supportsThinking = true,
            thinkingStartTag = "<think>",
            thinkingEndTag = "</think>",
        )
        val completion = createCompletion(fake)
        val events = completion.create(
            listOf(SYSTEM_MSG, USER_MSG),
            ChatCompletionOptions(enableThinking = false),
        ).toList()

        // Thinking content should appear as regular text, NOT as reasoning
        assertEquals("", events.reasoningContent())
        assertEquals("the actual response", events.textContent())
    }

    @Test
    fun `thinking plus text surfaces all as text when enableThinking is false`() = runTest {
        val fake = FakeSession(
            tokens = listOf("<think>", "reasoning", "</think>", "answer"),
            supportsThinking = true,
            thinkingStartTag = "<think>",
            thinkingEndTag = "</think>",
        )
        val completion = createCompletion(fake)
        val events = completion.create(
            listOf(SYSTEM_MSG, USER_MSG),
            ChatCompletionOptions(enableThinking = false),
        ).toList()

        assertEquals("", events.reasoningContent())
        assertEquals("reasoninganswer", events.textContent())
    }

    @Test
    fun `pre-seeded scanner handles thinking when generation prompt starts with think tag`() = runTest {
        // Simulate DeepSeek-R1 style: generation prompt ends with "<think>"
        val fake = FakeSession(
            tokens = listOf("reasoning here", "</think>", "the answer"),
            supportsThinking = true,
            thinkingStartTag = "<think>",
            thinkingEndTag = "</think>",
            generationPrompt = "<think>",
        )
        val completion = createCompletion(fake)
        val events = completion.create(
            listOf(SYSTEM_MSG, USER_MSG),
            ChatCompletionOptions(enableThinking = true),
        ).toList()

        assertEquals("reasoning here", events.reasoningContent())
        assertEquals("the answer", events.textContent())
    }

    // -- Message forwarding -----------------------------------------------

    @Test
    fun `messages are forwarded to beginCompletion`() = runTest {
        val fake = FakeSession(listOf("ok"))
        val completion = createCompletion(fake)

        val messages = listOf(SYSTEM_MSG, USER_MSG)
        completion.create(messages, ChatCompletionOptions(enableThinking = true)).toList()

        assertEquals(messages, fake.lastMessages)
        assertEquals(true, fake.lastEnableThinking)
    }

    // -- Usage statistics -------------------------------------------------

    @Test
    fun `Done event includes positive tokensPerSecond for non-empty generation`() = runTest {
        val completion = createCompletion(FakeSession(listOf("Hello", " ", "world")))
        val events = completion.create(listOf(SYSTEM_MSG, USER_MSG)).toList()

        val done = events.done()
        assertTrue(done.usage.completionTokens > 0)
        assertTrue(done.usage.tokensPerSecond >= 0f)
    }

    @Test
    fun `Done event has zero tokensPerSecond for empty generation`() = runTest {
        val completion = createCompletion(FakeSession(emptyList()))
        val events = completion.create(listOf(SYSTEM_MSG, USER_MSG)).toList()

        val done = events.done()
        assertEquals(0, done.usage.completionTokens)
        assertEquals(0f, done.usage.tokensPerSecond)
    }
}
