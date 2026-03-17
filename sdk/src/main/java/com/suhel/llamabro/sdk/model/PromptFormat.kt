package com.suhel.llamabro.sdk.model

/**
 * Defines the chat template used to wrap user and assistant messages.
 *
 * LLMs are trained with specific special tokens to distinguish between different 
 * roles (System, User, Assistant). Providing the correct format is essential 
 * for the model to follow instructions and maintain conversation flow.
 *
 * Use [PromptFormats] to select a pre-defined template for popular models.
 *
 * @property systemPrefix    The token(s) that start a system instruction.
 * @property systemSuffix    The token(s) that end a system instruction.
 * @property userPrefix      The token(s) that start a user message.
 * @property userSuffix      The token(s) that end a user message.
 * @property assistantPrefix The token(s) that start an assistant response.
 * @property assistantSuffix The token(s) that end an assistant response (often used as a stop sequence).
 * @property bos             The Beginning of Sentence token (e.g., "<s>" or "<|begin_of_text|>").
 *                           Inserted once at the very start of a session.
 * @property eos             The End of Sentence token (e.g., "</s>" or "<|end_of_text|>").
 * @property thinkStart      The token(s) that start a thinking block.
 * @property thinkEnd        The token(s) that end a thinking block.
 */
data class PromptFormat(
    val systemPrefix: String,
    val systemSuffix: String,
    val userPrefix: String,
    val userSuffix: String,
    val assistantPrefix: String,
    val assistantSuffix: String,
    val bos: String? = null,
    val eos: String? = null,
    val thinkStart: String = "<think>",
    val thinkEnd: String = "</think>",
)
