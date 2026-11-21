package com.suhel.llamabro

import com.suhel.llamabro.internal.RealtimeLink
import com.suhel.llamabro.voice.RealtimeJson
import com.suhel.llamabro.voice.VoiceConfig
import com.suhel.llamabro.voice.VoiceEvent
import com.suhel.llamabro.voice.VoiceSession
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
    val opened = CompletableDeferred<VoiceSession>()
    val events = Channel<VoiceEvent>(Channel.UNLIMITED)
    lateinit var link: RealtimeLink
    // its own scope: the session outlives the call that opened it, and ends with close() or the model
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
        try {
            realtime(emptyMap()) {
                link = it
                it.send(update)
            }.collect { text ->
                val event = RealtimeJson.parse(text)
                if (opened.isCompleted) {
                    val decoded = RealtimeJson.decode(event, text)
                    events.send(decoded)
                } else {
                    when (RealtimeJson.type(event)) {
                        "session.updated" -> {
                            opened.complete(session(link, events, event, scope))
                        }

                        "error" -> {
                            throw refused(event)
                        }

                        else -> {
                            Unit
                        } // session.created: the setup's answer follows
                    }
                }
            }
            opened.completeExceptionally(
                LlamaBroException.InvalidRequest("the server ended the voice session before it opened"),
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
            val failure = e as? LlamaBroException ?: LlamaBroException.InvalidRequest("unreadable voice event", e)
            opened.completeExceptionally(failure)
            events.close(failure)
        } finally {
            scope.cancel()
        }
    }
    // a caller cancelled while waiting, or a refused setup, ends the session it started
    return runCatching { opened.await() }
        .onFailure { scope.cancel() }
        .getOrThrow()
}

private fun Model.checkCanTalk() {
    requireTask<ModelTask.Generate>("voiceSession", "ModelTask.Generate")
    if (!info.supportsAudio) throw LlamaBroException.NotSupported("this model doesn't hear: no voice session")
}

/** The server's answer to a setup it refused, before the session opened. */
private fun refused(event: JsonObject) = LlamaBroException.InvalidRequest(errorMessage(event))

private fun Model.session(
    link: RealtimeLink,
    events: Channel<VoiceEvent>,
    updated: JsonObject,
    scope: CoroutineScope,
): VoiceSession {
    val session = updated["session"] as? JsonObject ?: JsonObject(emptyMap())
    val audio = session["audio"] as? JsonObject
    val input = audio?.get("input") as? JsonObject
    val output = audio?.get("output") as? JsonObject
    val inputRate = input.rate() ?: DEFAULT_INPUT_RATE
    return VoiceSession(
        link,
        events,
        session.string("id").orEmpty(),
        inputRate,
        output.rate(),
        output?.string("voice"),
        output?.string("language"),
        scope,
    )
}

private fun JsonObject?.rate(): Int? = ((this?.get("format") as? JsonObject)?.get("rate") as? JsonPrimitive)?.intOrNull

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun errorMessage(event: JsonObject): String =
    (event["error"] as? JsonObject)?.string("message") ?: "the server refused the voice session"

/** Upstream's input rate before the model's is known (server-realtime.cpp); a session always reports its own. */
private const val DEFAULT_INPUT_RATE = 16000
