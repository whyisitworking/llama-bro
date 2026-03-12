package com.suhel.llamabro.sdk.api

import com.suhel.llamabro.sdk.schema.Message

/**
 * A low-level inference session wrapping a single llama context. This interface gives
 * direct, synchronous access to the underlying prompt/generate cycle for advanced use cases.
 *
 * **Threading:** All methods are blocking and must be called from a background thread.
 * For coroutine-native streaming, use [createChatSession] instead.
 *
 * **Lifecycle:** Close this session when done. The underlying llama context (and its KV
 * cache memory) is freed on [close].
 */
interface LlamaSession : AutoCloseable {

    /**
     * Stages a message's tokens into the context without running generation.
     * Must be followed by one or more [generate] calls to produce output.
     *
     * **Blocking.** Call on a background thread.
     */
    fun prompt(message: Message)

    /**
     * Generates and returns the next valid text piece (one or more raw tokens assembled
     * into a valid UTF-8 sequence). Call repeatedly until `null` to stream the full response.
     *
     * Returns `null` when:
     * - The model emits an end-of-generation (EOS) token
     * - The context overflows and cannot be recovered (for [OverflowStrategy.Halt])
     *
     * **Blocking.** Call on a background thread.
     */
    fun generate(): String?

    /**
     * Clears the entire context memory (KV cache) and resets to a blank state.
     * The system prompt is **not** preserved — use [ChatSession.reset] for that.
     */
    fun clear()

    /**
     * Creates a [ChatSession] that wraps this raw session with a higher-level,
     * coroutine-native conversation API including history tracking and streaming.
     */
    fun createChatSession(config: ChatSessionConfig = ChatSessionConfig()): ChatSession
}

/**
 * Configuration for a [LlamaSession].
 *
 * @param contextSize      KV cache size in tokens. Larger = more conversation memory,
 *                         but uses significantly more RAM. Default: 4096.
 * @param overflowStrategy How to behave when the context fills up. Default: [OverflowStrategy.RollingWindow].
 * @param inferenceConfig  Sampling parameters controlling output creativity and coherence.
 * @param decodeConfig     Low-level batch and buffer tuning. Safe to leave as defaults.
 */
data class SessionConfig(
    val contextSize: Int = 4096,
    val overflowStrategy: OverflowStrategy = OverflowStrategy.RollingWindow(),
    val inferenceConfig: InferenceConfig = InferenceConfig(),
    val decodeConfig: DecodeConfig = DecodeConfig(),
)

/**
 * Sampling parameters that control how the model picks each token.
 * Defaults reflect **community best practices for general-purpose chat**.
 *
 * Sampler chain order (mirrors llama.cpp defaults): topK → topP → minP → repeatPenalty → temperature
 *
 * @param temperature   Randomness/creativity. `0.0` = greedy (deterministic). Default: 0.8.
 * @param repeatPenalty Penalises repeating recent tokens. `1.0` = no effect. Default: 1.1.
 * @param minP          Dynamic probability floor relative to the top token.
 *                      Recommended filter — superior to topP for most tasks.
 *                      `null` = disabled. Default: 0.1.
 * @param topP          Nucleus sampling threshold. Use minP **or** topP, not both.
 *                      `null` = disabled (recommended). Default: null.
 * @param topK          Hard token count cap before other filters. The community recommends
 *                      disabling this in favour of minP/topP. `null` = disabled. Default: null.
 * @param maxNewTokens  Hard cap on generated text pieces per [LlamaSession.generate] loop.
 *                      Guards against models that never emit EOS. Default: 2048.
 * @param seed          RNG seed. `-1` = random (non-deterministic). Default: -1.
 */
data class InferenceConfig(
    val temperature: Float = 0.8f,
    val repeatPenalty: Float = 1.1f,
    val minP: Float? = 0.1f,
    val topP: Float? = null,
    val topK: Int? = null,
    val maxNewTokens: Int = 2048,
    val seed: Int = -1,
)
