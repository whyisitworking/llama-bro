package com.suhel.llamabro.sdk.config

import com.suhel.llamabro.sdk.chat.pipeline.TagDelimiter

object ThinkingCapabilities {
    val STANDARD_THINK_TAGS = TagDelimiter("<think>", "</think>")

    val SOFT_SWITCH_THINKING = ThinkingCapability(
        tags = STANDARD_THINK_TAGS,
        strategy = ThinkingStrategy.SoftSwitch(),
        inferenceOverrides = InferenceConfig(temperature = 0.6f, topP = 0.95f),
    )

    val PREFILL_THINKING = ThinkingCapability(
        tags = STANDARD_THINK_TAGS,
        strategy = ThinkingStrategy.Prefill(
            forcePrefix = "<think>\n",
            suppressPrefix = "<think>\n\n</think>",
        ),
        inferenceOverrides = InferenceConfig(temperature = 0.6f, topP = 0.95f),
    )
}
