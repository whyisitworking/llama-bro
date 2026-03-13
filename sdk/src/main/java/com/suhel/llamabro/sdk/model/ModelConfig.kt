package com.suhel.llamabro.sdk.model

data class ModelConfig(
    val modelPath: String,
    val promptFormat: PromptFormat,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1),
)
