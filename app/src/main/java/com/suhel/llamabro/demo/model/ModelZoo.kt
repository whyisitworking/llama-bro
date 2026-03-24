package com.suhel.llamabro.demo.model

import com.suhel.llamabro.sdk.config.InferenceConfig
import com.suhel.llamabro.sdk.config.ModelProfiles

val ModelZoo = listOf(
    Model(
        id = "smollm2-135m-instruct",
        name = "SmolLM2 135M Instruct",
        description = "Ultra-lightweight model for absolute maximum tokens-per-second. Perfect for baseline speed tests.",
        downloadUrl = "https://huggingface.co/unsloth/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-F16.gguf",
        profile = ModelProfiles.SMOLLM2.copy(
            defaultInferenceConfig = InferenceConfig(
                temperature = 0.6f,
                repeatPenalty = 1.15f,
                presencePenalty = 0.15f,
                minP = 0.05f,
                topP = 0.9f,
                topK = 40,
            )
        ),
    ),
    Model(
        id = "smollm2-360m-instruct",
        name = "SmolLM2 360M Instruct",
        description = "Highly efficient sub-0.5B model balancing sheer speed with improved coherence.",
        downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf",
        profile = ModelProfiles.SMOLLM2.copy(
            defaultInferenceConfig = InferenceConfig(
                temperature = 0.7f,
                repeatPenalty = 1.15f,
                presencePenalty = 0.15f,
                minP = 0.05f,
                topP = 0.9f,
                topK = 40,
            )
        ),
    ),
    Model(
        id = "qwen2.5-0.5b-instruct",
        name = "Qwen2.5 0.5B Instruct",
        description = "Exceptional speed with strong multilingual support and structured JSON formatting capabilities.",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf",
        profile = ModelProfiles.QWEN_2_5,
    ),
    Model(
        id = "qwen3-0.6b",
        name = "Qwen3 0.6B",
        description = "Qwen3 is the latest generation of large language models in Qwen series, offering a comprehensive suite of dense and mixture-of-experts (MoE) models.",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-UD-Q4_K_XL.gguf",
        profile = ModelProfiles.QWEN_3,
    ),
    Model(
        id = "qwen3.5-2b",
        name = "Qwen3.5 2B",
        description = "Multimodal thinking model with advanced reasoning capabilities.",
        downloadUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-UD-Q5_K_XL.gguf",
        profile = ModelProfiles.QWEN_3_5,
    ),
    Model(
        id = "llama-3.2-1b-instruct",
        name = "Llama-3.2 1B Instruct",
        description = "Meta's highly optimized 1B mobile model. The industry standard for reliable on-device chat.",
        downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q5_K_M.gguf",
        profile = ModelProfiles.LLAMA_3_2,
    ),
    Model(
        id = "deepseek-r1-distill-qwen-1.5b",
        name = "DeepSeek-R1 1.5B (Distilled)",
        description = "Advanced reasoning capabilities on-device. Uses chain-of-thought processing.",
        downloadUrl = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q8_0.gguf",
        profile = ModelProfiles.DEEPSEEK_R1_DISTILL_QWEN,
    ),
    Model(
        id = "smollm2-1.7b-instruct",
        name = "SmolLM2 1.7B Instruct",
        description = "High-quality, nuanced text generation that punches above its weight class.",
        downloadUrl = "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q5_K_M.gguf",
        profile = ModelProfiles.SMOLLM2,
    ),
)
