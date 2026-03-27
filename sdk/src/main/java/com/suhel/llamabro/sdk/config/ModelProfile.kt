package com.suhel.llamabro.sdk.config

/**
 * Model-specific configuration for inference behavior.
 *
 * With the migration to native Jinja templates, prompt formatting and thinking tag detection
 * are handled entirely in C++. ModelProfile now only carries inference sampling parameters
 * and optional tool call capability.
 *
 * @param defaultInferenceConfig Default sampling parameters for normal generation.
 * @param thinkingInferenceConfig Optional sampling overrides when thinking/reasoning is enabled.
 *   If null, [defaultInferenceConfig] is used for thinking requests too.
 * @param toolCall Optional tool call capability for function calling models.
 */
data class ModelProfile(
    val supportsThinking: Boolean = false,
    val defaultInferenceConfig: InferenceConfig = InferenceConfig(),
    val thinkingInferenceConfig: InferenceConfig? = null,
    val toolCall: ToolCallCapability? = null,
)
