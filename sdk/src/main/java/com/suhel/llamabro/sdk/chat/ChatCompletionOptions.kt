package com.suhel.llamabro.sdk.chat

/**
 * Request parameters for a chat completion, mirroring the OpenAI Chat Completion API.
 *
 * @param temperature Sampling temperature (0.0 = greedy, higher = more random).
 * @param topP Nucleus sampling threshold.
 * @param topK Top-K sampling (0 = disabled).
 * @param minP Minimum probability threshold.
 * @param repeatPenalty Repetition penalty factor.
 * @param frequencyPenalty Frequency-based penalty.
 * @param presencePenalty Presence-based penalty.
 * @param seed Random seed for reproducibility (-1 = random).
 * @param enableThinking Whether to enable reasoning/thinking for this request.
 *   When enabled, models that support thinking will emit reasoning content separately.
 */
data class ChatCompletionOptions(
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val minP: Float? = null,
    val repeatPenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val seed: Int? = null,
    val enableThinking: Boolean = false,
)
