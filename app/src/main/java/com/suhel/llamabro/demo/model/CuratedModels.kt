package com.suhel.llamabro.demo.model

import com.suhel.llamabro.sdk.model.InferenceConfig
import com.suhel.llamabro.sdk.model.PromptFormats

val CURATED_MODELS = listOf(
    Model(
        id = "gemma-3n-2b",
        name = "Gemma 3n 2B",
        description = "Google’s Gemma 3n multimodal model handles image, audio, video, and text inputs. Available in 2B and 4B sizes, it supports 140 languages for text and multimodal tasks.",
        downloadUrl = "https://huggingface.co/unsloth/gemma-3n-E2B-it-GGUF/resolve/main/gemma-3n-E2B-it-Q4_K_M.gguf",
        promptFormat = PromptFormats.Gemma3,
        defaultInferenceConfig = InferenceConfig(
            temperature = 1.0f,
            topK = 64,
            minP = 0.01f,
            topP = 0.95f,
        )
    ),

    Model(
        id = "qwen3.5-4b",
        name = "Qwen3.5 4B",
        description = "Qwen3.5 is Alibaba’s new model family. The multimodal hybrid reasoning LLMs deliver the strongest performances for their sizes",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
        promptFormat = PromptFormats.ChatML,
        defaultInferenceConfig = InferenceConfig(
            temperature = 1.0f,
            topP = 0.95f,
            topK = 20,
            minP = 0.0f,
            presencePenalty = 1.5f,
            repeatPenalty = 1.0f,
        )
    )
)
