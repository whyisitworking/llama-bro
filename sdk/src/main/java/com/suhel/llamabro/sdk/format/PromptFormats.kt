package com.suhel.llamabro.sdk.format

/**
 * Out-of-the-box prompt templates for leading open-source model families.
 *
 * Each entry faithfully reproduces the template specified in the model's official documentation.
 * Using the wrong template will silently degrade generation quality, so choose carefully.
 */
object PromptFormats {

    /** ChatML — used by SmolLM2, Qwen 2.5, and other ChatML-compatible models. */
    val CHAT_ML = PromptFormat(
        systemPrefix = "<|im_start|>system\n",
        userPrefix = "<|im_start|>user\n",
        assistantPrefix = "<|im_start|>assistant\n",
        endOfTurn = "<|im_end|>\n",
        stopStrings = listOf("<|im_end|>")
    )

    /** Meta Llama 3.x / 3.1 / 3.2 family. */
    val LLAMA_3 = PromptFormat(
        systemPrefix = "<|start_header_id|>system<|end_header_id|>\n\n",
        userPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
        assistantPrefix = "<|start_header_id|>assistant<|end_header_id|>\n\n",
        endOfTurn = "<|eot_id|>",
        stopStrings = listOf("<|eot_id|>", "<|eom_id|>")
    )

    /** Mistral Instruct family. */
    val MISTRAL = PromptFormat(
        systemPrefix = "",
        userPrefix = "[INST] ",
        assistantPrefix = "",
        endOfTurn = " [/INST]",
        stopStrings = listOf("</s>", "[/INST]")
    )

    /** Google Gemma / Gemma 2 family. System messages are prepended to the first user turn. */
    val GEMMA = PromptFormat(
        systemPrefix = "<start_of_turn>user\nSystem: ",
        userPrefix = "<start_of_turn>user\n",
        assistantPrefix = "<start_of_turn>model\n",
        endOfTurn = "<end_of_turn>\n",
        stopStrings = listOf("<end_of_turn>")
    )

    /** DeepSeek R1 / R1-Distill family. */
    val DEEPSEEK_R1 = PromptFormat(
        systemPrefix = "<｜begin of sentence｜>",
        userPrefix = "User: ",
        assistantPrefix = "Assistant: ",
        endOfTurn = "<｜end of sentence｜>",
        stopStrings = listOf("<｜end of sentence｜>")
    )

    /** Qwen 2.5 family (identical to ChatML). */
    val QWEN_2_5 = CHAT_ML

    /**
     * NVIDIA Nemotron family.
     *
     * Uses the `<extra_id_0>` / `<extra_id_1>` sentinel format as specified in the official
     * [Nemotron-Mini-4B-Instruct](https://huggingface.co/nvidia/Nemotron-Mini-4B-Instruct) card.
     *
     * Template:
     * ```
     * <extra_id_0>System
     * {system_prompt}
     * <extra_id_1>User
     * {user_message}
     * <extra_id_1>Assistant\n
     * ```
     */
    val NEMOTRON = PromptFormat(
        systemPrefix = "<extra_id_0>System\n",
        userPrefix = "<extra_id_1>User\n",
        assistantPrefix = "<extra_id_1>Assistant\n",
        endOfTurn = "\n",
        stopStrings = listOf("<extra_id_1>")
    )

    val ZEPHYR = PromptFormat(
        systemPrefix = "<|system|>\n",
        userPrefix = "<|user|>\n",
        assistantPrefix = "<|assistant|>\n",
        endOfTurn = "</s>\n",
    )
}
