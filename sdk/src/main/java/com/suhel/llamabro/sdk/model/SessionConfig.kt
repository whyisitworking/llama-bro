package com.suhel.llamabro.sdk.model

/**
 * Configuration for creating a [com.suhel.llamabro.sdk.LlamaSession].
 *
 * This class bundles together the context size, sampling behavior, and 
 * resource management strategies for a single inference session.
 *
 * @property contextSize      The total number of tokens (system prompt + history + generation) 
 *                            that can be held in memory. Larger values consume more RAM.
 * @property overflowStrategy How the session reacts when the [contextSize] is reached. 
 *                            Defaults to [OverflowStrategy.RollingWindow].
 * @property inferenceConfig  Parameters controlling token selection (sampling) like 
 *                            temperature and penalties.
 * @property decodeConfig     Low-level tuning for the llama.cpp compute loop.
 * @property seed             The random seed for deterministic generation. Use -1 
 *                            for a random seed on every run.
 */
data class SessionConfig(
    val contextSize: Int = 4096,
    val overflowStrategy: OverflowStrategy = OverflowStrategy.RollingWindow(),
    val inferenceConfig: InferenceConfig = InferenceConfig(),
    val decodeConfig: DecodeConfig = DecodeConfig(),
    val seed: Int = -1,
)
