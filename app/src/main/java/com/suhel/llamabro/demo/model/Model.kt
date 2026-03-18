package com.suhel.llamabro.demo.model

import com.suhel.llamabro.sdk.model.InferenceConfig
import com.suhel.llamabro.sdk.model.PromptFormat

data class Model(
    val id: String,
    val name: String,
    val description: String? = null,
    val downloadUrl: String,
    val promptFormat: PromptFormat,
    val defaultInferenceConfig: InferenceConfig,
    val thinkingSupported: Boolean = false,
    val defaultSystemPrompt: String? = null
)
