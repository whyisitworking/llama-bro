package com.suhel.llamabro.sdk.model

object PromptFormats {
    val ChatML = PromptFormat(
        systemPrefix = "<|im_start|>system\n",
        systemSuffix = "<|im_end|>\n",
        userPrefix = "<|im_start|>user\n",
        userSuffix = "<|im_end|>\n",
        assistantPrefix = "<|im_start|>assistant\n",
        assistantSuffix = "<|im_end|>\n",
    )

    val Llama3 = PromptFormat(
        systemPrefix = "<|start_header_id|>system<|end_header_id|>\n\n",
        systemSuffix = "<|eot_id|>",
        userPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
        userSuffix = "<|eot_id|>",
        assistantPrefix = "<|start_header_id|>assistant<|end_header_id|>\n\n",
        assistantSuffix = "<|eot_id|>",
    )

    val Mistral = PromptFormat(
        userPrefix = "[INST] ",
        userSuffix = " [/INST]",
        assistantPrefix = "",
        assistantSuffix = "",
        systemPrefix = "",
        systemSuffix = "",
    )

    val Gemma3 = PromptFormat(
        systemPrefix = "<start_of_turn>system\n",
        systemSuffix = "<end_of_turn>\n",
        userPrefix = "<start_of_turn>user\n",
        userSuffix = "<end_of_turn>\n",
        assistantPrefix = "<start_of_turn>model\n",
        assistantSuffix = "<end_of_turn>\n",
    )
}
