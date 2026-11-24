package com.suhel.llamabro

import com.suhel.llamabro.internal.RealtimeLink
import com.suhel.llamabro.voice.RealtimeJson
import com.suhel.llamabro.voice.VoiceConfig
import com.suhel.llamabro.voice.VoiceEvent
import com.suhel.llamabro.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonObject

/**
 * Opens a spoken conversation with this model (D95): upstream's `/v1/realtime` conversation, in memory (D97). Returns
 * once the server has taken [configure] (`session.updated`), so a voice or setting it refuses fails here.
 *
 * Nothing in [configure] is set for the app (D98): LFM3-Omni needs a voice, a language and an instruction to answer
 * (F166), as in `voiceSession { voice = "female"; language = "en"; instructions = "…" }`. Close the session when
 * done; closing the model ends it too.
 *
 * @throws LlamaBroException.NotSupported if the model doesn't hear.
 * @throws LlamaBroException.InvalidRequest if the server refused the setup (a model that can't hold a conversation),
 *   with upstream's message.
 * @throws LlamaBroException.ServerError if the server ended before the session opened, or sent an event the SDK can't
 *   read.
 * @throws IllegalArgumentException if [VoiceConfig.Builder.voice] isn't one of [ModelInfo.voices].
 * @throws IllegalStateException if the model was loaded for another task ([ModelTask]).
 */
public suspend fun Model.voiceSession(configure: VoiceConfig.Builder.() -> Unit = {}): VoiceSession {
    val config = VoiceConfig(configure)
    checkCanTalk()
    // a model's voices are its own names: a typo is caught here, not as noise or a refusal from the server
    config.voice?.let { voice ->
        require(info.voices.isEmpty() || voice in info.voices) {
            "voice \"$voice\" isn't one this model speaks with; ModelInfo.voices: ${info.voices}"
        }
    }
    val update = RealtimeJson.sessionUpdate(config)
    return openRealtime(
        params = emptyMap(),
        what = "voice session",
        first = { it.send(update) },
        handshake = { event, link, events, scope ->
            when (RealtimeJson.type(event)) {
                "session.updated" -> session(link, events, event, scope)
                "error" -> throw LlamaBroException.InvalidRequest(RealtimeJson.errorMessage(event, "voice session"))
                else -> null // session.created: the setup's answer follows
            }
        },
        decode = RealtimeJson::decode,
    )
}

private fun Model.checkCanTalk() {
    requireTask<ModelTask.Generate>("voiceSession", "ModelTask.Generate")
    requireHears("voiceSession")
}

private fun session(
    link: RealtimeLink,
    events: Channel<VoiceEvent>,
    updated: JsonObject,
    scope: CoroutineScope,
): VoiceSession {
    val session = updated["session"] as? JsonObject ?: JsonObject(emptyMap())
    val audio = session["audio"] as? JsonObject
    val output = audio?.get("output") as? JsonObject
    return VoiceSession(
        link,
        events,
        RealtimeJson.stringOf(session, "id").orEmpty(),
        RealtimeJson.rate(audio?.get("input") as? JsonObject) ?: RealtimeJson.DEFAULT_INPUT_RATE,
        RealtimeJson.rate(output),
        output?.let { RealtimeJson.stringOf(it, "voice") },
        output?.let { RealtimeJson.stringOf(it, "language") },
        scope,
    )
}
