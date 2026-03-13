package com.suhel.llamabro.sdk.model

/**
 * Sampling parameters for token generation.
 *
 * @param temperature   Controls randomness. 0 = greedy (deterministic), higher = more creative. Default: 0.8.
 * @param repeatPenalty Penalises repeated tokens. 1.0 = no penalty. Default: 1.1.
 * @param minP          Min-P sampling threshold. Null to disable. Default: 0.1.
 * @param topP          Top-P (nucleus) sampling threshold. Null to disable. Default: null.
 * @param topK          Top-K sampling. Null to disable. Default: null.
 */
data class InferenceConfig(
    val temperature: Float = 0.8f,
    val repeatPenalty: Float = 1.1f,
    val minP: Float? = 0.1f,
    val topP: Float? = null,
    val topK: Int? = null,
)
