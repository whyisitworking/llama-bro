package com.suhel.llamabro.sdk.model

/**
 * Defines the prefix/suffix tokens that wrap each message role in a chat template.
 *
 * Use one of the pre-defined formats in [PromptFormats], or create a custom one
 * for fine-tuned models with non-standard templates.
 */
data class PromptFormat(
    val systemPrefix: String,
    val systemSuffix: String,
    val userPrefix: String,
    val userSuffix: String,
    val assistantPrefix: String,
    val assistantSuffix: String,
)
