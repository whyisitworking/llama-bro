package com.suhel.llamabro.sdk.config

import com.suhel.llamabro.sdk.chat.pipeline.TagDelimiter
import com.suhel.llamabro.sdk.format.PromptFormat

data class ModelProfile(
    val promptFormat: PromptFormat,
    val thinking: ThinkingCapability? = null,
    val toolCall: ToolCallCapability? = null,
    val defaultInferenceConfig: InferenceConfig = InferenceConfig(),
) {
    val supportsThinking: Boolean get() = thinking != null
    val supportsToolCalls: Boolean get() = toolCall != null

    internal val tagDelimiters: List<TagDelimiter> = listOfNotNull(
        thinking?.tags,
        toolCall?.tags,
    )

    val inferenceConfigForThinking: InferenceConfig =
        thinking?.inferenceOverrides ?: defaultInferenceConfig
}
