package com.suhel.llamabro.conversation

/**
 * A tool's error meant for the model: the agent loop sends `Error: <message>` as the call's result and goes on, so
 * the model can correct itself ([D62](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d62)).
 * Only this exception's message reaches the model; any other exception sends `Error: the tool failed` and reaches the
 * app as `ChatEvent.ToolResult.error`, so a failing tool can't leak a token or personal data into the prompt and the
 * saved chat unless it chooses to. Its [cause], if any, reaches the app as `ChatEvent.ToolResult.error` and not the
 * model. Open for subclassing.
 */
public open class LbToolError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Ends the turn instead of answering the model
 * ([D62](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d62)): the call that throws it and
 * every later call of the same reply get no result, and the flow ends with `Done(FinishReason.ToolAborted)` carrying
 * this exception as `Done.abort`. `resume()` continues the turn: it runs the aborted call again, then the reply's later
 * calls, then asks the model. A call that needs approval waits for approval again instead of running again. Open for
 * subclassing.
 */
public open class LbToolAbort(public val reason: String, cause: Throwable? = null) : Exception(reason, cause)
