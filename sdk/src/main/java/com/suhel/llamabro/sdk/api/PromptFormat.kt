package com.suhel.llamabro.sdk.api

data class PromptFormat(
    val systemPrefix: String,
    val systemSuffix: String,
    val userPrefix: String,
    val userSuffix: String,
    val assistantPrefix: String,
    val assistantSuffix: String,
    val globalPrefix: String,
    val globalSuffix: String
)

object PromptFormats {
    val ChatML = PromptFormat(
        systemPrefix = "<|im_start|>system\n",
        systemSuffix = "<|im_end|>\n",
        userPrefix = "<|im_start|>user\n",
        userSuffix = "<|im_end|>\n",
        assistantPrefix = "<|im_start|>assistant\n",
        assistantSuffix = "<|im_end|>\n",
        globalPrefix = "",
        globalSuffix = ""
    )

    val Llama3 = PromptFormat(
        globalPrefix = "<|begin_of_text|>",
        systemPrefix = "<|start_header_id|>system<|end_header_id|>\n\n",
        systemSuffix = "<|eot_id|>",
        userPrefix = "<|start_header_id|>user<|end_header_id|>\n\n",
        userSuffix = "<|eot_id|>",
        assistantPrefix = "<|start_header_id|>assistant<|end_header_id|>\n\n",
        assistantSuffix = "<|eot_id|>",
        globalSuffix = ""
    )

    val Mistral = PromptFormat(
        userPrefix = "[INST] ",
        userSuffix = " [/INST]",
        assistantPrefix = "",
        assistantSuffix = "",
        systemPrefix = "",
        systemSuffix = "",
        globalPrefix = "",
        globalSuffix = ""
    )
}

class PromptFormatter(private val formatter: PromptFormat) {
    fun user(text: String): String =
        "${formatter.userPrefix}$text${formatter.userSuffix}"

    fun system(text: String): String =
        "${formatter.systemPrefix}$text${formatter.systemSuffix}"

    fun assistant(text: String): String =
        "${formatter.assistantPrefix}$text${formatter.assistantSuffix}"
}
