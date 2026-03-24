package com.suhel.llamabro.sdk.config

import com.suhel.llamabro.sdk.chat.pipeline.TagDelimiter
import com.suhel.llamabro.sdk.format.PromptFormats

object ModelProfiles {

    private val STANDARD_THINK_TAGS = TagDelimiter("<think>", "</think>")

    private val SOFT_SWITCH_THINKING = ThinkingCapability(
        tags = STANDARD_THINK_TAGS,
        strategy = ThinkingStrategy.SoftSwitch(),
        inferenceOverrides = InferenceConfig(temperature = 0.6f, topP = 0.95f),
    )

    private val PREFILL_THINKING = ThinkingCapability(
        tags = STANDARD_THINK_TAGS,
        strategy = ThinkingStrategy.Prefill(
            forcePrefix = "<think>\n",
            suppressPrefix = "<think>\n\n</think>",
        ),
        inferenceOverrides = InferenceConfig(temperature = 0.6f, topP = 0.95f),
    )

    val SMOLLM2 = ModelProfile(
        promptFormat = PromptFormats.CHAT_ML,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.7f,
            repeatPenalty = 1.1f,
            presencePenalty = 0.15f,
            minP = 0.1f,
            topP = 0.9f,
            topK = 40,
        ),
    )

    val QWEN_2_5 = ModelProfile(
        promptFormat = PromptFormats.CHAT_ML,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.7f,
            repeatPenalty = 1.05f,
            presencePenalty = 0.15f,
            minP = 0.1f,
            topP = 0.8f,
            topK = 40,
        ),
    )

    val QWEN_3 = ModelProfile(
        promptFormat = PromptFormats.CHAT_ML,
        thinking = SOFT_SWITCH_THINKING,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            topP = 0.95f,
            topK = 20,
            minP = 0.0f,
        ),
    )

    val QWEN_3_5 = ModelProfile(
        promptFormat = PromptFormats.CHAT_ML,
        thinking = PREFILL_THINKING,
        defaultInferenceConfig = InferenceConfig(
            temperature = 1.0f,
            topP = 0.95f,
            topK = 20,
            minP = 0.0f,
            presencePenalty = 1.5f,
            repeatPenalty = 1.0f,
        ),
    )

    val DEEPSEEK_R1 = ModelProfile(
        promptFormat = PromptFormats.DEEPSEEK_R1,
        thinking = PREFILL_THINKING,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            topP = 0.95f,
            topK = 40,
        ),
    )

    val DEEPSEEK_R1_DISTILL_QWEN = ModelProfile(
        promptFormat = PromptFormats.CHAT_ML,
        thinking = PREFILL_THINKING,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            topP = 0.95f,
            topK = 40,
        ),
    )

    val LLAMA_3_2 = ModelProfile(
        promptFormat = PromptFormats.LLAMA_3,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            repeatPenalty = 1.1f,
            presencePenalty = 0.15f,
            minP = 0.05f,
            topP = 0.9f,
            topK = 40,
        ),
    )

    val GEMMA_2 = ModelProfile(
        promptFormat = PromptFormats.GEMMA,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.7f,
            topP = 0.9f,
            topK = 40,
        ),
    )
}
