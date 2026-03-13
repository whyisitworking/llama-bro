package com.suhel.llamabro.demo.model

import com.suhel.llamabro.sdk.model.InferenceConfig
import com.suhel.llamabro.sdk.model.PromptFormats

val CURATED_MODELS = listOf(
    Model(
        id = "gemma-3n-1b",
        name = "Gemma 3n 1B",
        description = "Google’s Gemma 3n multimodal model handles image, audio, video, and text inputs. Available in 2B and 4B sizes, it supports 140 languages for text and multimodal tasks.",
        downloadUrl = "https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF/resolve/main/gemma-3n-E2B-it-Q4_K_M.gguf",
        promptFormat = PromptFormats.Gemma3,
        defaultInferenceConfig = InferenceConfig(
            temperature = 1.0f,
            topK = 64,
            minP = 0.01f,
            topP = 0.95f,
        )
    )
)
