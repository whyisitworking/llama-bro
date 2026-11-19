package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.conversation.ConversationJson
import com.suhel.llamabro.internal.ChatJson
import com.suhel.llamabro.internal.ChatStreamDecoder
import com.suhel.llamabro.internal.ErrorMapping
import com.suhel.llamabro.internal.Media
import com.suhel.llamabro.internal.MediaIo
import com.suhel.llamabro.internal.RealtimeLink
import com.suhel.llamabro.internal.SseParser
import com.suhel.llamabro.spi.EngineAttachment
import com.suhel.llamabro.spi.EngineModel
import com.suhel.llamabro.spi.EngineRequest
import com.suhel.llamabro.spi.NativeError
import com.suhel.llamabro.spi.RequestSink
import com.suhel.llamabro.spi.SEND_OK
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A loaded model: one llama-server core on its own worker thread (design §3.2). Close it when done; [close] is safe
 * on the main thread.
 */
public class Model internal constructor(
    private val engineModel: EngineModel,
    /** Where the model runs and why ([ModelConfig.devicePolicy]). */
    public val loadReport: LoadReport,
    /** Runs the native shutdown off the caller's thread (LlamaBroRuntime.shutdown). */
    private val shutdown: (() -> Unit) -> Deferred<Unit>,
    /** What it was loaded for ([ModelConfig.task]): `embed`, `rerank` and `transcribe` check it first (D65). */
    internal val task: ModelTask,
    /**
     * `lb_read_fd`, for a chat's descriptor, read once (D84), and where a request with attachments starts: the native
     * side reads files in the call, so a slow descriptor (a remote `content://`) must not block the caller's thread.
     */
    private val mediaIo: MediaIo,
) : AutoCloseable {
    /** Where file reads and requests with attachments run (D84). */
    internal val io get() = mediaIo.dispatcher

    /** What the loaded model is and what it supports, read once at load. */
    public val info: ModelInfo = ModelInfo.parse(engineModel.infoJson)

    private val closed = AtomicBoolean(false)
    private val gate =
        java.util.concurrent.locks
            .ReentrantReadWriteLock()
    private val closedSignal = CompletableDeferred<Unit>()
    private val active = mutableSetOf<ActiveCall>()

    /** Completes when the native shutdown has finished; exists before [close] so a racing [closeAndJoin] waits too. */
    private val shutdownDone = CompletableDeferred<Unit>()

    /**
     * Any llama-server endpoint with a raw JSON body, exactly as llama-server's docs describe it.
     * A streaming body (`"stream": true`) emits each SSE `data:` payload until `[DONE]`; any other body emits
     * the whole response once. Errors fail the flow with a [LlamaBroException]. Cancelling the collector
     * cancels the request.
     *
     * @param params path and query parameters, e.g. `id_slot` and `action` for [Endpoint.SlotsAction].
     */
    public fun rawRequest(
        endpoint: Endpoint,
        jsonBody: String,
        params: Map<String, String> = emptyMap(),
    ): Flow<String> = request(endpoint, jsonBody, emptyList(), params)

    /**
     * [rawRequest] with [attachments] beside the body (D84). With attachments the native call starts on [io], since
     * it reads them before it returns.
     */
    internal fun request(
        endpoint: Endpoint,
        jsonBody: String,
        attachments: List<EngineAttachment>,
        params: Map<String, String> = emptyMap(),
    ): Flow<String> {
        val body = if (isStreamBody(jsonBody)) BodyMode.Sse else BodyMode.Whole
        val flow = requestFlow(endpoint, jsonBody, attachments, params, body) {}
        return if (attachments.isEmpty()) flow else flow.flowOn(io)
    }

    /**
     * A realtime conversation (`/v1/realtime`, D97): emits each server event as it comes and ends when the server
     * closes it; cancelling the collector hangs up. [onOpen] gets the link for the client's events before the native
     * session starts, so events sent at once are queued and go first.
     */
    internal fun realtime(params: Map<String, String>, onOpen: (RealtimeLink) -> Unit): Flow<String> =
        requestFlow(Endpoint.Realtime, "", emptyList(), params, BodyMode.Events, onOpen)

    /** A descriptor's bytes, read once on [io] (D84); one that can't be read is the request's fault. */
    internal suspend fun read(descriptor: MediaSource.Descriptor): ByteArray = withContext(io) { readNow(descriptor) }

    /** [read] on the caller's thread, for `Conversation.toJson`, which is not suspending. */
    internal fun readNow(descriptor: MediaSource.Descriptor): ByteArray =
        try {
            mediaIo.readFd(Media.fdOf(descriptor))
        } catch (e: NativeError) {
            throw LlamaBroException.InvalidRequest(ErrorMapping.nativeMessage(e.errorJson), e)
        }

    private fun requestFlow(
        endpoint: Endpoint,
        jsonBody: String,
        attachments: List<EngineAttachment>,
        params: Map<String, String>,
        mode: BodyMode,
        onOpen: (RealtimeLink) -> Unit,
    ): Flow<String> =
        callbackFlow {
            val call = ActiveCall(this)
            onOpen(call)
            val paramsJson =
                if (params.isEmpty()) {
                    null
                } else {
                    buildJsonObject {
                        params.forEach { (k, v) ->
                            put(k, v)
                        }
                    }.toString()
                }
            val handle =
                try {
                    // the read side of [gate]: close() can't free the native model while this call is entering it
                    gate.read {
                        if (!register(call)) throw LlamaBroException.ModelClosed()
                        engineModel.request(
                            endpoint.nativeName,
                            paramsJson,
                            jsonBody.toByteArray(Charsets.UTF_8),
                            attachments,
                            call.sink(mode),
                        )
                    }
                } catch (e: NativeError) {
                    unregister(call)
                    throw if (closed.get()) {
                        LlamaBroException.ModelClosed(
                            e,
                        )
                    } else {
                        LlamaBroException.InvalidRequest(ErrorMapping.nativeMessage(e.errorJson), e)
                    }
                } catch (e: IllegalStateException) {
                    unregister(call)
                    throw LlamaBroException.ModelClosed(e)
                }
            call.setHandle(handle)
            awaitClose {
                call.cancelIfRunning()
                unregister(call)
            }
        }.buffer(Channel.UNLIMITED)

    /**
     * Closes the model and returns at once: running requests fail with [LlamaBroException.ModelClosed], later calls
     * fail the same way, and the native shutdown (cancel, join the worker, free the weights and KV cache) finishes
     * in the background. The next `loadModel` waits for it, so two models are never in memory together. Use
     * [closeAndJoin] to wait for the memory to be freed. Calling it again does nothing.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        failActive()
        closedSignal.complete(Unit)
        background.cancel()
        shutdown {
            try {
                // waits for any request still entering native code (a closed check alone leaves a use-after-free
                // window)
                gate.write { }
                failActive() // one that registered just before the closed flag was set
                engineModel.close()
            } finally {
                shutdownDone.complete(Unit)
            }
        }
    }

    /**
     * [close], then suspends until the native shutdown has finished and the model's memory is freed. A streaming
     * request notices the cancel at its next result or within about 1 s (llama-server's polling interval, F23).
     */
    public suspend fun closeAndJoin() {
        close()
        shutdownDone.await()
    }

    private fun failActive() {
        val calls = synchronized(active) { active.toList() }
        calls.forEach { it.scope.close(LlamaBroException.ModelClosed()) }
    }

    internal val isClosed: Boolean get() = closed.get()

    // --- background pre-encode (Conversation) ---------------------------------------------------------

    /** Runs Conversation's background pre-encodes; cancelled when the model closes. */
    internal val background: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val preEncodeLock = Any()
    private var preEncodeJob: Job? = null

    /**
     * One pre-encode per model, like tools/ui's store-wide preEncodeAbortController: a new one replaces the old,
     * and any chat request cancels it ([cancelPreEncode]), whichever conversation started it.
     */
    internal fun startPreEncode(block: suspend CoroutineScope.() -> Unit) {
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

    private fun register(call: ActiveCall): Boolean =
        synchronized(active) {
            if (closed.get()) false else active.add(call)
        }

    private fun unregister(call: ActiveCall) {
        synchronized(active) { active.remove(call) }
    }

    /**
     * One request's state. The native handle may arrive after the first callbacks, and free() must follow
     * the terminal event, while cancel() must never touch a freed request: all three go through [lock].
     */
    private class ActiveCall(val scope: ProducerScope<String>) : RealtimeLink {
        private val lock = Any()
        private var handle: EngineRequest? = null
        private var terminal = false
        private var cancelRequested = false

        /** Client events sent before the handle arrived, sent in order once it has. */
        private val early = mutableListOf<ByteArray>()

        fun setHandle(h: EngineRequest) {
            synchronized(lock) {
                handle = h
                if (terminal) {
                    h.free()
                } else if (cancelRequested) {
                    h.cancel()
                } else {
                    early.forEach { h.send(it) }
                }
                early.clear()
            }
        }

        override fun send(message: String): Boolean =
            synchronized(lock) {
                if (terminal || cancelRequested) return false
                val bytes = message.toByteArray(Charsets.UTF_8)
                val h = handle
                if (h == null) {
                    early += bytes
                    true
                } else {
                    h.send(bytes) == SEND_OK
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

        fun sink(mode: BodyMode): RequestSink =
            object : RequestSink {
                private val parser = SseParser()
                private val whole = ByteArrayOutputStream()
                private var failed = false

                override fun onChunk(httpStatus: Int, data: ByteArray) {
                    if (failed) return
                    if (httpStatus >= HTTP_ERROR_FROM) {
                        fail(ErrorMapping.fromBody(httpStatus, String(data, Charsets.UTF_8)))
                    } else if (mode == BodyMode.Events) {
                        scope.trySend(String(data, Charsets.UTF_8))
                    } else if (mode == BodyMode.Whole) {
                        whole.write(data)
                    } else {
                        for (payload in parser.feed(data)) {
                            when {
                                payload == "[DONE]" -> {
                                    Unit
                                }

                                // the flow completes at the terminal event
                                payload.startsWith("{\"error\"") -> {
                                    fail(ErrorMapping.fromBody(httpStatus, payload))
                                    return
                                }

                                else -> {
                                    scope.trySend(payload)
                                }
                            }
                        }
                    }
                }

                override fun onDone() {
                    if (mode == BodyMode.Whole && !failed &&
                        whole.size() > 0
                    ) {
                        scope.trySend(whole.toString(Charsets.UTF_8.name()))
                    }
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

    /** How a response's chunks become the flow's strings. */
    private enum class BodyMode {
        /** The whole body, once, at the end. */
        Whole,

        /** Each SSE `data:` payload until `[DONE]`. */
        Sse,

        /** Each chunk as it comes: a realtime conversation's events (D97). */
        Events,
    }

    private companion object {
        fun isStreamBody(body: String): Boolean =
            runCatching {
                ((Json.parseToJsonElement(body) as JsonObject)["stream"] as? JsonPrimitive)?.booleanOrNull == true
            }.getOrDefault(false)
    }
}

/**
 * A typed chat completion (design §6.1, level 2). Always streams; emits [ChatEvent]s
 * ending with exactly one `Done`. Errors fail the flow with a [LlamaBroException].
 */
public fun Model.chat(request: ChatRequest): Flow<ChatEvent> = chatInternal(request, null)

/** [Model.chat], reporting the stream's completion id as soon as it's known (Conversation.endReasoning). */
internal fun Model.chatInternal(request: ChatRequest, onCompletionId: ((String) -> Unit)?): Flow<ChatEvent> =
    flow {
        Media.checkMessages(info, request.messages)
        cancelPreEncode() // tools/ui cancels a pending pre-encode before every chat request
        val decoder = ChatStreamDecoder()
        var reported = false
        val encoded = ChatJson.encode(request)
        request(Endpoint.ChatCompletions, encoded.body, encoded.attachments).collect { payload ->
            val events = ErrorMapping.readReply("chat") { decoder.accept(payload) }
            if (!reported && onCompletionId != null) {
                decoder.completionId?.let {
                    reported = true
                    onCompletionId(it)
                }
            }
            events.forEach { emit(it) }
        }
        decoder.finish().forEach { emit(it) }
    }

/**
 * A new [com.suhel.llamabro.conversation.Conversation] (design §6.1, level 1), with an optional system prompt.
 *
 * [history] starts it from messages the app stored itself (F96): user, assistant, tool-result and compaction
 * messages, in order, without the system prompt, which has its own parameter. The next request sends them before the
 * new message. To continue a conversation saved with `toJson`, use [restoreConversation].
 *
 * @throws IllegalArgumentException if [history] contains a system message.
 */
public fun Model.conversation(
    system: String? = null,
    history: List<Message> = emptyList(),
    configure: Conversation.Builder.() -> Unit = {},
): Conversation {
    require(history.none { it is Message.System }) {
        "history must not contain a system message; pass the system prompt as `system`"
    }
    return Conversation(
        this,
        system,
        history.toList(),
        Conversation
            .Builder()
            .apply(configure),
    )
}

/**
 * Restores a conversation saved with [com.suhel.llamabro.conversation.Conversation.toJson], with its saved
 * settings. Register the tools again in [configure] (handlers aren't saved); anything else set there changes the
 * saved settings. With the same tools, the next request is byte-identical to the original's, so the server's
 * prompt cache still hits.
 *
 * @throws IllegalArgumentException if [json] isn't a saved conversation.
 */
public fun Model.restoreConversation(json: String, configure: Conversation.Builder.() -> Unit = {}): Conversation {
    val saved =
        ConversationJson
            .decode(json)
    val builder =
        Conversation
            .Builder()
            .apply(saved.settings)
            .apply(configure)
    return com.suhel.llamabro.conversation
        .Conversation(this, saved.system, saved.messages, builder)
}

/** From this HTTP status up, a response is an error. */
private const val HTTP_ERROR_FROM = 400
