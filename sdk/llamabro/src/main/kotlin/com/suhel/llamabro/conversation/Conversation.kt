package com.suhel.llamabro.conversation

import androidx.annotation.CheckResult
import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.Typed
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.RequestOptions
import com.suhel.llamabro.chat.Tool
import com.suhel.llamabro.chat.ToolCall
import com.suhel.llamabro.chatInternal
import com.suhel.llamabro.internal.ErrorMapping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A chat history that behaves like llama.cpp's own web client (`tools/ui`,
 * [D8](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d8)): it builds each request from the
 * history, appends replies, and only ever appends, so the server reuses its cached prefix.
 *
 * Every request carries the conversation's [Builder.defaults]; one [send], [resume] or [regenerate] can change
 * them for its own requests only. One operation at a time: collecting [send], [resume], [regenerate], [approve],
 * [deny] or [compact] while another runs ([isBusy]) fails with [LlamaBroException.Busy]; creating the flow never
 * throws.
 *
 * This class is the facade: it holds the history and the state flows, runs one operation at a time, and compacts.
 * The rest belongs to internal collaborators: [AgentLoop] runs the turns and the tools
 * ([D14](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d14),
 * [D62](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d62)), [ReplyStreamer] folds one model
 * request into the history, [PreEncoder] warms the server's cache in the background, and [MediaPrep] checks and reads a
 * message's media ([D84](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d84)).
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

    @Volatile
    private var completionId: String? = null

    private val streamer = ReplyStreamer(model, _reply, _contextUsage, { completionId = it }, ::append)

    private val preEncoder = PreEncoder(model, config.registry, _contextUsage)

    private val loop =
        AgentLoop(
            registry = config.registry,
            maxTurns = { config.maxTurns },
            history = { _messages.value },
            append = ::append,
            chat = { out, options -> streamer.stream(out, request(requestMessages(), options)) },
            awaitClosed = model::awaitClosed,
            afterAnswer = { if (config.preEncodeAfterReply) preEncoder.start(requestMessages()) },
        )

    // computed from the history and the registry, so a restored conversation (whose builder is filled before it is
    // constructed) starts with the right value
    private val _pendingApproval = MutableStateFlow(loop.awaitingApproval)

    /**
     * The tool call waiting for [approve] or [deny]
     * ([D62](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d62)), null when none waits or
     * while an operation runs. It is derived from the history and the registered tools, so it is right after a restore.
     *
     * It is null while [isBusy]. An app reacting to it should start [approve] or [deny] once [isBusy] is false, for
     * example by observing both together: the operation that ended publishes the waiting call just before it clears
     * busy.
     */
    public val pendingApproval: StateFlow<ToolCall?> = _pendingApproval.asStateFlow()

    /**
     * Appends a user message and streams the reply.
     *
     * [attachments] are pictures and clips the model must be able to read ([com.suhel.llamabro.ModelInfo]'s
     * `supportsVision` and `supportsAudio`); otherwise, or for a missing file or audio that isn't WAV, MP3 or FLAC (by
     * MIME type, and by its first bytes for a file, a descriptor or bytes), the flow fails with
     * [LlamaBroException.InvalidRequest] and nothing is appended. A descriptor is read into bytes, and those checked,
     * before the message is appended, off the caller's thread; the app may close it once the message is in [messages]
     * ([D84](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d84)).
     *
     * Media that passes those checks can still fail the first request: a picture llama.cpp can't decode (HEIC, WebP),
     * or a file that went away. Then, before any token or tool call arrives, the flow fails with
     * [LlamaBroException.InvalidRequest] and the message is removed again, so the chat stays usable; it is removed only
     * while it is still the last message. Any other failure (a text-only send that hits `ContextExceeded`, say) keeps
     * the message, so [resume] can send it again.
     * @param options changes the [Builder.defaults] for this send's requests only (every turn of its tool loop).
     */
    @CheckResult
    public fun send(
        text: String,
        vararg attachments: Content,
        options: RequestOptions.Builder.() -> Unit = {},
    ): Flow<ChatEvent> {
        val parts = listOf<Content>(Content.Text(text)) + attachments
        val opts = config.defaults.copy(options)
        return exclusive {
            val user = Message.User(MediaPrep.forMessage(model, parts))
            append(user)
            try {
                turns(opts)
            } catch (e: LlamaBroException.InvalidRequest) {
                // media no Kotlin check knows (a HEIC picture, say) failed the first request before any token: the
                // message would fail every later one, so it leaves the chat, unless something came after it
                if (attachments.isNotEmpty() && ErrorMapping.isMediaError(e)) {
                    _messages.update { if (it.lastOrNull() === user) it.dropLast(1) else it }
                }
                throw e
            }
        }
    }

    /**
     * [send], collected: the final reply as a [Typed.Value] (its content and reasoning), or [Typed.Incomplete] when
     * the reply isn't a complete answer: `Length` (cut at the output limit), `TurnLimit`, `ToolCallsPending`
     * (continue with [resume], after [addToolResult] for pending tools) or `ToolAborted` (a tool threw
     * [LbToolAbort]). Engine failures still throw.
     */
    public suspend fun ask(
        text: String,
        vararg attachments: Content,
        options: RequestOptions.Builder.() -> Unit = {},
    ): Typed<String> = collectReply(send(text, *attachments, options = options))

    /**
     * Sends the current history again: after `ContextExceeded` (and a compaction), a turn limit, or pending tool
     * results. First it finishes the last reply's tool calls that have no result yet, in order, as the loop would
     * have ([D62](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d62)): after
     * `Done(ToolAborted)` it runs the aborted call again. It stops with `Done(ToolCallsPending)`, without asking the
     * model, at a call that needs approval or when a call without a handler still has no result.
     */
    @CheckResult
    public fun resume(options: RequestOptions.Builder.() -> Unit = {}): Flow<ChatEvent> {
        val opts = config.defaults.copy(options)
        return exclusive { turns(opts) }
    }

    /**
     * Runs the call waiting for approval ([pendingApproval]), then continues the turn as [resume] does: the reply's
     * later calls, then the model ([D62](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d62)).
     * @param options changes the [Builder.defaults] for this flow's requests only, as [resume]'s does.
     * @throws IllegalArgumentException (when collected) if [callId] isn't [pendingApproval]'s id.
     */
    @CheckResult
    public fun approve(callId: String, options: RequestOptions.Builder.() -> Unit = {}): Flow<ChatEvent> {
        val opts = config.defaults.copy(options)
        // null when the approved call aborted: the loop has ended the flow with Done(ToolAborted)
        return exclusive { loop.approve(this, callId)?.let { turns(opts, from = it) } }
    }

    /**
     * Answers the call waiting for approval ([pendingApproval]) without running it: the model gets
     * `Error: denied by the user`, or `Error: <reason>`. Then continues the turn as [resume] does
     * ([D62](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d62)).
     * @param options changes the [Builder.defaults] for this flow's requests only, as [resume]'s does.
     * @throws IllegalArgumentException (when collected) if [callId] isn't [pendingApproval]'s id.
     */
    @CheckResult
    public fun deny(
        callId: String,
        reason: String? = null,
        options: RequestOptions.Builder.() -> Unit = {},
    ): Flow<ChatEvent> {
        val opts = config.defaults.copy(options)
        return exclusive { turns(opts, from = loop.deny(this, callId, reason)) }
    }

    /**
     * Drops the replies after the last user message (with their tool calls and results) and asks again, as
     * tools/ui's regenerate does. The request repeats the original's messages, so the server's cache covers them.
     * @throws IllegalStateException (when collected) if there is no user message, or a compaction follows it.
     */
    @CheckResult
    public fun regenerate(options: RequestOptions.Builder.() -> Unit = {}): Flow<ChatEvent> {
        val opts = config.defaults.copy(options)
        return exclusive {
            val history = _messages.value
            val lastUser = history.indexOfLast { it is Message.User }
            check(lastUser >= 0) { "nothing to regenerate: no user message" }
            check(history.subList(lastUser + 1, history.size).none { it is Message.Compaction }) {
                "can't regenerate: a compaction follows the last user message"
            }
            // atomic, as toJson may replace a history descriptor meanwhile (which never changes the list's size)
            _messages.update { it.subList(0, lastUser + 1) }
            turns(opts)
        }
    }

    /**
     * [regenerate], collected, as [ask] is [send] collected: the new reply as [Typed.Value], or [Typed.Incomplete] for
     * the reasons [ask] gives. Engine failures still throw.
     * @throws IllegalStateException if there is no user message, or a compaction follows it (as [regenerate]).
     */
    public suspend fun askAgain(options: RequestOptions.Builder.() -> Unit = {}): Typed<String> =
        collectReply(regenerate(options))

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
     *
     * Media ([D84](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d84)): bytes are saved as
     * base64, and a file as its path, so the file must still be there when the chat is restored; if it is gone, every
     * request fails with [LlamaBroException.InvalidRequest] naming the path, and the app recovers by starting a
     * conversation from [messages] with that part removed or replaced (`model.conversation(system, history = …)`). A
     * descriptor in a `history` the conversation started from that no request has read yet is read here, on the calling
     * thread, and kept as bytes, so it is read only once.
     *
     * @throws LlamaBroException.InvalidRequest if such a descriptor is closed or can't be read.
     */
    public fun toJson(): String {
        val snapshot = MediaPrep.readHistory(_messages, model::readNow)
        return ConversationJson.encode(
            system,
            snapshot.drop(
                if (system != null) {
                    1
                } else {
                    0
                },
            ),
            config,
            model::readNow,
        )
    }

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

        /**
         * When a send, resume, regenerate, approve or deny finds the history too long for the context
         * (`ContextExceeded`), [compact] it with the defaults and send again, once, in the same flow (default off, as
         * tools/ui leaves it to the person). The summary isn't streamed as the reply: it appears in [messages] as a
         * [Message.Compaction]. When there is nothing to compact ([canCompact] is false) or the history is still too
         * long, the flow fails with `ContextExceeded` as it would without this.
         */
        public var autoCompact: Boolean = false

        internal val registry = ToolRegistry()

        /**
         * Registers a tool. With a [handler], the agent loop runs it and sends its result back. What a
         * handler throws decides what the model sees
         * ([D62](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d62)): an [LbToolError] sends
         * `Error: <message>` (as do [JsonArgs]' accessors, with `invalid arguments: …`), and its `cause`, if any,
         * reaches the app as `ChatEvent.ToolResult.error`; an [LbToolAbort] ends the turn with `Done(ToolAborted)`; any
         * other exception sends `Error: the tool failed` and reaches the app as `ChatEvent.ToolResult.error`, so its
         * text never enters the prompt. Without a handler, a call to it stops the loop with `Done(ToolCallsPending)`:
         * add results with [addToolResult], then [resume]. A call to a name never registered gets `Error: Unknown tool:
         * <name>` and the loop goes on.
         *
         * @param schema the parameters' JSON Schema object, as a string.
         * @param requiresApproval a call to it stops the loop with `Done(ToolCallsPending)` before the handler runs
         *   and shows as [pendingApproval]; [approve] runs it, [deny] answers the model instead
         *   ([D62](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d62)).
         * @throws IllegalArgumentException if [requiresApproval] is set without a [handler].
         */
        public fun tool(
            name: String,
            description: String,
            schema: String,
            requiresApproval: Boolean = false,
            handler: (suspend (JsonArgs) -> String)? = null,
        ) {
            val forCall: (suspend (ToolCall) -> String)? =
                handler?.let { h -> { call -> h(JsonArgs(call.argumentsJson)) } }
            registry.add(Tool.function(name, description, schema), forCall, requiresApproval)
        }

        /**
         * Registers a tool whose handler takes the whole call: llamabro-typed's typed tools decode
         * [ToolCall.argumentsJson] themselves
         * ([D61](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d61)). The loop treats it as
         * [tool] does a handler.
         */
        @InternalLlamaBroApi
        public fun toolForCall(tool: Tool, requiresApproval: Boolean, handler: suspend (ToolCall) -> String) {
            registry.add(tool, handler, requiresApproval)
        }
    }

    /**
     * Summarises the history before the last [preservedSteps] steps, the way tools/ui does
     * (stores/chat/index.svelte.ts compaction). The summary request is the chat's own request cut before the preserved
     * steps plus [instruction], so the server reuses its cached prefix. On success a [Message.Compaction] is appended
     * and the shortened history is pre-encoded in the background (cancelled by the next send).
     *
     * @throws com.suhel.llamabro.LlamaBroException.CompactionTruncated if the summary hit the output limit; the
     *   history is unchanged.
     * @throws IllegalStateException if no step lies before the preserved ones (nothing to compact), or a tool call of
     *   the last reply still has no result (waiting for approval, or a manual tool): approve, deny or answer it first.
     */
    @CheckResult
    public fun compact(
        preservedSteps: Int = Compaction.DEFAULT_PRESERVED_STEPS,
        instruction: String = Compaction.DEFAULT_PROMPT,
    ): Flow<ChatEvent> {
        require(preservedSteps >= 0) { "preservedSteps must be >= 0" }
        return exclusive { compactNow(preservedSteps, instruction, streamSummary = true) }
    }

    /**
     * Adds the result of a pending tool call (after `Done(ToolCallsPending)`); then call [resume].
     * @throws LlamaBroException.Busy while a send, resume, regenerate, approve, deny or compaction runs.
     */
    public fun addToolResult(toolCallId: String, result: String) {
        if (!busy.compareAndSet(false, true)) throw LlamaBroException.Busy()
        try {
            val known = _messages.value.any { m -> m is Message.Assistant && m.toolCalls.any { it.id == toolCallId } }
            require(known) { "no tool call with id $toolCallId" }
            require(_messages.value.none { it is Message.ToolResult && it.toolCallId == toolCallId }) {
                "tool call $toolCallId already has a result"
            }
            append(Message.ToolResult(toolCallId, result))
            // the app may answer the waiting call itself, or a manual call before it
            _pendingApproval.value = loop.awaitingApproval
        } finally {
            busy.value = false
        }
    }

    // --- internals ------------------------------------------------------------------------------------

    /** The compaction itself, inside an operation; [streamSummary] false keeps the summary out of the flow. */
    private suspend fun FlowCollector<ChatEvent>.compactNow(
        preservedSteps: Int,
        instruction: String,
        streamSummary: Boolean,
    ) {
        val unanswered = unansweredToolCalls(_messages.value)
        check(unanswered.isEmpty()) {
            "can't compact: tool calls ${unanswered.map { it.id }} have no result; approve, deny or answer them first"
        }
        val source = CompactionPlanner.modelView(_messages.value)
        val cut =
            CompactionPlanner.cutIndex(source, preservedSteps)
                ?: error("nothing to compact: no step before the $preservedSteps preserved ones")
        // the request the chat sends, cut before the preserved steps, plus the instruction as a user message
        val req = request(requestMessages(source.subList(0, cut)) + Message.user(instruction), compactionOptions())
        val summary = StringBuilder()
        var done: ChatEvent.Done? = null
        try {
            model.chatInternal(req) { completionId = it }.collect { ev ->
                when (ev) {
                    is ChatEvent.ContentDelta -> {
                        summary.append(ev.delta)
                        if (streamSummary) emit(ev)
                    }

                    is ChatEvent.Done -> {
                        done = ev
                    }

                    else -> {
                        if (streamSummary) emit(ev)
                    }
                }
            }
        } finally {
            completionId = null
        }
        val d = checkNotNull(done)
        // a summary the output limit cut is no summary (tools/ui COMPACTION.FINISH_CUT): roll back
        if (d.reason == FinishReason.Length) throw LlamaBroException.CompactionTruncated()
        if (summary.isNotBlank()) {
            append(Message.Compaction(summary.toString(), preservedSteps))
            if (streamSummary) preEncoder.start(requestMessages())
        }
        if (streamSummary) emit(d)
    }

    /** The agent loop, with one compaction and retry on `ContextExceeded` when [Builder.autoCompact] is on. */
    private suspend fun FlowCollector<ChatEvent>.turns(options: RequestOptions, from: Int = 0) {
        try {
            loop.run(this, options, from)
        } catch (e: LlamaBroException.ContextExceeded) {
            if (!config.autoCompact || !canCompact()) throw e
            compactNow(Compaction.DEFAULT_PRESERVED_STEPS, Compaction.DEFAULT_PROMPT, streamSummary = false)
            loop.run(this, options)
        }
    }

    private fun exclusive(block: suspend FlowCollector<ChatEvent>.() -> Unit): Flow<ChatEvent> =
        flow {
            if (!busy.compareAndSet(false, true)) throw LlamaBroException.Busy()
            _pendingApproval.value = null // nothing can be approved while an operation runs
            try {
                model.preEncodeSlot.cancel()
                MediaPrep.readHistory(_messages) { model.read(it) }
                block()
            } finally {
                completionId = null
                _reply.value = null
                _pendingApproval.value = loop.awaitingApproval
                busy.value = false
            }
        }

    /** Atomic: [toJson], which isn't behind [busy], may replace a history descriptor at the same time. */
    private fun append(m: Message) {
        _messages.update { it + m }
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

    /** [flow] collected: this turn's last reply, after its user message, so an older reply never counts. */
    private suspend fun collectReply(flow: Flow<ChatEvent>): Typed<String> {
        var done: ChatEvent.Done? = null
        flow.collect { if (it is ChatEvent.Done) done = it }
        val history = messages.value
        val reply =
            history
                .subList(history.indexOfLast { it is Message.User } + 1, history.size)
                .lastOrNull { it is Message.Assistant } as Message.Assistant?
        val content = reply?.content.orEmpty()
        val reason = done?.reason ?: FinishReason.Stop
        return if (reason in INCOMPLETE) {
            Typed.Incomplete(reason, content)
        } else {
            Typed.Value(content, content, reply?.reasoning)
        }
    }
}

/** The reasons `ask` reports as [Typed.Incomplete]: the turn ended without a final answer. */
private val INCOMPLETE =
    setOf(
        FinishReason.Length,
        FinishReason.TurnLimit,
        FinishReason.ToolCallsPending,
        FinishReason.ToolAborted,
    )
