package com.suhel.llamabro.chat

import com.suhel.llamabro.internal.JsonMergePatch

/**
 * Everything a chat request carries besides its messages and tools: what `Conversation` sends with every
 * request (`defaults { }`) and what one `send { }` changes. [ChatRequest] has the same options.
 */
public class RequestOptions internal constructor(
    public val toolChoice: ToolChoice?,
    public val parallelToolCalls: Boolean?,
    public val responseFormat: ResponseFormat?,
    public val sampling: Sampling,
    public val reasoning: Reasoning,
    public val stop: List<String>,
    /**
     * A JSON object applied to the body last, as a JSON Merge Patch (RFC 7396): objects merge key by key, `null`
     * removes a key, anything else replaces. Any llama-server field the typed ones don't cover goes here.
     */
    public val extra: String?,
) {
    /** A copy with [configure] applied on top of these options. */
    public fun copy(configure: Builder.() -> Unit): RequestOptions = Builder(this).apply(configure).buildOptions()

    override fun equals(other: Any?): Boolean = other is RequestOptions && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "RequestOptions(toolChoice=$toolChoice, parallelToolCalls=$parallelToolCalls, " +
            "responseFormat=$responseFormat, " +
            "sampling=$sampling, reasoning=$reasoning, stop=$stop, extra=$extra)"

    private fun parts() = listOf(toolChoice, parallelToolCalls, responseFormat, sampling, reasoning, stop, extra)

    /**
     * Mutable scope of `RequestOptions { }`, `defaults { }`, a send's options and [copy]. It starts from the
     * current options: assigning a property replaces it; `sampling { }` and `reasoning { }` add to it.
     */
    public open class Builder internal constructor(from: RequestOptions) {
        public var toolChoice: ToolChoice? = from.toolChoice
        public var parallelToolCalls: Boolean? = from.parallelToolCalls
        public var responseFormat: ResponseFormat? = from.responseFormat
        public var sampling: Sampling = from.sampling
        public var reasoning: Reasoning = from.reasoning
        public var stop: List<String> = from.stop

        /**
         * See [RequestOptions.extra]. Checked when set: it must be a JSON object, and may not set `stream`,
         * `stream_options` or `return_progress`, which the SDK owns.
         * @throws IllegalArgumentException
         */
        public var extra: String? = from.extra
            set(value) {
                if (value != null) JsonMergePatch.checkPatch(value, OWNED)
                field = value
            }

        public fun stop(vararg stop: String) {
            this.stop = stop.toList()
        }

        /** Changes [sampling] in place: a second block adds to the first. */
        public fun sampling(configure: Sampling.Builder.() -> Unit) {
            sampling = sampling.copy(configure)
        }

        /** Changes [reasoning] in place. */
        public fun reasoning(configure: Reasoning.Builder.() -> Unit) {
            reasoning = reasoning.copy(configure)
        }

        internal fun buildOptions(): RequestOptions =
            RequestOptions(toolChoice, parallelToolCalls, responseFormat, sampling, reasoning, stop.toList(), extra)
    }

    public companion object {
        /** Nothing set: the model's and llama-server's defaults. */
        @JvmField
        public val Default: RequestOptions =
            RequestOptions(null, null, null, Sampling.Default, Reasoning.Default, emptyList(), null)

        /** Body keys the SDK sets and `extra` may not touch. */
        internal val OWNED = setOf("stream", "stream_options", "return_progress")
    }
}

/** `RequestOptions { sampling { temperature = 0.2f }; stop("END") }`. */
public fun RequestOptions(configure: RequestOptions.Builder.() -> Unit): RequestOptions =
    RequestOptions.Default.copy(configure)
