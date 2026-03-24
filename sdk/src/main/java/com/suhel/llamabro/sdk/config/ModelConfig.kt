package com.suhel.llamabro.sdk.config

data class LoadableModel(
    val loadConfig: ModelLoadConfig,
    val profile: ModelProfile,
)

data class ModelLoadConfig(
    val path: String,
    val useMMap: Boolean = true,
    val useMLock: Boolean = false,
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1),
)
