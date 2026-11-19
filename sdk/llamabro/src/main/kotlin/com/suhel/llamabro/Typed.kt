package com.suhel.llamabro

import com.suhel.llamabro.chat.FinishReason

/**
 * The outcome of a reply the application asked for as a value: [Value], or why there is none. Engine failures
 * (context exceeded, a closed model, cancellation) are not outcomes of the reply and stay exceptions.
 */
public sealed interface Typed<out T> {
    /** A complete reply. [text] is the reply's text and [reasoning] its reasoning, `null` when it has none. */
    public data class Value<out T>(public val value: T, public val text: String, public val reasoning: String?) :
        Typed<T>

    /**
     * The reply is not a complete answer: [reason] is `Length` (cut at the output limit), `TurnLimit`,
     * `ToolCallsPending` (continue with `resume()`, after `addToolResult` for pending tools) or `ToolAborted` (a tool
     * threw `LbToolAbort`). [text] is the reply's text so far.
     */
    public data class Incomplete(public val reason: FinishReason, public val text: String) : Typed<Nothing>

    /** The reply is complete but [text] could not be read as the requested type; [cause] says why. */
    public data class Invalid(public val text: String, public val cause: Throwable) : Typed<Nothing>
}

/** The value of a [Typed.Value]; null for [Typed.Incomplete] and [Typed.Invalid], whose reasons it drops. */
public fun <T> Typed<T>.valueOrNull(): T? = (this as? Typed.Value<T>)?.value
