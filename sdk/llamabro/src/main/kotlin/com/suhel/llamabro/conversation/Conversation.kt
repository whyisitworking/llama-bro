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
 * them for its own requests only. One operation at a time: collecting [send], [resume], [regenerate], [approve],
 * [deny] (or `compact`) while another runs ([isBusy]) fails with [LlamaBroException.Busy]; creating the flow never
 * throws.
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

    /** True while a send, resume, regenerate, approve, deny or compaction runs. */
    public val isBusy: StateFlow<Boolean> = busy.asStateFlow()

    // computed from the history and the registry, so a restored conversation (whose builder is filled before it is
    // constructed) starts with the right value
    private val _pendingApproval = MutableStateFlow(awaitingApproval)

    /**
     * The tool call waiting for [approve] or [deny] (D62), null when none waits or while an operation runs. It is
     * derived from the history and the registered tools, so it is right after a restore.
     *
     * It is null while [isBusy]. An app reacting to it should start [approve] or [deny] once [isBusy] is false, for
     * example by observing both together: the operation that ended publishes the waiting call just before it clears
     * busy.
     */
    public val pendingApproval: StateFlow<ToolCall?> = _pendingApproval.asStateFlow()

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
     *   `TurnLimit`, `ToolCallsPending` (continue with [resume], after [addToolResult] for pending tools) or
     *   `ToolAborted` (a tool threw [LbToolAbort]).
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
        if (reason in INCOMPLETE) {
            throw LlamaBroException.Incomplete(reason, content)
        }
        return content
    }

    /**
     * Sends the current history again: after `ContextExceeded` (and a compaction), a turn limit, or pending tool
     * results. First it finishes the last reply's tool calls that have no result yet, in order, as the loop would
     * have (D62): after `Done(ToolAborted)` it runs the aborted call again. It stops with `Done(ToolCallsPending)`,
     * without asking the model, at a call that needs approval or when a call without a handler still has no result.
     */
    public fun resume(options: RequestOptions.Builder.() -> Unit = {}): Flow<ChatEvent> {
        val opts = config.defaults.copy(options)
        return exclusive { runTurns(this, opts) }
    }

    /**
     * Runs the call waiting for approval ([pendingApproval]), then continues the turn as [resume] does: the reply's
     * later calls, then the model (D62).
     * @param options changes the [Builder.defaults] for this flow's requests only, as [resume]'s does.
     * @throws IllegalArgumentException (when collected) if [callId] isn't [pendingApproval]'s id.
     */
    public fun approve(callId: String, options: RequestOptions.Builder.() -> Unit = {}): Flow<ChatEvent> {
        val opts = config.defaults.copy(options)
        return exclusive {
            val (index, call) = approvalFor(callId)
            val handler = config.registry.entry(call.name)!!.handler!!
            answer(this, call, runTool(handler, call))?.let { abort ->
                emitStop(FinishReason.ToolAborted, null, abort)
                return@exclusive
            }
            runTurns(this, opts, from = index + 1)
        }
    }

    /**
     * Answers the call waiting for approval ([pendingApproval]) without running it: the model gets
     * `Error: denied by the user`, or `Error: <reason>`. Then continues the turn as [resume] does (D62).
     * @param options changes the [Builder.defaults] for this flow's requests only, as [resume]'s does.
     * @throws IllegalArgumentException (when collected) if [callId] isn't [pendingApproval]'s id.
     */
    public fun deny(
        callId: String,
        reason: String? = null,
        options: RequestOptions.Builder.() -> Unit = {},
    ): Flow<ChatEvent> {
        val opts = config.defaults.copy(options)
        return exclusive {
            val (index, call) = approvalFor(callId)
            answer(this, call, ToolOutcome.Result("Error: ${reason ?: "denied by the user"}"))
            runTurns(this, opts, from = index + 1)
        }
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
         * Registers a tool (design §6.2). With a [handler], the agent loop runs it and sends its result back. What a
         * handler throws decides what the model sees (D62): an [LbToolError] sends `Error: <message>` (as do
         * [JsonArgs]' accessors, with `invalid arguments: …`); an [LbToolAbort] ends the turn with
         * `Done(ToolAborted)`; any other exception sends `Error: the tool failed` and reaches the app as
         * `ChatEvent.ToolResult.error`, so its text never enters the prompt. Without a handler, a call to it stops
         * the loop with `Done(ToolCallsPending)`: add results with [addToolResult], then [resume]. A call to a name
         * never registered gets `Error: Unknown tool: <name>` and the loop goes on.
         *
         * @param schema the parameters' JSON Schema object, as a string.
         * @param requiresApproval a call to it stops the loop with `Done(ToolCallsPending)` before the handler runs
         *   and shows as [pendingApproval]; [approve] runs it, [deny] answers the model instead (D62).
         * @throws IllegalArgumentException if [requiresApproval] is set without a [handler].
         */
        public fun tool(
            name: String,
            description: String,
            schema: String,
            requiresApproval: Boolean = false,
            handler: (suspend (JsonArgs) -> String)? = null,
        ) {
            registry.add(Tool.function(name, description, schema), handler, requiresApproval)
        }
    }

    /** tools/ui stores/chat/index.svelte.ts compaction (see [compact]). */
    internal fun compactInternal(preservedSteps: Int, instruction: String): Flow<ChatEvent> {
        require(preservedSteps >= 0) { "preservedSteps must be >= 0" }
        return exclusive {
            val unanswered = unansweredToolCalls(_messages.value)
            check(unanswered.isEmpty()) {
                "can't compact: tool calls ${unanswered.map {
                    it.id
                }} have no result; approve, deny or answer them first"
            }
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
        if (!busy.compareAndSet(false, true)) throw LlamaBroException.Busy()
        try {
            val known = _messages.value.any { m -> m is Message.Assistant && m.toolCalls.any { it.id == toolCallId } }
            require(known) { "no tool call with id $toolCallId" }
            require(_messages.value.none { it is Message.ToolResult && it.toolCallId == toolCallId }) {
                "tool call $toolCallId already has a result"
            }
            append(Message.ToolResult(toolCallId, result))
            // the app may answer the waiting call itself, or a manual call before it
            _pendingApproval.value = awaitingApproval
        } finally {
            busy.value = false
        }
    }

    // --- internals ------------------------------------------------------------------------------------

    private fun exclusive(block: suspend FlowCollector<ChatEvent>.() -> Unit): Flow<ChatEvent> =
        flow {
            if (!busy.compareAndSet(false, true)) throw LlamaBroException.Busy()
            _pendingApproval.value = null // nothing can be approved while an operation runs
            try {
                model.cancelPreEncode()
                block()
            } finally {
                completionId = null
                _reply.value = null
                _pendingApproval.value = awaitingApproval
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
     *
     * It first finishes the last batch, from call [from] (D62): a no-op for [send] and [regenerate], whose history
     * ends at a user message; for [resume], [approve] and [deny] it runs the calls a stop left without a result.
     */
    private suspend fun runTurns(out: FlowCollector<ChatEvent>, options: RequestOptions, from: Int = 0) {
        var turn = 0
        var last: ChatEvent.Done? = null
        var start = from
        while (true) {
            when (val batch = runBatch(out, start)) {
                BatchOutcome.Answered -> Unit
                BatchOutcome.Pending -> return out.emitStop(FinishReason.ToolCallsPending, last)
                is BatchOutcome.Aborted -> return out.emitStop(FinishReason.ToolAborted, last, batch.abort)
            }
            start = 0
            if (turn >= config.maxTurns) return out.emitStop(FinishReason.TurnLimit, last)
            val reply = streamReply(out, request(requestMessages(), options))
            last = reply.done
            turn++
            if (reply.toolCalls.isEmpty()) {
                out.emit(reply.done)
                if (config.preEncodeAfterReply) startPreEncode()
                return
            }
        }
    }

    /** How a batch run ended: every call has a result, some still wait for the app, or a tool ended the turn. */
    private sealed interface BatchOutcome {
        object Answered : BatchOutcome

        object Pending : BatchOutcome

        class Aborted(val abort: LbToolAbort) : BatchOutcome
    }

    /**
     * Runs the last batch's unanswered calls from index [from] in order, announcing each as it is reached (D62):
     * an unknown name gets its error result, an automatic call runs, a manual call is left for the app, and a call
     * that needs approval stops the batch before it runs. Announcing on reach means a call reached again after an
     * abort is announced again, and calls after an approval stop are announced only when the batch gets to them.
     */
    private suspend fun runBatch(out: FlowCollector<ChatEvent>, from: Int): BatchOutcome {
        val batch = ToolBatch.of(_messages.value) ?: return BatchOutcome.Answered
        val left = batch.unanswered.mapTo(mutableSetOf()) { it.id }
        for (call in batch.calls.drop(from).filter { it.id in left }) {
            out.emit(ChatEvent.ToolCallReceived(call))
            val entry = config.registry.entry(call.name)
            val handler = entry?.handler
            val outcome =
                when {
                    // tools/ui answers an unknown name and goes on (F140, D62)
                    entry == null -> ToolOutcome.Result("Error: Unknown tool: ${call.name}")

                    // manual mode: the app adds the result
                    handler == null -> continue

                    entry.requiresApproval -> return BatchOutcome.Pending

                    else -> runTool(handler, call)
                }
            // an abort wins over manual calls skipped before it: the turn ends with ToolAborted, not pending
            answer(out, call, outcome)?.let { return BatchOutcome.Aborted(it) }
        }
        val complete = ToolBatch.of(_messages.value)?.unanswered.isNullOrEmpty()
        return if (complete) BatchOutcome.Answered else BatchOutcome.Pending
    }

    /**
     * Records a call's outcome: a result is appended and emitted; an abort is returned, and neither this call nor
     * the later ones get a result, so the turn ends here for the app and [resume] runs this call again (D62).
     */
    private suspend fun answer(out: FlowCollector<ChatEvent>, call: ToolCall, outcome: ToolOutcome): LbToolAbort? =
        when (outcome) {
            is ToolOutcome.Aborted -> {
                outcome.abort
            }

            is ToolOutcome.Result -> {
                append(Message.ToolResult(call.id, outcome.text))
                out.emit(ChatEvent.ToolResult(call.id, call.name, outcome.text, outcome.error))
                null
            }
        }

    /** The call waiting for approval, from the history and the registry (see [ToolBatch.awaitingApproval]). */
    private val awaitingApproval: ToolCall?
        get() = ToolBatch.of(_messages.value)?.awaitingApproval(config.registry)?.value

    /** [approve] and [deny] act only on the call waiting; returns it with its index in the batch. */
    private fun approvalFor(callId: String): IndexedValue<ToolCall> {
        val waiting = ToolBatch.of(_messages.value)?.awaitingApproval(config.registry)
        require(waiting != null && waiting.value.id == callId) { "tool call $callId isn't waiting for approval" }
        return waiting
    }

    /** What one automatic call produced: a result for the model, or an abort that ends the turn. */
    private sealed interface ToolOutcome {
        /** [error] is the exception behind a generic `Error: the tool failed`, for the app only. */
        class Result(val text: String, val error: Throwable? = null) : ToolOutcome

        class Aborted(val abort: LbToolAbort) : ToolOutcome
    }

    /**
     * One handler call. Unlike tools/ui, which sends every exception's message to the model, only an [LbToolError]'s
     * message does; any other exception sends a generic text, so a token or personal data in it stays out of the
     * prompt and the saved chat (D62). The arguments are parsed inside the try block, so ones that don't parse
     * become `Error: invalid arguments: …`. Cancelling the flow cancels the handler; closing the model cancels it
     * too and fails the flow with ModelClosed. Exceptions are sorted inside the scope, so the app gets the
     * handler's own instance, not a copy made by coroutines' stack-trace recovery when it leaves the scope.
     */
    private suspend fun runTool(handler: suspend (JsonArgs) -> String, call: ToolCall): ToolOutcome =
        coroutineScope {
            val watcher =
                launch {
                    model.awaitClosed()
                    throw LlamaBroException.ModelClosed()
                }
            try {
                ToolOutcome.Result(handler(JsonArgs(call.argumentsJson)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlamaBroException.ModelClosed) {
                throw e
            } catch (e: LbToolAbort) {
                ToolOutcome.Aborted(e)
            } catch (e: LbToolError) {
                ToolOutcome.Result("Error: ${e.message.orEmpty()}")
            } catch (e: Throwable) {
                ToolOutcome.Result("Error: the tool failed", e)
            } finally {
                watcher.cancel()
            }
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

    private companion object {
        /** The reasons [ask] reports as [LlamaBroException.Incomplete]: the turn ended without a final answer. */
        val INCOMPLETE =
            setOf(
                FinishReason.Length,
                FinishReason.TurnLimit,
                FinishReason.ToolCallsPending,
                FinishReason.ToolAborted,
            )
    }
}

/**
 * Adds the result of a pending tool call (after `Done(ToolCallsPending)`); then call [Conversation.resume].
 * @throws LlamaBroException.Busy while a send, resume, regenerate, approve, deny or compaction runs.
 */
public fun Conversation.addToolResult(toolCallId: String, result: String): Unit =
    addToolResultInternal(toolCallId, result)

/**
 * Ends a flow early. [last] is the flow's last reply; a stop before any request (resume straight into a waiting
 * approval) has no timings or usage to report.
 */
private suspend fun FlowCollector<ChatEvent>.emitStop(
    reason: FinishReason,
    last: ChatEvent.Done?,
    abort: LbToolAbort? = null,
) {
    emit(ChatEvent.Done(reason, last?.timings, last?.usage, abort))
}
