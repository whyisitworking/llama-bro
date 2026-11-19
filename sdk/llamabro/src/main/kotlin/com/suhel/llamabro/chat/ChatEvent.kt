package com.suhel.llamabro.chat

import com.suhel.llamabro.conversation.LbToolAbort

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

    /**
     * A complete tool call. All calls of a reply arrive together, just before [Done]. In `Conversation`'s loop each
     * call is announced when the loop reaches it (D62), so calls after an approval stop are announced only when the
     * loop continues.
     */
    public data class ToolCallReceived(public val call: ToolCall) : ChatEvent

    /**
     * A tool call's result, as sent to the model. Emitted only by `Conversation`'s agent loop.
     * @param result the text the model sees: the handler's return, or an `Error: …` text (D62).
     * @param error the exception a handler threw when the model got the generic `Error: the tool failed` instead of
     *   its message, so the app can log or show it; or the `cause` of an `LbToolError` that has one, which the model
     *   never sees; null otherwise, including for an `LbToolError` without a cause, whose message the model
     *   already has.
     */
    public data class ToolResult(
        public val callId: String,
        public val name: String,
        public val result: String,
        public val error: Throwable? = null,
    ) : ChatEvent

    /**
     * The end of a reply.
     * @param abort the `LbToolAbort` a tool threw when [reason] is [FinishReason.ToolAborted]; null otherwise.
     */
    public data class Done internal constructor(
        public val reason: FinishReason,
        public val timings: Timings?,
        public val usage: Usage?,
        public val abort: LbToolAbort? = null,
    ) : ChatEvent
}

/**
 * Why a reply ended. The set is final at 1.0 (D26). [TurnLimit], [ToolCallsPending] and [ToolAborted] come only from
 * `Conversation`'s agent loop: [ToolCallsPending] when a call waits for the app's result, [ToolAborted]
 * when a tool threw `LbToolAbort` (D62; the exception is `Done.abort`).
 */
public enum class FinishReason { Stop, Length, ToolCalls, TurnLimit, ToolCallsPending, ToolAborted }
