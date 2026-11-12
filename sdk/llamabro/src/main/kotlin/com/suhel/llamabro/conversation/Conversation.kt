package com.suhel.llamabro.conversation

import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.RequestOptions
import com.suhel.llamabro.chat.Tool
import com.suhel.llamabro.chat.ToolCall
import com.suhel.llamabro.chatInternal
import com.suhel.llamabro.internal.ChatJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * A chat history that behaves like llama.cpp's own web client (`tools/ui`, design §6–§7, D8): it builds each
 * request from the history, appends replies, and only ever appends, so the server reuses its cached prefix.
 *
 * Every request carries the conversation's [Builder.defaults]; one [send], [resume] or [regenerate] can change
 * them for its own requests only. One operation at a time: collecting [send], [resume], [regenerate] (or `compact`)
 * while another runs ([isBusy]) fails with [LlamaBroException.Busy]; creating the flow never throws.
 */
public class Conversation internal constructor(
    private val model: Model,
    private val system: String?,
    restored: List<Message>,
    private val config: Builder,
) {
    private val _messages = MutableStateFlow(listOfNotNull(system?.let { Message.System(it) }) + restored)

    /** The full history, including the system message (first) and compaction nodes. */
    public val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _contextUsage = MutableStateFlow(ContextUsage(0, model.info.contextSize))

    /** Tokens in use of the context, from the last reply's timings. */
    public val contextUsage: StateFlow<ContextUsage> = _contextUsage.asStateFlow()

    private val _reply = MutableStateFlow<Message.Assistant?>(null)

    /**
     * The assistant message streaming now (content and reasoning so far), null when no reply is streaming. When a
     * reply ends it moves to [messages] first, then this turns null, so a UI showing both never shows it twice
     * or not at all.
     */
    public val reply: StateFlow<Message.Assistant?> = _reply.asStateFlow()

    private val busy = MutableStateFlow(false)

    /** True while a send, resume, regenerate or compaction runs. */
    public val isBusy: StateFlow<Boolean> = busy.asStateFlow()

    @Volatile
    private var completionId: String? = null

    /**
     * Appends a user message and streams the reply.
     * @param options changes the [Builder.defaults] for this send's requests only (every turn of its tool loop).
     */
    public fun send(
        text: String,
        vararg attachments: Content,
        options: RequestOptions.Builder.() -> Unit = {},
    ): Flow<ChatEvent> {
        val parts = listOf<Content>(Content.Text(text)) + attachments
        val opts = config.defaults.copy(options)
        return exclusive {
            append(Message.User(parts))
            runTurns(this, opts)
        }
    }

    /**
     * [send], collected: returns the final reply's content.
     * @throws LlamaBroException.Incomplete if the reply isn't a complete answer: `Length` (cut at the output limit),
     *   `TurnLimit` or `ToolCallsPending` (continue with [resume], after [addToolResult] for pending tools).
     */
    public suspend fun ask(text: String, options: RequestOptions.Builder.() -> Unit = {}): String {
        var done: ChatEvent.Done? = null
        send(text, options = options).collect { if (it is ChatEvent.Done) done = it }
        val history = _messages.value
        // this send's last reply: after its user message, so an older reply never counts
        val reply =
            history
                .subList(history.indexOfLast { it is Message.User } + 1, history.size)
                .lastOrNull { it is Message.Assistant } as Message.Assistant?
        val content = reply?.content.orEmpty()
        val reason = done?.reason ?: FinishReason.Stop
        if (reason == FinishReason.Length || reason == FinishReason.TurnLimit ||
            reason == FinishReason.ToolCallsPending
        ) {
            throw LlamaBroException.Incomplete(reason, content)
        }
        return content
    }

    /**
     * Sends the current history again: after `ContextExceeded` (and a compaction), a turn limit, or pending tool
     * results.
     */
    public fun resume(options: RequestOptions.Builder.() -> Unit = {}): Flow<ChatEvent> {
        val opts = config.defaults.copy(options)
        return exclusive { runTurns(this, opts) }
    }

    /**
     * Drops the replies after the last user message (with their tool calls and results) and asks again, as
     * tools/ui's regenerate does. The request repeats the original's messages, so the server's cache covers them.
     * @throws IllegalStateException (when collected) if there is no user message, or a compaction follows it.
     */
    public fun regenerate(options: RequestOptions.Builder.() -> Unit = {}): Flow<ChatEvent> {
        val opts = config.defaults.copy(options)
        return exclusive {
            val history = _messages.value
            val lastUser = history.indexOfLast { it is Message.User }
            check(lastUser >= 0) { "nothing to regenerate: no user message" }
            check(history.subList(lastUser + 1, history.size).none { it is Message.Compaction }) {
                "can't regenerate: a compaction follows the last user message"
            }
            _messages.value = history.subList(0, lastUser + 1)
            runTurns(this, opts)
        }
    }

    /** Asks the server to stop thinking now (llama-server's `reasoning_end` control) for the reply streaming now. */
    public suspend fun endReasoning() {
        val id = completionId ?: return
        val body =
            buildJsonObject {
                put("id", id)
                put("action", "reasoning_end")
            }.toString()
        model.rawRequest(Endpoint.Control, body).collect()
    }

    /**
     * The history and settings as JSON; restore with `model.restoreConversation(json)`. Saved: the system prompt,
     * every message, [Builder.defaults], `maxTurns`, `preEncodeAfterReply`. Not saved: tools, whose handlers are
     * code; register them again when restoring.
     */
    public fun toJson(): String =
        ConversationJson.encode(
            system,
            _messages.value.drop(
                if (system != null) {
                    1
                } else {
                    0
                },
            ),
            config,
        )

    /** The scope of `model.conversation { … }`: defaults, limits and tools. */
    public class Builder internal constructor() {
        /** Sent with every request; unset options use the model's and llama-server's defaults. */
        public var defaults: RequestOptions = RequestOptions.Default

        /** Changes [defaults] in place: `defaults { sampling { temperature = 0.7f }; stop("END") }`. */
        public fun defaults(configure: RequestOptions.Builder.() -> Unit) {
            defaults = defaults.copy(configure)
        }

        /** Model requests per send before `Done(TurnLimit)` (tools/ui `agenticMaxTurns`, default 100). */
        public var maxTurns: Int = 100

        /** Pre-encode the history after each reply (tools/ui "Pre-fill KV cache after response", default off). */
        public var preEncodeAfterReply: Boolean = false

        internal val registry = ToolRegistry()

        /**
         * Registers a tool (design §6.2). With a [handler], the agent loop runs it and sends its result back;
         * a thrown exception becomes `Error: <message>`. Without one, a call to it stops the loop with
         * `Done(ToolCallsPending)`: add results with [addToolResult], then [resume].
         *
         * @param schema the parameters' JSON Schema object, as a string.
         */
        public fun tool(
            name: String,
            description: String,
            schema: String,
            handler: (suspend (JsonArgs) -> String)? = null,
        ) {
            registry.add(Tool.function(name, description, schema), handler)
        }
    }

    /** tools/ui stores/chat/index.svelte.ts compaction (see [compact]). */
    internal fun compactInternal(preservedSteps: Int, instruction: String): Flow<ChatEvent> {
        require(preservedSteps >= 0) { "preservedSteps must be >= 0" }
        return exclusive {
            val source = CompactionPlanner.modelView(_messages.value)
            val cut =
                CompactionPlanner.cutIndex(source, preservedSteps)
                    ?: throw IllegalStateException(
                        "nothing to compact: no step before the $preservedSteps preserved ones",
                    )
            // the request the chat sends, cut before the preserved steps, plus the instruction as a user message
            val req = request(requestMessages(source.subList(0, cut)) + Message.user(instruction), compactionOptions())
            val summary = StringBuilder()
            var done: ChatEvent.Done? = null
            try {
                model.chatInternal(req) { completionId = it }.collect { ev ->
                    when (ev) {
                        is ChatEvent.ContentDelta -> {
                            summary.append(ev.delta)
                            emit(ev)
                        }

                        is ChatEvent.Done -> {
                            done = ev
                        }

                        else -> {
                            emit(ev)
                        }
                    }
                }
            } finally {
                completionId = null
            }
            val d = done!!
            // a summary the output limit cut is no summary (tools/ui COMPACTION.FINISH_CUT): roll back
            if (d.reason == FinishReason.Length) throw LlamaBroException.CompactionTruncated()
            if (summary.isNotBlank()) {
                append(Message.Compaction(summary.toString(), preservedSteps))
                startPreEncode()
            }
            emit(d)
        }
    }

    /**
     * tools/ui triggerPreEncode / triggerCompactionPreEncode: when every slot is idle, re-submit the model view
     * with `n_predict: 0` (chat.service.ts preEncode: messages, tools, no sampling) so the server encodes the
     * prompt ahead of the next turn. The timings update [contextUsage].
     */
    private fun startPreEncode() {
        val body =
            buildJsonObject {
                put("messages", ChatJson.messages(requestMessages()))
                put("n_predict", 0)
                put("stream", false)
                if (config.registry.tools.isNotEmpty()) put("tools", ChatJson.tools(config.registry.tools))
            }.toString()
        model.startPreEncode {
            try {
                if (!allSlotsIdle()) return@startPreEncode
                model.rawRequest(Endpoint.ChatCompletions, body).collect { response ->
                    val t =
                        runCatching {
                            (
                                Json.parseToJsonElement(
                                    response,
                                ) as JsonObject
                            )["timings"] as? JsonObject
                        }.getOrNull()
                    if (t != null) {
                        fun n(k: String) = (t[k] as? JsonPrimitive)?.intOrNull ?: 0
                        _contextUsage.value =
                            ContextUsage(n("prompt_n") + n("cache_n") + n("predicted_n"), model.info.contextSize)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // best effort, like tools/ui: a failed pre-encode only costs the next turn its prefill
            }
        }
    }

    /** tools/ui ChatService.areAllSlotsIdle: GET /slots, no slot `is_processing`. */
    private suspend fun allSlotsIdle(): Boolean {
        var idle = true
        model.rawRequest(Endpoint.Slots, "").collect { body ->
            val slots = runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return@collect
            idle = slots.none { ((it as? JsonObject)?.get("is_processing") as? JsonPrimitive)?.booleanOrNull == true }
        }
        return idle
    }

    /** Adds a result for a pending tool call (manual mode); then call [resume]. */
    internal fun addToolResultInternal(toolCallId: String, result: String) {
        checkIdle()
        val known = _messages.value.any { m -> m is Message.Assistant && m.toolCalls.any { it.id == toolCallId } }
        require(known) { "no tool call with id $toolCallId" }
        require(_messages.value.none { it is Message.ToolResult && it.toolCallId == toolCallId }) {
            "tool call $toolCallId already has a result"
        }
        append(Message.ToolResult(toolCallId, result))
    }

    // --- internals ------------------------------------------------------------------------------------

    private fun checkIdle() {
        if (busy.value) throw LlamaBroException.Busy()
    }

    private fun exclusive(block: suspend FlowCollector<ChatEvent>.() -> Unit): Flow<ChatEvent> =
        flow {
            if (!busy.compareAndSet(false, true)) throw LlamaBroException.Busy()
            try {
                model.cancelPreEncode()
                block()
            } finally {
                completionId = null
                _reply.value = null
                busy.value = false
            }
        }

    private fun append(m: Message) {
        _messages.value = _messages.value + m
    }

    /**
     * What is sent: the model view after the last compaction (tools/ui sliceAtLastCompaction), with empty system
     * messages dropped (chat.service.ts normalizeMessagesForApi).
     */
    private fun requestMessages(view: List<Message> = CompactionPlanner.modelView(_messages.value)): List<Message> =
        CompactionPlanner.forRequest(view).filterNot { it is Message.System && it.text.isBlank() }

    private fun request(messages: List<Message>, options: RequestOptions): ChatRequest =
        ChatRequest(messages, config.registry.tools, options)

    /**
     * The summary request (tools/ui sends it like a chat request): the chat's tools, sampling, reasoning and
     * `extra`, which shape the prompt or the sampler; not its format, stop words or tool choice, which would
     * constrain a free-text summary.
     */
    private fun compactionOptions(): RequestOptions =
        config.defaults.let { d ->
            RequestOptions {
                sampling = d.sampling
                reasoning = d.reasoning
                extra = d.extra
            }
        }

    /**
     * The agent loop, ported from tools/ui stores/agentic/index.svelte.ts (design §6.2): send; if the reply calls
     * tools, run them one after another in order, append each result, and send again; stop on a reply without
     * tool calls. The turn check sits at the top of the loop (`turn >= maxTurns`), so maxTurns counts model
     * requests per flow; tools/ui then asks the user to continue, which here is `Done(TurnLimit)` + [resume].
     */
    private suspend fun runTurns(out: FlowCollector<ChatEvent>, options: RequestOptions) {
        var turn = 0
        var last: ChatEvent.Done? = null
        while (true) {
            if (turn >= config.maxTurns) {
                out.emit(ChatEvent.Done(FinishReason.TurnLimit, last?.timings, last?.usage))
                return
            }
            val reply = streamReply(out, request(requestMessages(), options))
            last = reply.done
            if (reply.toolCalls.isEmpty()) {
                out.emit(reply.done)
                if (config.preEncodeAfterReply) startPreEncode()
                return
            }
            var pending = false
            for (call in reply.toolCalls) {
                out.emit(ChatEvent.ToolCallReceived(call))
                val handler = config.registry.handler(call.name)
                if (handler == null) {
                    pending = true // manual mode: the app adds the result
                    continue
                }
                val result = runTool(handler, call)
                append(Message.ToolResult(call.id, result))
                out.emit(ChatEvent.ToolResult(call.id, call.name, result))
            }
            if (pending) {
                out.emit(ChatEvent.Done(FinishReason.ToolCallsPending, reply.done.timings, reply.done.usage))
                return
            }
            turn++
        }
    }

    /**
     * One handler call. Errors, including arguments that aren't JSON, become `Error: <message>` as in tools/ui
     * (the arguments are parsed inside its try block). Cancelling the flow cancels the handler; closing the
     * model cancels it too and fails the flow with ModelClosed.
     */
    private suspend fun runTool(handler: suspend (JsonArgs) -> String, call: ToolCall): String =
        try {
            val args = JsonArgs(call.argumentsJson)
            coroutineScope {
                val watcher =
                    launch {
                        model.awaitClosed()
                        throw LlamaBroException.ModelClosed()
                    }
                try {
                    handler(args)
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlamaBroException.ModelClosed) {
            throw e
        } catch (e: Throwable) {
            "Error: ${e.message ?: e.toString()}"
        }

    private class Reply(val done: ChatEvent.Done, val toolCalls: List<ToolCall>)

    /** The assistant message as it arrives: text, reasoning and tool calls so far, and the closing event. */
    private class ReplyDraft {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var done: ChatEvent.Done? = null

        fun shown() = Message.Assistant(content.toString(), reasoning.toString().ifEmpty { null })

        fun hasAnything() = content.isNotBlank() || reasoning.isNotBlank() || calls.isNotEmpty()

        /** What a stop or an error keeps: the text and reasoning so far, no partial tool calls. */
        fun partial() =
            Message.Assistant(content = content.toString(), reasoning = reasoning.toString().ifBlank { null })

        // tools/ui stores/agentic/index.svelte.ts: `content: turnContent || undefined`,
        // `reasoning_content: … || undefined`
        fun finished() =
            if (calls.isEmpty()) {
                Message.Assistant(content = content.toString(), reasoning = reasoning.toString().ifEmpty { null })
            } else {
                Message.Assistant(
                    content = content.toString().ifEmpty { null },
                    reasoning = reasoning.toString().ifEmpty { null },
                    toolCalls = calls.toList(),
                )
            }
    }

    /**
     * One model request. Appends the reply to the history and returns its Done (not emitted). A reply that
     * stops early keeps what was streamed, as tools/ui stores/chat/index.svelte.ts savePartialResponseIfNeeded
     * does: content and reasoning stay, partial tool calls are dropped.
     */
    private suspend fun streamReply(out: FlowCollector<ChatEvent>, req: ChatRequest): Reply {
        val draft = ReplyDraft()
        try {
            model.chatInternal(req) { completionId = it }.collect { ev ->
                take(draft, ev)
                if (ev !is ChatEvent.Done && ev !is ChatEvent.ToolCallReceived) out.emit(ev)
            }
        } catch (e: CancellationException) {
            // A stop: tools/ui created the assistant message before streaming and never removes it, so the reply
            // stays, empty if nothing came (roles keep alternating); partial tool calls are dropped.
            append(draft.partial())
            _reply.value = null // after the append, so a UI never loses the text in between
            throw e
        } catch (e: Throwable) {
            // An error (e.g. ContextExceeded): keep what was streamed, nothing when it's empty, so resume()
            // sends the same request again (P1 plan T9).
            if (draft.hasAnything()) append(draft.partial())
            _reply.value = null
            throw e
        } finally {
            completionId = null
        }
        append(draft.finished())
        _reply.value = null
        return Reply(draft.done!!, draft.calls)
    }

    /** Folds one event into the draft and the live state the UI reads. */
    private fun take(draft: ReplyDraft, ev: ChatEvent) {
        when (ev) {
            is ChatEvent.ContentDelta -> {
                draft.content.append(ev.delta)
                _reply.value = draft.shown()
            }

            is ChatEvent.ReasoningDelta -> {
                draft.reasoning.append(ev.delta)
                _reply.value = draft.shown()
            }

            is ChatEvent.ToolCallReceived -> {
                draft.calls += ev.call
            }

            is ChatEvent.Done -> {
                draft.done = ev
                ev.timings?.let { t ->
                    _contextUsage.value =
                        ContextUsage(t.promptTokens + t.cachedTokens + t.predictedTokens, model.info.contextSize)
                }
            }

            else -> {
                Unit
            }
        }
    }
}

/**
 * Adds the result of a pending tool call (after `Done(ToolCallsPending)`); then call [Conversation.resume].
 * @throws LlamaBroException.Busy while a send, resume, regenerate or compaction runs.
 */
public fun Conversation.addToolResult(toolCallId: String, result: String): Unit =
    addToolResultInternal(toolCallId, result)
