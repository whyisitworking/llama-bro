package com.suhel.llamabro.sdk.config

import com.suhel.llamabro.sdk.chat.pipeline.FeatureMarker
import com.suhel.llamabro.sdk.chat.pipeline.ThinkingMarker
import com.suhel.llamabro.sdk.format.PromptFormat
import com.suhel.llamabro.sdk.toolcall.ToolCallDefinition

data class ModelDefinition(
    val loadConfig: ModelLoadConfig,
    val promptFormat: PromptFormat,
    val features: List<FeatureMarker> = emptyList(),
    val toolCall: ToolCallDefinition? = null
) {
    val supportsThinking: Boolean get() = features.any { it is ThinkingMarker }
}

data class ModelLoadConfig(
    val path: String,
    val useMMap: Boolean = true,
    val useMLock: Boolean = false,
    val threads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1),
)
