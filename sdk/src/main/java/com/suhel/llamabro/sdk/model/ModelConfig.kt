package com.suhel.llamabro.sdk.model

/**
 * Configuration for loading a GGUF model into a [com.suhel.llamabro.sdk.LlamaEngine].
 *
 * This class defines how the model file is read and held in memory. Choosing the 
 * right parameters here is crucial for performance and stability on mobile devices.
 *
 * @property modelPath          The absolute filesystem path to the `.gguf` model file.
 * @property promptFormat       The chat template that matches how the model was trained.
 *                              Use [PromptFormats] for common models.
 * @property useMmap            If true, the model is memory-mapped. This allows faster
 *                              initialization and lets the OS manage memory paging.
 *                              Usually set to true.
 * @property useMlock           If true, the model's memory pages are locked in RAM.
 *                              This prevents the OS from swapping them out, ensuring
 *                              consistent performance but potentially causing OOMs if
 *                              the model is larger than available RAM.
 * @property supportsThinking   Whether the model supports thinking.
 * @property threads            The number of CPU threads to use for compute-heavy
 *                              inference tasks. Defaults to half the available cores
 *                              to balance performance and battery/thermal impact.
 */
data class ModelConfig(
    val modelPath: String,
    val promptFormat: PromptFormat,
    val supportsThinking: Boolean = false,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1),
)
