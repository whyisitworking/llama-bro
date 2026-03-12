package com.suhel.llamabro.sdk.api

/**
 * Configuration for loading a model. This is the entry point for [LlamaEngine.create].
 *
 * @param modelPath    Absolute path to the GGUF model file on device storage.
 * @param promptFormat The chat template used by this model. Use [PromptFormats] presets.
 *                     This is a property of the model weights, not a per-session setting.
 * @param useMmap      Memory-map the model file. Recommended: true. Reduces RAM by reading
 *                     weights directly from disk. Disable for encrypted storage or if the
 *                     model file may be modified while running.
 * @param useMlock     Lock the model in RAM (prevent paging). Recommended: false for most
 *                     devices. Enable only if you have guaranteed available physical memory.
 * @param threads      Number of CPU threads for inference. Defaults to half the available
 *                     logical cores, which is optimal for big.LITTLE Android SoCs (ensuring
 *                     performance cores are used without saturating them).
 */
data class ModelConfig(
    val modelPath: String,
    val promptFormat: PromptFormat,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1),
)
