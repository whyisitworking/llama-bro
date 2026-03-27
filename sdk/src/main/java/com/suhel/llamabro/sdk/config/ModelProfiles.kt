package com.suhel.llamabro.sdk.config

/**
 * Curated model profiles with optimal sampling parameters.
 *
 * With native Jinja templates handling all prompt formatting and thinking tag detection,
 * profiles are now purely about inference sampling parameters.
 */
object ModelProfiles {
    val SMOLLM2 = ModelProfile(
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
        supportsThinking = true,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            topP = 0.95f,
            topK = 20,
            minP = 0.0f,
        ),
        thinkingInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            topP = 0.95f,
        ),
    )

    val QWEN_3_5 = ModelProfile(
        supportsThinking = true,
        defaultInferenceConfig = InferenceConfig(
            temperature = 1.0f,
            topP = 0.95f,
            topK = 20,
            minP = 0.0f,
            presencePenalty = 1.5f,
            repeatPenalty = 1.0f,
        ),
        thinkingInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            topP = 0.95f,
        ),
    )

    val DEEPSEEK_R1 = ModelProfile(
        supportsThinking = true,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            topP = 0.95f,
            topK = 40,
        ),
        thinkingInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            topP = 0.95f,
        ),
    )

    val LLAMA_3_2 = ModelProfile(
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            repeatPenalty = 1.1f,
            presencePenalty = 0.15f,
            minP = 0.05f,
            topP = 0.9f,
            topK = 40,
        ),
    )

    val GEMMA = ModelProfile(
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.7f,
            topP = 0.9f,
            topK = 40,
        ),
    )
}
