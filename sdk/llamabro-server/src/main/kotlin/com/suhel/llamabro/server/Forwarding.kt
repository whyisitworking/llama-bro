package com.suhel.llamabro.server

import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.RawPart
import com.suhel.llamabro.exchange
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.userAgent
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.TimeSource

/**
 * The model routes: each request goes to [ServedModels.acquire]'s model through `Model.exchange`, and llama-server's
 * status, content type and bytes come back unchanged, flushed as they come. A client that hangs up cancels the
 * request, so generation stops. No queue of ours: a request beyond the model's slots waits in llama-server's own.
 */
@OptIn(InternalLlamaBroApi::class, ExperimentalAtomicApi::class)
internal class Forwarding(private val models: ServedModels, private val emit: (ServerEvent) -> Unit) {
    private val ids = AtomicLong(0)

    /** The routes [config] serves, and a 404 in OpenAI's shape for every other path, as upstream answers one. */
    fun Route.modelRoutes(config: ServerConfig) {
        routeTable.filter { !it.answeredHere && it.isServed(config) }.forEach { served ->
            route(served.path, served.method) { handle { forward(call, served, config.maxBodyBytes) } }
        }
        route("{...}") {
            handle {
                val status = HttpStatusCode.NotFound
                val client = call.request.local.remoteAddress
                emit(ServerEvent.Rejected(client, call.request.path(), status.value, "no route"))
                call.respondError(status, "File Not Found", ErrorType.NOT_FOUND)
            }
        }
    }

    private suspend fun forward(call: ApplicationCall, served: ServedRoute, maxBodyBytes: Long) {
        val request = call.request
        val client = request.local.remoteAddress
        // the checks come before a model is asked for: a refused request never holds a lease
        val body =
            when (val guarded = call.guardedBody(served.method == HttpMethod.Post, maxBodyBytes)) {
                is GuardedBody.Passed -> {
                    guarded
                }

                is GuardedBody.Refused -> {
                    emit(ServerEvent.Rejected(client, request.path(), guarded.status.value, guarded.reason))
                    call.respondError(guarded.status, guarded.message, ErrorType.INVALID_REQUEST)
                    return
                }
            }
        val id = ids.incrementAndFetch()
        emit(ServerEvent.RequestStarted(id, client, request.httpMethod.value, request.path(), request.userAgent()))
        val began = TimeSource.Monotonic.markNow()
        val outcome = Outcome()
        try {
            val lease = call.acquire(requestedModel(body.json), outcome) ?: return
            try {
                val parts = lease.model.exchange(served.endpoint, body.bytes, call.forwardedParams())
                call.respondParts(parts, served, outcome)
            } finally {
                lease.release()
            }
        } finally {
            val counts = outcome.tokens?.counts()
            val millis = began.elapsedNow().inWholeMilliseconds
            emit(ServerEvent.RequestEnded(id, outcome.status, millis, counts?.prompt, counts?.predicted))
        }
    }

    /** The lease for [id], or null once the client has its error: 404 for an id not served, 503 with none loaded. */
    private suspend fun ApplicationCall.acquire(id: String?, outcome: Outcome): ServedLease? =
        try {
            models.acquire(id)
        } catch (e: ModelUnavailable) {
            val status = if (e.id == null) HttpStatusCode.ServiceUnavailable else HttpStatusCode.NotFound
            outcome.status = status.value
            val type = if (e.id == null) ErrorType.UNAVAILABLE else ErrorType.NOT_FOUND
            respondError(status, if (e.id == null) "no model loaded" else "model '${e.id}' not found", type)
            null
        }

    // the flow runs beside the response: its first part decides the status, the rest are written as they come
    private suspend fun ApplicationCall.respondParts(parts: Flow<RawPart>, served: ServedRoute, outcome: Outcome) =
        coroutineScope {
            val channel = Channel<RawPart>()
            val producer =
                launch {
                    try {
                        parts.collect { channel.send(it) }
                        channel.close()
                    } catch (e: LlamaBroException) {
                        // the model closed or failed: the response decides what the client sees, not a crash
                        channel.close(e)
                    }
                }
            try {
                val first = channel.receiveCatching()
                val failure = first.exceptionOrNull()
                when {
                    failure != null -> respondFailure(failure, outcome)
                    first.isClosed -> respondEmpty(served.endpoint, outcome)
                    else -> stream(first.getOrThrow() as RawPart.Head, channel, outcome)
                }
            } finally {
                // a client that hung up, or a write that failed: the request is cancelled, generation stops
                producer.cancel()
            }
        }

    private suspend fun ApplicationCall.stream(head: RawPart.Head, parts: ReceiveChannel<RawPart>, outcome: Outcome) {
        outcome.status = head.status
        val tokens = TokenCounts(streaming = head.contentType.startsWith(EVENT_STREAM))
        outcome.tokens = tokens
        respondBytesWriter(ContentType.parse(head.contentType), HttpStatusCode.fromValue(head.status)) {
            try {
                for (part in parts) {
                    if (part !is RawPart.Body) continue
                    writeFully(part.bytes)
                    flush()
                    tokens.feed(part.bytes)
                }
            } catch (_: LlamaBroException) {
                // the model closed after the status went out: it can't change now, so the stream ends cleanly
            }
        }
    }

    private suspend fun ApplicationCall.respondFailure(failure: Throwable, outcome: Outcome) {
        if (failure !is LlamaBroException) throw failure
        val closed = failure is LlamaBroException.ModelClosed
        val status = if (closed) HttpStatusCode.ServiceUnavailable else HttpStatusCode.InternalServerError
        outcome.status = status.value
        val message = if (closed) "the model was closed" else failure.message ?: "the model failed"
        respondError(status, message, if (closed) ErrorType.UNAVAILABLE else ErrorType.SERVER)
    }

    // upstream always writes a chunk; a response with none still gets the route's content type and an empty body
    private suspend fun ApplicationCall.respondEmpty(endpoint: Endpoint, outcome: Outcome) {
        outcome.status = HttpStatusCode.OK.value
        val type = if (endpoint == Endpoint.Metrics) METRICS else ContentType.Application.Json
        respondBytes(ByteArray(0), type, HttpStatusCode.OK)
    }

    /** What the request ended with, for [ServerEvent.RequestEnded]: a 500 until something answers. */
    private class Outcome {
        var status: Int = HttpStatusCode.InternalServerError.value
        var tokens: TokenCounts? = null
    }

    private companion object {
        const val EVENT_STREAM = "text/event-stream"
        val METRICS = ContentType.parse("text/plain; version=0.0.4")
    }
}

/** The body's `model` field, which picks the model; null for no body or no field. */
internal fun requestedModel(body: JsonElement?): String? =
    ((body as? JsonObject)?.get("model") as? JsonPrimitive)?.contentOrNull

/** Path and query parameters, `id_slot` and `action` for `POST /slots/:id_slot`, as `Model.exchange` takes them. */
private fun ApplicationCall.forwardedParams(): Map<String, String> =
    parameters.entries().associate { (name, values) -> name to values.first() }
