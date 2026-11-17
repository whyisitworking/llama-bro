package com.suhel.llamabro

import com.suhel.llamabro.chat.FinishReason

/**
 * The outcome of a reply the application asked for as a value: [Value], or why there is none. Engine failures
 * (context exceeded, a closed model, cancellation) are not outcomes of the reply and stay exceptions.
 */
public sealed interface Typed<out T> {
    // regular classes, not data classes: a field can be added later without a binary break (no copy, no componentN)

    /** A complete reply. [json] is the reply's text and [reasoning] its reasoning, `null` when it has none. */
    public class Value<out T>(public val value: T, public val json: String, public val reasoning: String?) : Typed<T> {
        override fun equals(other: Any?): Boolean =
            other is Value<*> && value == other.value && json == other.json && reasoning == other.reasoning

        override fun hashCode(): Int =
            (((value?.hashCode() ?: 0) * 31) + json.hashCode()) * 31 + (reasoning?.hashCode() ?: 0)

        override fun toString(): String = "Value(value=$value, json=$json, reasoning=$reasoning)"
    }

    /**
     * The reply is not a complete answer: [reason] is `Length` (cut at the output limit), `TurnLimit`,
     * `ToolCallsPending` (continue with `resume()`, after `addToolResult` for pending tools) or `ToolAborted` (a tool
     * threw `LbToolAbort`). [json] is the reply's text so far.
     */
    public class Incomplete(public val reason: FinishReason, public val json: String) : Typed<Nothing> {
        override fun equals(other: Any?): Boolean = other is Incomplete && reason == other.reason && json == other.json

        override fun hashCode(): Int = reason.hashCode() * 31 + json.hashCode()

        override fun toString(): String = "Incomplete(reason=$reason, json=$json)"
    }

    /** The reply is complete but [json] could not be read as the requested type; [cause] says why. */
    public class Invalid(public val json: String, public val cause: Throwable) : Typed<Nothing> {
        override fun equals(other: Any?): Boolean = other is Invalid && json == other.json && cause == other.cause

        override fun hashCode(): Int = json.hashCode() * 31 + cause.hashCode()

        override fun toString(): String = "Invalid(json=$json, cause=$cause)"
    }
}

/** The value of a [Typed.Value]; null for [Typed.Incomplete] and [Typed.Invalid], whose reasons it drops. */
public fun <T> Typed<T>.valueOrNull(): T? = (this as? Typed.Value<T>)?.value
