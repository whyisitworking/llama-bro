package com.suhel.llamabro.conversation

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.chat.RequestOptions
import com.suhel.llamabro.chat.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A [Conversation]'s agent loop, ported from tools/ui stores/agentic/index.svelte.ts (D14): it asks the model, runs
 * the tools a reply calls, in order, sends their results back, and stops at a reply without calls, at the turn limit,
 * or where the app must act (a manual tool, an approval, an abort). It owns the tool-call policy of D62: which calls
 * run, wait or get an error, what text of a handler's failure reaches the model, and approval. It exists apart from
 * [Conversation] so that policy reads in one place; the conversation keeps the history and the flows.
 *
 * @param history the conversation's history now; the loop finds the last batch of tool calls in it.
 * @param append adds a tool result to the history.
 * @param chat streams one model request of the history with the given options, appends the reply, and returns it.
 * @param awaitClosed returns when the model closes, which cancels a running handler.
 * @param afterAnswer runs after a reply without tool calls ended the loop (`preEncodeAfterReply`).
 */
internal class AgentLoop(
    private val registry: ToolRegistry,
    private val maxTurns: () -> Int,
    private val history: () -> List<Message>,
    private val append: (Message) -> Unit,
    private val chat: suspend (FlowCollector<ChatEvent>, RequestOptions) -> ReplyStreamer.Reply,
    private val awaitClosed: suspend () -> Unit,
    private val afterAnswer: () -> Unit,
) {
    /**
     * Send; if the reply calls tools, run them one after another in order, append each result, and send again; stop
     * on a reply without tool calls. The turn check sits at the top of the loop (`turn >= maxTurns`), so maxTurns
     * counts model requests per flow; tools/ui then asks the user to continue, which here is `Done(TurnLimit)` +
     * `resume`.
     *
     * It first finishes the last batch, from call [from] (D62): a no-op for `send` and `regenerate`, whose history
     * ends at a user message; for `resume`, `approve` and `deny` it runs the calls a stop left without a result.
     */
    suspend fun run(out: FlowCollector<ChatEvent>, options: RequestOptions, from: Int = 0) {
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
            if (turn >= maxTurns()) return out.emitStop(FinishReason.TurnLimit, last)
            val reply = chat(out, options)
            last = reply.done
            turn++
            if (reply.toolCalls.isEmpty()) {
                out.emit(reply.done)
                afterAnswer()
                return
            }
        }
    }

    /** The call waiting for approval, from the history and the registry (see [ToolBatch.awaitingApproval]). */
    val awaitingApproval: ToolCall?
        get() = ToolBatch.of(history())?.awaitingApproval(registry)?.value

    /**
     * Runs the call waiting for approval. Returns the index in its batch to continue the turn from, or null when its
     * handler aborted, after emitting `Done(ToolAborted)`.
     * @throws IllegalArgumentException if [callId] isn't the call waiting.
     */
    suspend fun approve(out: FlowCollector<ChatEvent>, callId: String): Int? {
        val (index, call) = approvalFor(callId)
        val handler = checkNotNull(registry.entry(call.name)?.handler) { "no handler for ${call.name}" }
        answer(out, call, runTool(handler, call))?.let { abort ->
            out.emitStop(FinishReason.ToolAborted, null, abort)
            return null
        }
        return index + 1
    }

    /**
     * Answers the call waiting for approval with `Error: <reason>` (`denied by the user` without one). Returns the
     * index in its batch to continue the turn from.
     * @throws IllegalArgumentException if [callId] isn't the call waiting.
     */
    suspend fun deny(out: FlowCollector<ChatEvent>, callId: String, reason: String?): Int {
        val (index, call) = approvalFor(callId)
        answer(out, call, ToolOutcome.Result("Error: ${reason ?: "denied by the user"}"))
        return index + 1
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
        val batch = ToolBatch.of(history()) ?: return BatchOutcome.Answered
        val left = batch.unanswered.mapTo(mutableSetOf()) { it.id }
        for (call in batch.calls.drop(from).filter { it.id in left }) {
            out.emit(ChatEvent.ToolCallReceived(call))
            val entry = registry.entry(call.name)
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
        val complete = ToolBatch.of(history())?.unanswered.isNullOrEmpty()
        return if (complete) BatchOutcome.Answered else BatchOutcome.Pending
    }

    /**
     * Records a call's outcome: a result is appended and emitted; an abort is returned, and neither this call nor
     * the later ones get a result, so the turn ends here for the app and `resume` runs this call again (D62).
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

    /** `approve` and `deny` act only on the call waiting; returns it with its index in the batch. */
    private fun approvalFor(callId: String): IndexedValue<ToolCall> {
        val waiting = ToolBatch.of(history())?.awaitingApproval(registry)
        require(waiting != null && waiting.value.id == callId) { "tool call $callId isn't waiting for approval" }
        return waiting
    }

    /** What one automatic call produced: a result for the model, or an abort that ends the turn. */
    private sealed interface ToolOutcome {
        /** [error] is the exception behind `Error: the tool failed`, or an [LbToolError]'s cause; for the app only. */
        class Result(val text: String, val error: Throwable? = null) : ToolOutcome

        class Aborted(val abort: LbToolAbort) : ToolOutcome
    }

    /**
     * One handler call. Unlike tools/ui, which sends every exception's message to the model, only an [LbToolError]'s
     * message does; any other exception sends a generic text, so a token or personal data in it stays out of the
     * prompt and the saved chat (D62). The arguments are parsed inside the try block, so ones that don't parse
     * become `Error: invalid arguments: …`. Cancelling the flow cancels the handler; closing the model cancels it
     * too and fails the flow with ModelClosed. A cancellation while the turn is still active is the handler's own
     * (its `withTimeout`), so it is a tool failure, not a silent end of the turn. A JVM `Error` (out of memory, a
     * stack overflow) is not the tool's answer and ends the turn. Exceptions are sorted inside the scope, so the app
     * gets the handler's own instance, not a copy made by coroutines' stack-trace recovery when it leaves the scope.
     */
    private suspend fun runTool(handler: suspend (ToolCall) -> String, call: ToolCall): ToolOutcome =
        coroutineScope {
            val watcher =
                launch {
                    awaitClosed()
                    throw LlamaBroException.ModelClosed()
                }
            try {
                ToolOutcome.Result(handler(call))
            } catch (e: CancellationException) {
                if (!isActive) throw e
                ToolOutcome.Result("Error: the tool failed", e)
            } catch (e: LlamaBroException.ModelClosed) {
                throw e
            } catch (e: LbToolAbort) {
                ToolOutcome.Aborted(e)
            } catch (e: LbToolError) {
                // the cause is for the app (the original exception of a tool that explains itself), never the model
                ToolOutcome.Result("Error: ${e.message.orEmpty()}", e.cause)
            } catch (e: Exception) {
                ToolOutcome.Result("Error: the tool failed", e)
            } finally {
                watcher.cancel()
            }
        }
}

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
