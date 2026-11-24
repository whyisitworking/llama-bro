package com.suhel.llamabro

import com.suhel.llamabro.internal.RealtimeLink
import com.suhel.llamabro.voice.RealtimeJson
import com.suhel.llamabro.voice.TranscriptionConfig
import com.suhel.llamabro.voice.TranscriptionEvent
import com.suhel.llamabro.voice.TranscriptionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject

/**
 * Opens a live transcription with this model (D138): upstream's `/v1/realtime` with `intent=transcription`, in memory,
 * set up as upstream's own client does (`ui/lib/voice/src/realtime.ts`). Returns once the server has taken the setup
 * (`session.updated`).
 *
 * Upstream transcribes this way only on a model with a streaming audio encoder: live when its template has a
 * transcription prompt (LFM3, Voxtral Realtime), otherwise per commit on a model that holds realtime conversations,
 * which this switches to transcribing ([TranscriptionSession.isLive] says which). Any other model that hears, such as
 * Qwen3-ASR, is refused: transcribe its audio with [transcribe] instead, as upstream's client falls back to
 * `/v1/audio/transcriptions`.
 *
 * @throws LlamaBroException.NotSupported if the model doesn't hear, or upstream refused live transcription for it,
 *   with upstream's message.
 * @throws LlamaBroException.InvalidRequest if the server refused the setup.
 * @throws LlamaBroException.ServerError if the server ended before the session opened, or sent an event the SDK can't
 *   read.
 * @throws IllegalStateException if the model was loaded for another task ([ModelTask]).
 */
public suspend fun Model.transcriptionSession(
    configure: TranscriptionConfig.Builder.() -> Unit = {},
): TranscriptionSession {
    val config = TranscriptionConfig(configure)
    checkCanTranscribe()
    // upstream answers with the kind it made: a live session, or a conversation the update switches to transcription
    var createdType: String? = null
    return openRealtime(
        params = mapOf("intent" to "transcription"),
        what = "transcription session",
        first = {},
        handshake = { event, link, events, scope ->
            when (RealtimeJson.type(event)) {
                "session.created" -> {
                    createdType = (event["session"] as? JsonObject)?.let { RealtimeJson.stringOf(it, "type") }.orEmpty()
                    link.send(RealtimeJson.transcriptionUpdate(config))
                    null
                }

                "session.updated" -> {
                    createdType?.let { session(link, events, event, it == "transcription", scope) }
                }

                "error" -> {
                    throw refusal(event, created = createdType != null)
                }

                else -> {
                    null
                }
            }
        },
        decode = RealtimeJson::decodeTranscription,
    )
}

private fun Model.checkCanTranscribe() {
    requireTask<ModelTask.Generate>("transcriptionSession", "ModelTask.Generate")
    requireHears("transcriptionSession")
}

/** An error before the session opened: before `session.created` upstream refuses the model, after it the setup. */
private fun refusal(event: JsonObject, created: Boolean): LlamaBroException {
    val message = RealtimeJson.errorMessage(event, "transcription session")
    return if (created) LlamaBroException.InvalidRequest(message) else LlamaBroException.NotSupported(message)
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
    return TranscriptionSession(
        link,
        events,
        RealtimeJson.stringOf(session, "id").orEmpty(),
        live,
        RealtimeJson.rate(input) ?: RealtimeJson.DEFAULT_INPUT_RATE,
        scope,
    )
}
