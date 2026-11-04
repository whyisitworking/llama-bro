package com.suhel.llamabro

import com.suhel.llamabro.internal.EngineModel
import com.suhel.llamabro.internal.EngineRequest
import com.suhel.llamabro.internal.ErrorMapping
import com.suhel.llamabro.internal.NativeError
import com.suhel.llamabro.internal.RequestSink
import com.suhel.llamabro.internal.SseParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.read
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.concurrent.write

/** A loaded model: one llama-server core on its own worker thread (design §3.2). Close it when done. */
public class Model internal constructor(private val engineModel: EngineModel) : AutoCloseable {

    public val info: ModelInfo = ModelInfo.parse(engineModel.infoJson)

    private val closed = AtomicBoolean(false)
    private val gate = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val closedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val active = mutableSetOf<ActiveCall>()

    /**
     * Any llama-server endpoint with a raw JSON body, exactly as llama-server's docs describe it.
     * A streaming body (`"stream": true`) emits each SSE `data:` payload until `[DONE]`; any other body emits
     * the whole response once. Errors fail the flow with a [LlamaBroException]. Cancelling the collector
     * cancels the request.
     *
     * @param params path and query parameters, e.g. `id_slot` and `action` for [Endpoint.SlotsAction].
     */
    public fun rawRequest(endpoint: Endpoint, jsonBody: String, params: Map<String, String> = emptyMap()): Flow<String> =
        callbackFlow {
            val call = ActiveCall(this)
            val stream = isStreamBody(jsonBody)
            val paramsJson = if (params.isEmpty()) null else buildJsonObject { params.forEach { (k, v) -> put(k, v) } }.toString()
            val handle = try {
                // the read side of [gate]: close() can't free the native model while this call is entering it
                gate.read {
                    if (!register(call)) throw LlamaBroException.ModelClosed()
                    engineModel.request(endpoint.nativeName, paramsJson, jsonBody.toByteArray(Charsets.UTF_8), call.sink(stream))
                }
            } catch (e: NativeError) {
                unregister(call)
                throw if (closed.get()) LlamaBroException.ModelClosed() else LlamaBroException.InvalidRequest(ErrorMapping.nativeMessage(e.errorJson))
            } catch (e: IllegalStateException) {
                unregister(call)
                throw LlamaBroException.ModelClosed()
            }
            call.setHandle(handle)
            awaitClose {
                call.cancelIfRunning()
                unregister(call)
            }
        }.buffer(Channel.UNLIMITED)

    /**
     * Cancels every running request (their flows fail with [LlamaBroException.ModelClosed]), waits for the
     * native side to finish them, and frees the model. Later calls fail with `ModelClosed`.
     *
     * Blocks: a streaming request notices the cancel at its next result or within about 1 s (llama-server's
     * polling interval, findings F23), and the model's worker thread is joined. Call it off the main thread.
     */
    @androidx.annotation.WorkerThread
    override fun close() {
        // waits for any request that is entering native code (a closed check alone leaves a use-after-free window)
        if (!gate.write { closed.compareAndSet(false, true) }) return
        val calls = synchronized(active) { active.toList() }
        calls.forEach { it.scope.close(LlamaBroException.ModelClosed()) }
        closedSignal.complete(Unit)
        background.cancel()
        engineModel.close()
    }

    internal val isClosed: Boolean get() = closed.get()

    // --- background pre-encode (Conversation) ---------------------------------------------------------

    /** Runs Conversation's background pre-encodes; cancelled when the model closes. */
    internal val background: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    private val preEncodeLock = Any()
    private var preEncodeJob: kotlinx.coroutines.Job? = null

    /**
     * One pre-encode per model, like tools/ui's store-wide preEncodeAbortController: a new one replaces the old,
     * and any chat request cancels it ([cancelPreEncode]), whichever conversation started it.
     */
    internal fun startPreEncode(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        synchronized(preEncodeLock) {
            preEncodeJob?.cancel()
            preEncodeJob = background.launch(block = block)
        }
    }

    internal suspend fun cancelPreEncode() {
        val job = synchronized(preEncodeLock) { preEncodeJob.also { preEncodeJob = null } }
        job?.cancelAndJoin()
    }

    /** Returns when [close] is called (Conversation cancels a running tool handler on it). */
    internal suspend fun awaitClosed() = closedSignal.await()

    private fun register(call: ActiveCall): Boolean = synchronized(active) {
        if (closed.get()) false else active.add(call)
    }

    private fun unregister(call: ActiveCall) {
        synchronized(active) { active.remove(call) }
    }

    /**
     * One request's state. The native handle may arrive after the first callbacks, and free() must follow
     * the terminal event, while cancel() must never touch a freed request: all three go through [lock].
     */
    private class ActiveCall(val scope: ProducerScope<String>) {
        private val lock = Any()
        private var handle: EngineRequest? = null
        private var terminal = false
        private var cancelRequested = false

        fun setHandle(h: EngineRequest) {
            synchronized(lock) {
                handle = h
                if (terminal) h.free() else if (cancelRequested) h.cancel()
            }
        }

        fun cancelIfRunning() {
            synchronized(lock) {
                if (terminal) return
                val h = handle
                if (h == null) cancelRequested = true else h.cancel()
            }
        }

        private fun onTerminal() {
            synchronized(lock) {
                if (terminal) return
                terminal = true
                handle?.free()
            }
        }

        fun sink(stream: Boolean): RequestSink = object : RequestSink {
            private val parser = SseParser()
            private val whole = ByteArrayOutputStream()
            private var failed = false

            override fun onChunk(httpStatus: Int, data: ByteArray) {
                if (failed) return
                if (httpStatus >= 400) {
                    fail(ErrorMapping.fromBody(httpStatus, String(data, Charsets.UTF_8)))
                } else if (!stream) {
                    whole.write(data)
                } else {
                    for (payload in parser.feed(data)) {
                        when {
                            payload == "[DONE]" -> Unit  // the flow completes at the terminal event
                            payload.startsWith("{\"error\"") -> { fail(ErrorMapping.fromBody(httpStatus, payload)); return }
                            else -> scope.trySend(payload)
                        }
                    }
                }
            }

            override fun onDone() {
                if (!stream && !failed && whole.size() > 0) scope.trySend(whole.toString(Charsets.UTF_8.name()))
                onTerminal()
                scope.close()
            }

            override fun onCancelled() {
                onTerminal()
                scope.close()
            }

            private fun fail(e: LlamaBroException) {
                failed = true
                scope.close(e)
            }
        }
    }

    private companion object {
        fun isStreamBody(body: String): Boolean = runCatching {
            ((Json.parseToJsonElement(body) as JsonObject)["stream"] as? JsonPrimitive)?.booleanOrNull == true
        }.getOrDefault(false)
    }
}

/**
 * A typed chat completion (design §6.1, level 2). Always streams; emits [com.suhel.llamabro.chat.ChatEvent]s
 * ending with exactly one `Done`. Errors fail the flow with a [LlamaBroException].
 */
public fun Model.chat(request: com.suhel.llamabro.chat.ChatRequest): Flow<com.suhel.llamabro.chat.ChatEvent> =
    chatInternal(request, null)

/** [Model.chat], reporting the stream's completion id as soon as it's known (Conversation.endReasoning). */
internal fun Model.chatInternal(
    request: com.suhel.llamabro.chat.ChatRequest,
    onCompletionId: ((String) -> Unit)?,
): Flow<com.suhel.llamabro.chat.ChatEvent> = kotlinx.coroutines.flow.flow {
    cancelPreEncode()  // tools/ui cancels a pending pre-encode before every chat request
    val decoder = com.suhel.llamabro.internal.ChatStreamDecoder()
    var reported = false
    rawRequest(Endpoint.ChatCompletions, com.suhel.llamabro.internal.ChatJson.body(request)).collect { payload ->
        val events = decoder.accept(payload)
        if (!reported && onCompletionId != null) decoder.completionId?.let { reported = true; onCompletionId(it) }
        events.forEach { emit(it) }
    }
    decoder.finish().forEach { emit(it) }
}

/** A new [com.suhel.llamabro.conversation.Conversation] (design §6.1, level 1), with an optional system prompt. */
public fun Model.conversation(
    system: String? = null,
    configure: com.suhel.llamabro.conversation.Conversation.Builder.() -> Unit = {},
): com.suhel.llamabro.conversation.Conversation =
    com.suhel.llamabro.conversation.Conversation(this, system, emptyList(), com.suhel.llamabro.conversation.Conversation.Builder().apply(configure))

/**
 * Restores a conversation saved with [com.suhel.llamabro.conversation.Conversation.toJson]. Pass the same
 * [configure] block as the original (sampling, reasoning, tools): they aren't saved, and the next request is
 * byte-identical to the original's (so the server's prompt cache still hits) only when they match.
 *
 * @throws IllegalArgumentException if [json] isn't a saved conversation.
 */
public fun Model.restoreConversation(
    json: String,
    configure: com.suhel.llamabro.conversation.Conversation.Builder.() -> Unit = {},
): com.suhel.llamabro.conversation.Conversation {
    val (system, messages) = com.suhel.llamabro.conversation.ConversationJson.decode(json)
    return com.suhel.llamabro.conversation.Conversation(this, system, messages, com.suhel.llamabro.conversation.Conversation.Builder().apply(configure))
}
