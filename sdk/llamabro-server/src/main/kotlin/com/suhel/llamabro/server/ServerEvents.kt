package com.suhel.llamabro.server

/** Where a [LlamaBroServer] is. */
public sealed interface ServerState {
    public data object Stopped : ServerState

    public data object Starting : ServerState

    /** Listening on [port], the real one when the config asked for 0. */
    public data class Serving(val port: Int) : ServerState

    /** [LlamaBroServer.start] could not bind, e.g. the port is in use. */
    public data class Failed(val reason: Throwable) : ServerState
}

/** What the server did, for an app's request log. */
public sealed interface ServerEvent {
    public data class RequestStarted(
        val id: Long,
        val client: String,
        val method: String,
        val path: String,
        val userAgent: String?,
    ) : ServerEvent

    /** The token counts come from the final `timings` or `usage` object when the body has one, else null. */
    public data class RequestEnded(
        val id: Long,
        val status: Int,
        val millis: Long,
        val promptTokens: Int?,
        val predictedTokens: Int?,
    ) : ServerEvent

    /** A request turned away before it reached a model. */
    public data class Rejected(val client: String, val path: String, val status: Int, val reason: String) :
        ServerEvent
}
