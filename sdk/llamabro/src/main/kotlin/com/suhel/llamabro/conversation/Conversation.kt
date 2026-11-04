package com.suhel.llamabro.conversation

import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.Reasoning
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.chat.Tool
import com.suhel.llamabro.chat.ToolCall
import com.suhel.llamabro.chatInternal
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
import com.suhel.llamabro.internal.ChatJson
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A chat history that behaves like llama.cpp's own web client (`tools/ui`, design §6–§7, D8): it builds each
 * request from the history, appends replies, and only ever appends, so the server reuses its cached prefix.
 *
 * One operation at a time: [send], [resume] (and `compact`) throw [IllegalStateException] while another runs.
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
    public val contextUsage: StateFlow<ContextUsage> = _contextUsage.asStateFlow()

    private val busy = AtomicBoolean(false)

    @Volatile
    private var completionId: String? = null


    /** Appends a user message and streams the reply. */
    public fun send(text: String, vararg attachments: Content): Flow<ChatEvent> {
        checkIdle()
        val parts = listOf<Content>(Content.Text(text)) + attachments
        return exclusive {
            append(Message.User(parts))
            runTurns(this)
        }
    }

    /**
     * [send], collected: returns the final reply's content.
     * @throws IllegalStateException if the reply ended without one: `TurnLimit` or `ToolCallsPending`
     *   (continue with [resume], after [addToolResult] for pending tools).
     */
    public suspend fun ask(text: String): String {
        var done: ChatEvent.Done? = null
        send(text).collect { if (it is ChatEvent.Done) done = it }
        val reason = done?.reason
        check(reason != FinishReason.TurnLimit && reason != FinishReason.ToolCallsPending) {
            "the reply ended with $reason, not an answer; call resume() to continue"
        }
        return (_messages.value.lastOrNull() as? Message.Assistant)?.content.orEmpty()
    }

    /** Sends the current history again: after `ContextExceeded` (and a compaction), a turn limit, or pending tool results. */
    public fun resume(): Flow<ChatEvent> {
        checkIdle()
        return exclusive { runTurns(this) }
    }

    /** Asks the server to stop thinking now (llama-server's `reasoning_end` control) for the reply streaming now. */
    public suspend fun endReasoning() {
        val id = completionId ?: return
        val body = buildJsonObject { put("id", id); put("action", "reasoning_end") }.toString()
        model.rawRequest(Endpoint.Control, body).collect()
    }

    /** The history as JSON; restore with `model.restoreConversation(json)`. Builder settings and tool handlers aren't saved. */
    public fun toJson(): String = ConversationJson.encode(system, _messages.value.drop(if (system != null) 1 else 0))

    public class Builder internal constructor() {
        /** Sent with every request; null fields use the model's defaults. */
        public var sampling: Sampling = Sampling()
        public var reasoning: Reasoning = Reasoning()
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
        public fun tool(name: String, description: String, schema: String, handler: (suspend (JsonArgs) -> String)? = null) {
            registry.add(Tool.function(name, description, schema), handler)
        }
    }

    /** tools/ui stores/chat/index.svelte.ts compaction (see [compact]). */
    internal fun compactInternal(preservedSteps: Int, instruction: String): Flow<ChatEvent> {
        require(preservedSteps >= 0) { "preservedSteps must be >= 0" }
        checkIdle()
        return exclusive {
            val source = CompactionPlanner.modelView(_messages.value)
            val cut = CompactionPlanner.cutIndex(source, preservedSteps)
                ?: throw IllegalStateException("nothing to compact: no step before the $preservedSteps preserved ones")
            // the request the chat sends, cut before the preserved steps, plus the instruction as a user message
            val req = request(requestMessages(source.subList(0, cut)) + Message.user(instruction))
            val summary = StringBuilder()
            var done: ChatEvent.Done? = null
            try {
                model.chatInternal(req) { completionId = it }.collect { ev ->
                    when (ev) {
                        is ChatEvent.Content -> { summary.append(ev.delta); emit(ev) }
                        is ChatEvent.Done -> done = ev
                        else -> emit(ev)
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
        val body = buildJsonObject {
            put("messages", ChatJson.messages(requestMessages()))
            put("n_predict", 0)
            put("stream", false)
            if (config.registry.tools.isNotEmpty()) put("tools", ChatJson.tools(config.registry.tools))
        }.toString()
        model.startPreEncode {
            try {
                if (!allSlotsIdle()) return@startPreEncode
                model.rawRequest(Endpoint.ChatCompletions, body).collect { response ->
                    val t = runCatching { (Json.parseToJsonElement(response) as JsonObject)["timings"] as? JsonObject }.getOrNull()
                    if (t != null) {
                        fun n(k: String) = (t[k] as? JsonPrimitive)?.intOrNull ?: 0
                        _contextUsage.value = ContextUsage(n("prompt_n") + n("cache_n") + n("predicted_n"), model.info.contextSize)
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
        check(!busy.get()) { "another send, resume or compact is running on this conversation" }
    }

    private fun exclusive(block: suspend FlowCollector<ChatEvent>.() -> Unit): Flow<ChatEvent> = flow {
        if (!busy.compareAndSet(false, true)) throw IllegalStateException("another send, resume or compact is running on this conversation")
        try {
            model.cancelPreEncode()
            block()
        } finally {
            completionId = null
            busy.set(false)
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

    private fun request(messages: List<Message> = requestMessages()): ChatRequest = ChatRequest(
        messages = messages,
        tools = config.registry.tools,
        sampling = config.sampling,
        reasoning = config.reasoning,
    )

    /**
     * The agent loop, ported from tools/ui stores/agentic/index.svelte.ts (design §6.2): send; if the reply calls
     * tools, run them one after another in order, append each result, and send again; stop on a reply without
     * tool calls. The turn check sits at the top of the loop (`turn >= maxTurns`), so maxTurns counts model
     * requests per flow; tools/ui then asks the user to continue, which here is `Done(TurnLimit)` + [resume].
     */
    private suspend fun runTurns(out: FlowCollector<ChatEvent>) {
        var turn = 0
        var last: ChatEvent.Done? = null
        while (true) {
            if (turn >= config.maxTurns) {
                out.emit(ChatEvent.Done(FinishReason.TurnLimit, last?.timings, last?.usage))
                return
            }
            val reply = streamReply(out)
            last = reply.done
            if (reply.toolCalls.isEmpty()) {
                out.emit(reply.done)
                if (config.preEncodeAfterReply) startPreEncode()
                return
            }
            var pending = false
            for (call in reply.toolCalls) {
                out.emit(ChatEvent.ToolCall(call))
                val handler = config.registry.handler(call.name)
                if (handler == null) {
                    pending = true  // manual mode: the app adds the result
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
    private suspend fun runTool(handler: suspend (JsonArgs) -> String, call: ToolCall): String = try {
        val args = JsonArgs(call.argumentsJson)
        coroutineScope {
            val watcher = launch {
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

    /**
     * One model request. Appends the reply to the history and returns its Done (not emitted). A reply that
     * stops early keeps what was streamed, as tools/ui stores/chat/index.svelte.ts savePartialResponseIfNeeded
     * does: content and reasoning stay, partial tool calls are dropped.
     */
    private suspend fun streamReply(out: FlowCollector<ChatEvent>, req: ChatRequest = request()): Reply {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var done: ChatEvent.Done? = null
        try {
            model.chatInternal(req) { completionId = it }.collect { ev ->
                when (ev) {
                    is ChatEvent.Content -> content.append(ev.delta)
                    is ChatEvent.Reasoning -> reasoning.append(ev.delta)
                    is ChatEvent.ToolCall -> calls += ev.call
                    is ChatEvent.Done -> {
                        done = ev
                        ev.timings?.let { t ->
                            _contextUsage.value = ContextUsage(t.promptTokens + t.cachedTokens + t.predictedTokens, model.info.contextSize)
                        }
                    }
                    else -> Unit
                }
                if (ev !is ChatEvent.Done && ev !is ChatEvent.ToolCall) out.emit(ev)
            }
        } catch (e: CancellationException) {
            // A stop: tools/ui created the assistant message before streaming and never removes it, so the reply
            // stays, empty if nothing came (roles keep alternating); partial tool calls are dropped.
            append(Message.Assistant(content = content.toString(), reasoning = reasoning.toString().ifBlank { null }))
            throw e
        } catch (e: Throwable) {
            // An error (e.g. ContextExceeded): keep what was streamed, nothing when it's empty, so resume()
            // sends the same request again (P1 plan T9).
            if (content.isNotBlank() || reasoning.isNotBlank() || calls.isNotEmpty()) {
                append(Message.Assistant(content = content.toString(), reasoning = reasoning.toString().ifBlank { null }))
            }
            throw e
        } finally {
            completionId = null
        }
        // tools/ui stores/agentic/index.svelte.ts: `content: turnContent || undefined`, `reasoning_content: … || undefined`
        append(
            if (calls.isEmpty()) {
                Message.Assistant(content = content.toString(), reasoning = reasoning.toString().ifEmpty { null })
            } else {
                Message.Assistant(content = content.toString().ifEmpty { null }, reasoning = reasoning.toString().ifEmpty { null }, toolCalls = calls.toList())
            },
        )
        return Reply(done!!, calls)
    }
}

/** Adds the result of a pending tool call (after `Done(ToolCallsPending)`); then call [Conversation.resume]. */
public fun Conversation.addToolResult(toolCallId: String, result: String): Unit = addToolResultInternal(toolCallId, result)
