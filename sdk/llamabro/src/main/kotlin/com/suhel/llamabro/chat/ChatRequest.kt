package com.suhel.llamabro.chat

/**
 * An OpenAI-shaped chat request, sent to llama-server's `/v1/chat/completions` handler: messages, tools and
 * [options]. Unset fields are left out, so the GGUF's and llama-server's defaults apply (D18).
 *
 * ```
 * val request = ChatRequest {
 *     messages(Message.system("Be brief."), Message.user("Hi!"))
 *     sampling { temperature = 0.2f }
 *     reasoning { enableThinking = false }
 *     extra = """{"n_probs": 3}"""
 * }
 * ```
 */
public class ChatRequest internal constructor(
    public val messages: List<Message>,
    public val tools: List<Tool>,
    // everything else (sampling, reasoning, format, stop, tool choice, extra), read through the getters below
    internal val options: RequestOptions,
) {
    public val toolChoice: ToolChoice? get() = options.toolChoice
    public val parallelToolCalls: Boolean? get() = options.parallelToolCalls
    public val responseFormat: ResponseFormat? get() = options.responseFormat
    public val sampling: Sampling get() = options.sampling
    public val reasoning: Reasoning get() = options.reasoning
    public val stop: List<String> get() = options.stop
    public val extra: String? get() = options.extra

    /** A copy with [configure] applied on top of this request. */
    public fun copy(configure: Builder.() -> Unit): ChatRequest = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean =
        other is ChatRequest && other.messages == messages && other.tools == tools && other.options == options

    override fun hashCode(): Int = listOf(messages, tools, options).hashCode()

    override fun toString(): String = "ChatRequest(messages=$messages, tools=$tools, options=$options)"

    /** Mutable scope of `ChatRequest { }` and [copy]: the [RequestOptions] scope plus messages and tools. */
    public class Builder internal constructor(from: ChatRequest) : RequestOptions.Builder(from.options) {
        public var messages: List<Message> = from.messages
        public var tools: List<Tool> = from.tools

        public fun messages(vararg messages: Message) {
            this.messages = messages.toList()
        }

        public fun tools(vararg tools: Tool) {
            this.tools = tools.toList()
        }

        internal fun build(): ChatRequest = ChatRequest(messages.toList(), tools.toList(), buildOptions())
    }

    internal companion object {
        val EMPTY = ChatRequest(emptyList(), emptyList(), RequestOptions.Default)
    }
}

/** Builds a [ChatRequest]; see its example. */
public fun ChatRequest(configure: ChatRequest.Builder.() -> Unit): ChatRequest = ChatRequest.EMPTY.copy(configure)
