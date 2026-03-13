package com.suhel.llamabro.sdk.model

/**
 * Low-level llama.cpp context tuning parameters. The defaults are optimised for
 * typical Android on-device inference. Only adjust these if you have a specific
 * performance profile in mind.
 *
 * @param batchSize             Maximum number of tokens processed in a single llama_decode
 *                              call (n_batch). Larger values speed up prompt ingestion
 *                              (pre-fill) at the cost of peak memory. Default: 2048.
 * @param microBatchSize        Physical GGML compute batch size (n_ubatch). Must be ≤
 *                              [batchSize]. Affects SIMD register utilisation. Default: 512.
 * @param systemPromptReserve   Token buffer reserved at the context boundary when truncating
 *                              long prompts, ensuring the system prompt boundary is never used
 *                              for user content. Default: 100.
 */
data class DecodeConfig(
    val batchSize: Int = 2048,
    val microBatchSize: Int = 512,
    val systemPromptReserve: Int = 100,
) {
    init {
        require(batchSize >= microBatchSize) {
            "batchSize ($batchSize) must be >= microBatchSize ($microBatchSize)"
        }
    }
}