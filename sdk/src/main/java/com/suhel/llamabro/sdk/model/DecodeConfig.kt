package com.suhel.llamabro.sdk.model

/**
 * Low-level llama.cpp context tuning parameters. 
 *
 * The defaults are optimized for typical Android on-device inference. Only adjust 
 * these if you have a specific performance profile or memory constraint in mind.
 *
 * @property batchSize             Maximum number of tokens processed in a single llama_decode
 *                                 call (n_batch). Larger values speed up prompt ingestion
 *                                 (pre-fill) at the cost of peak memory usage. 
 * @property microBatchSize        Physical GGML compute batch size (n_ubatch). Must be less 
 *                                 than or equal to [batchSize]. Affects SIMD register 
 *                                 utilization and memory bandwidth.
 */
data class DecodeConfig(
    val batchSize: Int = 2048,
    val microBatchSize: Int = 512,
) {
    init {
        require(batchSize >= microBatchSize) {
            "batchSize ($batchSize) must be >= microBatchSize ($microBatchSize)"
        }
    }
}
