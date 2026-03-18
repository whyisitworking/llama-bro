package com.suhel.llamabro.demo.model

import com.suhel.llamabro.sdk.model.InferenceConfig
import com.suhel.llamabro.sdk.model.PromptFormats

val ModelZoo = listOf(
    Model(
        id = "smollm2-135m-instruct",
        name = "SmolLM2 135M Instruct",
        description = "Ultra-lightweight model for absolute maximum tokens-per-second. Perfect for baseline speed tests.",
        downloadUrl = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-F16.gguf",
        promptFormat = PromptFormats.ChatML,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            repeatPenalty = 1.15f,
            presencePenalty = 0.15f,
            minP = 0.05f,
            topP = 0.9f,
            topK = 40,
        )
    ),
    Model(
        id = "smollm2-360m-instruct",
        name = "SmolLM2 360M Instruct",
        description = "Highly efficient sub-0.5B model balancing sheer speed with improved coherence.",
        downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf",
        promptFormat = PromptFormats.ChatML,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.7f,
            repeatPenalty = 1.15f,
            presencePenalty = 0.15f,
            minP = 0.05f,
            topP = 0.9f,
            topK = 40,
        )
    ),
    Model(
        id = "qwen2.5-0.5b-instruct",
        name = "Qwen2.5 0.5B Instruct",
        description = "Exceptional speed with strong multilingual support and structured JSON formatting capabilities.",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf",
        promptFormat = PromptFormats.ChatML,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.7f,
            repeatPenalty = 1.05f,
            presencePenalty = 0.15f,
            minP = 0.1f,
            topP = 0.8f,
            topK = 40,
        )
    ),
    Model(
        id = "qwen3.5-2b",
        name = "Qwen3.5 2B",
        description = "Multimodal thinking model with advanced reasoning capabilities.",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-UD-Q5_K_XL.gguf",
        promptFormat = PromptFormats.ChatML,
        defaultInferenceConfig = InferenceConfig(
            temperature = 1.0f,
            topP = 0.95f,
            topK = 20,
            minP = 0.0f,
            presencePenalty = 1.5f,
            repeatPenalty = 1.0f,
        ),
        thinkingSupported = true,
    ),
    Model(
        id = "llama-3.2-1b-instruct",
        name = "Llama-3.2 1B Instruct",
        description = "Meta's highly optimized 1B mobile model. The industry standard for reliable on-device chat.",
        downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q5_K_M.gguf",
        promptFormat = PromptFormats.Llama3,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            repeatPenalty = 1.1f,
            presencePenalty = 0.15f,
            minP = 0.05f,
            topP = 0.9f,
            topK = 40,
        )
    ),
    Model(
        id = "deepseek-r1-distill-qwen-1.5b",
        name = "DeepSeek-R1 1.5B (Distilled)",
        description = "Advanced reasoning capabilities on-device. Uses chain-of-thought processing.",
        downloadUrl = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q8_0.gguf",
        promptFormat = PromptFormats.ChatML,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.6f,
            repeatPenalty = 1.0f,
            presencePenalty = 0.0f,
            minP = null,
            topP = 0.95f,
            topK = 40,
        ),
        thinkingSupported = true,
        defaultSystemPrompt = "You are a helpful and harmless assistant. You are Llama Bro."
    ),
    Model(
        id = "smollm2-1.7b-instruct",
        name = "SmolLM2 1.7B Instruct",
        description = "High-quality, nuanced text generation that punches above its weight class.",
        downloadUrl = "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q5_K_M.gguf",
        promptFormat = PromptFormats.ChatML,
        defaultInferenceConfig = InferenceConfig(
            temperature = 0.7f,
            repeatPenalty = 1.1f,
            presencePenalty = 0.15f,
            minP = 0.1f,
            topP = 0.9f,
            topK = 40,
        )
    )
)
