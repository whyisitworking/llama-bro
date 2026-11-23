package com.suhel.llamabro

import com.suhel.llamabro.internal.RealtimeLink
import com.suhel.llamabro.voice.RealtimeJson
import com.suhel.llamabro.voice.TranscriptionConfig
import com.suhel.llamabro.voice.TranscriptionEvent
import com.suhel.llamabro.voice.TranscriptionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Opens a live transcription with this model (D138): upstream's `/v1/realtime` with `intent=transcription`, in memory,
 * set up as upstream's own client does (`ui/lib/voice/src/realtime.ts`). Returns once the server has taken the setup
 * (`session.updated`).
 *
 * Upstream transcribes this way only on a model with a streaming audio encoder: live when its template has a
 * transcription prompt (LFM3, Voxtral Realtime), otherwise per commit on a model that holds realtime conversations,
 * which this switches to transcribing ([TranscriptionSession.live] says which). Any other model that hears, such as
 * Qwen3-ASR, is refused: transcribe its audio with [transcribe] instead, as upstream's client falls back to
 * `/v1/audio/transcriptions`.
 *
 * @throws LlamaBroException.NotSupported if the model doesn't hear, or upstream refused live transcription for it,
 *   with upstream's message.
 * @throws LlamaBroException.InvalidRequest if the server refused the setup.
 * @throws IllegalStateException if the model was loaded for another task ([ModelTask]).
 */
public suspend fun Model.transcriptionSession(
    configure: TranscriptionConfig.Builder.() -> Unit = {},
): TranscriptionSession {
    val config = TranscriptionConfig(configure)
    checkCanTranscribe()
    val opened = CompletableDeferred<TranscriptionSession>()
    val events = Channel<TranscriptionEvent>(Channel.UNLIMITED)
    lateinit var link: RealtimeLink
    var createdType: String? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        try {
            realtime(mapOf("intent" to "transcription")) { link = it }.collect { text ->
                val event = RealtimeJson.parse(text)
                when {
                    opened.isCompleted -> {
                        events.send(RealtimeJson.decodeTranscription(event, text))
                    }

                    RealtimeJson.type(event) == "error" -> {
                        throw refusal(event, created = createdType != null)
                    }

                    RealtimeJson.type(event) == "session.created" -> {
                        createdType = (event["session"] as? JsonObject)?.string("type").orEmpty()
                        link.send(RealtimeJson.transcriptionUpdate(config))
                    }

                    RealtimeJson.type(event) == "session.updated" && createdType != null -> {
                        opened.complete(session(link, events, event, createdType == "transcription", scope))
                    }
                }
            }
            opened.completeExceptionally(
                LlamaBroException.InvalidRequest("the server ended the transcription session before it opened"),
            )
            events.close()
        } catch (e: CancellationException) {
            opened.cancel(e)
            events.close()
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // besides the SDK's own errors, an event that isn't a JSON object, which upstream never sends
            val failure =
                e as? LlamaBroException ?: LlamaBroException.InvalidRequest("unreadable transcription event", e)
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

private fun session(
    link: RealtimeLink,
    events: Channel<TranscriptionEvent>,
    updated: JsonObject,
    live: Boolean,
    scope: CoroutineScope,
): TranscriptionSession {
    val session = updated["session"] as? JsonObject ?: JsonObject(emptyMap())
    val input = (session["audio"] as? JsonObject)?.get("input") as? JsonObject
    val rate = ((input?.get("format") as? JsonObject)?.get("rate") as? JsonPrimitive)?.intOrNull
    return TranscriptionSession(link, events, session.string("id").orEmpty(), live, rate ?: DEFAULT_RATE, scope)
}

private fun Model.checkCanTranscribe() {
    requireTask<ModelTask.Generate>("transcriptionSession", "ModelTask.Generate")
    if (!info.supportsAudio) throw LlamaBroException.NotSupported("this model doesn't hear: no transcription session")
}

/** An error before the session opened: before `session.created` upstream refuses the model, after it the setup. */
private fun refusal(event: JsonObject, created: Boolean): LlamaBroException =
    if (created) {
        LlamaBroException.InvalidRequest(errorMessage(event))
    } else {
        LlamaBroException.NotSupported(errorMessage(event))
    }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun errorMessage(event: JsonObject): String =
    (event["error"] as? JsonObject)?.string("message") ?: "the server refused the transcription session"

/** Upstream's audio rate when a session doesn't report one; every session does. */
private const val DEFAULT_RATE = 16000
