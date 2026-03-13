package com.suhel.llamabro.sdk.model

/**
 * Configuration for loading a GGUF model into an [com.suhel.llamabro.sdk.LlamaEngine].
 *
 * @param modelPath    Absolute path to the `.gguf` model file on the device.
 * @param promptFormat The chat template that matches the model's training format.
 * @param useMmap      Whether to memory-map the model file (faster cold-start, shared pages). Default: true.
 * @param useMlock     Whether to lock model pages in RAM (prevents paging, needs sufficient free memory). Default: false.
 * @param threads      Number of threads for inference. Default: half of available processors (at least 1).
 */
data class ModelConfig(
    val modelPath: String,
    val promptFormat: PromptFormat,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1),
)
