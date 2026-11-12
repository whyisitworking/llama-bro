package com.suhel.llamabro.chat

/**
 * Thinking controls: `reasoning_format`, `reasoning_budget_tokens`, `reasoning_budget_message` (server-schema.cpp)
 * and the chat template's `enable_thinking` (sent in `chat_template_kwargs`). null = llama-server's default.
 *
 * Build one with `Reasoning { enableThinking = false }`; change one with [copy].
 */
public class Reasoning internal constructor(
    public val format: ReasoningFormat?,
    public val budgetTokens: Int?,
    /** Text inserted when the budget runs out, before the model is made to stop thinking. */
    public val budgetMessage: String?,
    public val enableThinking: Boolean?,
) {
    public fun copy(configure: Builder.() -> Unit): Reasoning = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean = other is Reasoning && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "Reasoning(format=$format, budgetTokens=$budgetTokens, budgetMessage=$budgetMessage, enableThinking=$enableThinking)"

    private fun parts() = listOf(format, budgetTokens, budgetMessage, enableThinking)

    /** Mutable scope of `Reasoning { }` and [copy]. */
    public class Builder internal constructor(from: Reasoning) {
        public var format: ReasoningFormat? = from.format
        public var budgetTokens: Int? = from.budgetTokens
        public var budgetMessage: String? = from.budgetMessage
        public var enableThinking: Boolean? = from.enableThinking

        internal fun build(): Reasoning = Reasoning(format, budgetTokens, budgetMessage, enableThinking)
    }

    public companion object {
        /** Nothing set: the model's and llama-server's defaults. */
        @JvmField
        public val Default: Reasoning = Reasoning(null, null, null, null)
    }
}

/** `Reasoning { enableThinking = false }`. */
public fun Reasoning(configure: Reasoning.Builder.() -> Unit): Reasoning = Reasoning.Default.copy(configure)

/** llama-server's `reasoning_format` names: `auto`, `none`, `deepseek`. */
public enum class ReasoningFormat(internal val wire: String) { Auto("auto"), None("none"), DeepSeek("deepseek") }
