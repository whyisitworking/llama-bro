package com.suhel.llamabro

import com.suhel.llamabro.internal.HttpStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [Model.rawRequest] for a server in front of the model
 * ([D155](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d155)):
 * llama-server's status and body bytes as its handler wrote them, SSE `event:` lines and JSON error bodies
 * included, where `rawRequest` keeps only `data:` payloads and fails on an error status.
 * One [RawPart.Head] first, then the [RawPart.Body] parts; the flow ends when the response does.
 * An error status is a head and its body, not an exception. Cancelling the collector cancels the request; a closed
 * model fails the flow with [LlamaBroException.ModelClosed], as `rawRequest` does.
 */
@InternalLlamaBroApi
public fun Model.exchange(
    endpoint: Endpoint,
    body: ByteArray,
    params: Map<String, String> = emptyMap(),
): Flow<RawPart> =
    flow {
        val streaming = Model.isStreamBody(body.decodeToString())
        var headSent = false
        requestStatused(endpoint, body, params).collect { chunk ->
            if (!headSent) {
                headSent = true
                emit(RawPart.Head(chunk.status, contentType(endpoint, streaming, chunk.status)))
            }
            if (chunk.bytes.isNotEmpty()) emit(RawPart.Body(chunk.bytes))
        }
    }

/** The content type follows from the route and the body, as upstream sets it, errors included. */
private fun contentType(endpoint: Endpoint, streaming: Boolean, status: Int): String =
    when {
        endpoint == Endpoint.Metrics -> "text/plain; version=0.0.4"
        streaming && status == HttpStatus.OK -> "text/event-stream"
        else -> "application/json"
    }

/** What [exchange] emits. */
@InternalLlamaBroApi
public sealed interface RawPart {
    /** First: the status llama-server answered and its content type (`text/event-stream` when it streams). */
    public data class Head(val status: Int, val contentType: String) : RawPart

    /** The body's bytes as the handler produced them, SSE framing included. */
    public class Body(public val bytes: ByteArray) : RawPart
}
