package com.suhel.llamabro.chat

/**
 * Thinking controls: `reasoning_format`, `reasoning_budget_tokens` (server-schema.cpp) and the chat template's
 * `enable_thinking` (sent in `chat_template_kwargs`). null = llama-server's default.
 */
public data class Reasoning(
    public val format: ReasoningFormat? = null,
    public val budgetTokens: Int? = null,
    public val enableThinking: Boolean? = null,
)

/** llama-server's `reasoning_format` names: `auto`, `none`, `deepseek`. */
public enum class ReasoningFormat(internal val wire: String) { Auto("auto"), None("none"), DeepSeek("deepseek") }
