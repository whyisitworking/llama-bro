package com.suhel.llamabro.demo.model

import com.suhel.llamabro.sdk.config.InferenceConfig
import com.suhel.llamabro.sdk.format.PromptFormat

data class Model(
    val id: String,
    val name: String,
    val description: String? = null,
    val downloadUrl: String,
    val promptFormat: PromptFormat,
    val defaultInferenceConfig: InferenceConfig,
    val thinkingSupported: Boolean = false,
    val defaultMaxThinkingTokens: Int? = null,
)
