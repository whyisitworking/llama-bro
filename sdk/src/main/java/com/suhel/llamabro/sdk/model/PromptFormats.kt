package com.suhel.llamabro.sdk.model

/**
 * Pre-defined [PromptFormat] templates for popular AI model families.
 *
 * Each model is trained with a specific way of separating roles (System, User, Assistant).
 * Using the wrong format will lead to poor model performance, hallucinations, 
 * or the model failing to recognize when it should stop generating.
 *
 * If your model is not listed here, consult the model's documentation on HuggingFace 
 * to determine the correct chat template and create a custom [PromptFormat].
 */
object PromptFormats {
    /** 
     * The ChatML format used by models like OpenAI (historically), Qwen, and many others.
     * Uses `<|im_start|>` and `<|im_end|>` tags.
     */
    val ChatML = PromptFormat(
        systemPrefix = "<|im_start|>system\n",
        systemSuffix = "<|im_end|>",
        userPrefix = "<|im_start|>user\n",
        userSuffix = "<|im_end|>",
        assistantPrefix = "<|im_start|>assistant\n",
        assistantSuffix = "<|im_end|>",
        stopStrings = listOf("<|im_start|>"),
    )

    /** 
     * The official prompt format for Meta's Llama 3 and 3.1 models.
     * Uses `<|start_header_id|>` and `<|eot_id|>` tags.
     */
    val Llama3 = PromptFormat(
        bos = "<|begin_of_text|>",
        systemPrefix = "<|start_header_id|>system<|end_header_id|>\n\n",
        systemSuffix = "<|eot_id|>",
        userPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
        userSuffix = "<|eot_id|>",
        assistantPrefix = "<|start_header_id|>assistant<|end_header_id|>\n\n",
        assistantSuffix = "<|eot_id|>",
        stopStrings = listOf("<|start_header_id|>"),
    )

    /** 
     * The instruction format used by Mistral-7B-Instruct.
     * Uses `[INST]` and `[/INST]` tags. Note that Mistral often doesn't have 
     * an explicit system role in its base template.
     */
    val Mistral = PromptFormat(
        bos = "<s>",
        eos = "</s>",
        userPrefix = "[INST] ",
        userSuffix = " [/INST]",
        assistantPrefix = "",
        assistantSuffix = "",
        systemPrefix = "",
        systemSuffix = "",
        stopStrings = listOf("[INST]"),
    )

    /** 
     * The prompt format for Google's Gemma 3 models.
     * Uses `<start_of_turn>` and `<end_of_turn>` tags.
     */
    val Gemma3 = PromptFormat(
        bos = "<bos>",
        systemPrefix = "<start_of_turn>system\n",
        systemSuffix = "<end_of_turn>",
        userPrefix = "\n<start_of_turn>user\n",
        userSuffix = "<end_of_turn>",
        assistantPrefix = "\n<start_of_turn>model\n",
        assistantSuffix = "<end_of_turn>",
        stopStrings = listOf("<start_of_turn>"),
    )
}
