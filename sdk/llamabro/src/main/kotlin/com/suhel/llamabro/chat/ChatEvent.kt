package com.suhel.llamabro.chat

/** What a chat stream emits, in order. Exactly one [Done], last. */
public sealed interface ChatEvent {
    /** Prefill progress (llama-server's `prompt_progress`). */
    public data class PromptProgress(public val processed: Int, public val total: Int, public val cached: Int, public val timeMs: Long) : ChatEvent
    public data class Reasoning(public val delta: String) : ChatEvent
    public data class Content(public val delta: String) : ChatEvent
    /** A complete tool call. All calls of a reply arrive together, just before [Done]. */
    public data class ToolCall(public val call: com.suhel.llamabro.chat.ToolCall) : ChatEvent
    /** A tool's result. Emitted only by `Conversation`, after it runs a handler. */
    public data class ToolResult(public val callId: String, public val name: String, public val result: String) : ChatEvent
    public data class Done(public val reason: FinishReason, public val timings: Timings?, public val usage: Usage?) : ChatEvent
}

/**
 * [TurnLimit] and [ToolCallsPending] come only from `Conversation`'s agent loop (design §6.2).
 */
public enum class FinishReason { Stop, Length, ToolCalls, TurnLimit, ToolCallsPending }
