package com.suhel.llamabro

import com.suhel.llamabro.internal.RealtimeLink
import com.suhel.llamabro.voice.RealtimeJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Opens a session on upstream's `/v1/realtime`, in memory (D97), as `voiceSession` (D98) and `transcriptionSession`
 * (D138) both do: [first] sends the client's opening events; each server event goes to [handshake] until it returns the
 * open session (null: keep waiting; a `LlamaBroException` thrown refuses the open); every event after that is
 * [decode]d onto the session's channel. Returns once the session is open. The session's coroutines live in their own
 * scope, which its `close()` cancels; a caller cancelled while waiting, or a refused open, ends them too.
 */
internal suspend fun <S, E> Model.openRealtime(
    params: Map<String, String>,
    /** What the session is, for the errors: "voice session", "transcription session". */
    what: String,
    first: (RealtimeLink) -> Unit,
    handshake: (event: JsonObject, link: RealtimeLink, events: Channel<E>, scope: CoroutineScope) -> S?,
    decode: (event: JsonObject, text: String) -> E,
): S {
    val opened = CompletableDeferred<S>()
    val events = Channel<E>(Channel.UNLIMITED)
    lateinit var link: RealtimeLink
    // its own scope: the session outlives the call that opened it, and ends with close() or the model
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        try {
            realtime(params) {
                link = it
                first(it)
            }.collect { text ->
                val event = RealtimeJson.parse(text)
                if (opened.isCompleted) {
                    events.send(decode(event, text))
                } else {
                    handshake(event, link, events, scope)?.let(opened::complete)
                }
            }
            opened.completeExceptionally(
                LlamaBroException.InvalidRequest("the server ended the $what before it opened"),
            )
            events.close()
        } catch (e: CancellationException) {
            opened.cancel(e)
            events.close()
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // besides the SDK's own errors: an event that isn't a JSON object, or audio that isn't base64, which
            // upstream never sends. Either ends the session typed; uncaught, it would leave the open hanging.
            val failure = e as? LlamaBroException ?: LlamaBroException.InvalidRequest("unreadable $what event", e)
            opened.completeExceptionally(failure)
            events.close(failure)
        } finally {
            scope.cancel()
        }
    }
    return runCatching { opened.await() }
        .onFailure { scope.cancel() }
        .getOrThrow()
}
