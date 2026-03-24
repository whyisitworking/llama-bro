package com.suhel.llamabro.sdk.config

import com.suhel.llamabro.sdk.chat.pipeline.TagDelimiter
import com.suhel.llamabro.sdk.toolcall.ToolCall
import com.suhel.llamabro.sdk.toolcall.ToolDefinition

sealed interface Capability

data class ThinkingCapability(
    val tags: TagDelimiter = TagDelimiter("<think>", "</think>"),
    val strategy: ThinkingStrategy = ThinkingStrategy.None,
    val inferenceOverrides: InferenceConfig? = null,
) : Capability

/**
 * How the SDK manipulates the prompt to activate/deactivate thinking.
 * The scanner ALWAYS watches for thinking tags regardless of strategy.
 * Strategy only governs prompt-level control when user requests think on/off.
 */
sealed interface ThinkingStrategy {
    /**
     * Append a directive to the user message.
     * Used by: Qwen3, SmolLM3
     */
    data class SoftSwitch(
        val enableDirective: String = "/think",
        val disableDirective: String = "/no_think",
    ) : ThinkingStrategy

    /**
     * Insert text after the assistant prefix to force/suppress thinking.
     * Used by: DeepSeek R1, Qwen 3.5
     */
    data class Prefill(
        val forcePrefix: String,
        val suppressPrefix: String,
    ) : ThinkingStrategy

    /** No prompt-level control. Model thinks (or doesn't) on its own. */
    data object None : ThinkingStrategy
}

data class ToolCallCapability(
    val tags: TagDelimiter,
    val callParser: (String) -> ToolCall,
    val callSerializer: (ToolCall) -> String,
    val definitionFormatter: (List<ToolDefinition>) -> String,
) : Capability
