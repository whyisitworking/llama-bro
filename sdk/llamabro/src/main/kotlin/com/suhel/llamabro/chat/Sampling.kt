package com.suhel.llamabro.chat

/**
 * Per-request sampling (llama-server's field names, tools/server/server-schema.cpp). null = the GGUF's
 * `general.sampling.*` default, else llama-server's (D18, F17).
 *
 * @property repeatLastN must be ≥ 0 per request (F14); to cover the whole context, pass the context size.
 * @property samplers the sampler chain order, e.g. `listOf("top_k", "top_p", "min_p", "temperature")` (F15).
 * @property maxTokens `max_tokens`; null = until the model stops or the context is full.
 */
public data class Sampling(
    public val temperature: Float? = null,
    public val topK: Int? = null,
    public val topP: Float? = null,
    public val minP: Float? = null,
    public val presencePenalty: Float? = null,
    public val frequencyPenalty: Float? = null,
    public val repeatPenalty: Float? = null,
    public val repeatLastN: Int? = null,
    public val seed: Long? = null,
    public val samplers: List<String>? = null,
    public val maxTokens: Int? = null,
)
