package com.suhel.llamabro.chat

/**
 * What a chat stream emits, in order. Exactly one [Done], last. Sealed so `when` over it is exhaustive; the set is
 * final at 1.0 (D26).
 */
public sealed interface ChatEvent {
    /** Prefill progress (llama-server's `prompt_progress`). */
    public data class PromptProgress(
        public val processed: Int,
        public val total: Int,
        public val cached: Int,
        public val timeMs: Long,
    ) : ChatEvent

    /** The next piece of the reply's reasoning (`reasoning_content`). */
    public data class ReasoningDelta(public val delta: String) : ChatEvent

    /** The next piece of the reply's text (`content`). */
    public data class ContentDelta(public val delta: String) : ChatEvent

    /** A complete tool call. All calls of a reply arrive together, just before [Done]. */
    public data class ToolCallReceived(public val call: ToolCall) : ChatEvent

    /** A tool's result. Emitted only by `Conversation`, after it runs a handler. */
    public data class ToolResult(public val callId: String, public val name: String, public val result: String) :
        ChatEvent

    /** The end of a reply. A class, not a data class: it gains fields (D26). */
    public class Done internal constructor(
        public val reason: FinishReason,
        public val timings: Timings?,
        public val usage: Usage?,
    ) : ChatEvent {
        override fun equals(other: Any?): Boolean =
            other is Done && other.reason == reason && other.timings == timings && other.usage == usage

        override fun hashCode(): Int = listOf(reason, timings, usage).hashCode()

        override fun toString(): String = "Done(reason=$reason, timings=$timings, usage=$usage)"
    }
}

/**
 * Why a reply ended. The set is final at 1.0 (D26). [TurnLimit] and [ToolCallsPending] come only from `Conversation`'s
 * agent loop (design §6.2).
 */
public enum class FinishReason { Stop, Length, ToolCalls, TurnLimit, ToolCallsPending }
