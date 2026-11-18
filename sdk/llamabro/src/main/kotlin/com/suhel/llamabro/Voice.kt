package com.suhel.llamabro

import com.suhel.llamabro.internal.RealtimeLink
import com.suhel.llamabro.voice.RealtimeJson
import com.suhel.llamabro.voice.TurnCaptions
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
import kotlinx.serialization.SerializationException
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
 * @throws LlamaBroException.InvalidRequest if it was loaded for another task, or the server refused the setup (a
 *   model that can't hold a conversation, an unknown voice), with upstream's message.
 */
public suspend fun Model.voiceSession(configure: VoiceConfig.Builder.() -> Unit = {}): VoiceSession {
    val config = VoiceConfig(configure)
    checkCanTalk(config)
    val update = RealtimeJson.sessionUpdate(config)
    val opened = CompletableDeferred<VoiceSession>()
    val events = Channel<VoiceEvent>(Channel.UNLIMITED)
    lateinit var link: RealtimeLink
    // its own scope: the session outlives the call that opened it, and ends with close() or the model
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var open: VoiceSession? = null
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
                    open?.observe(decoded)
                } else {
                    when (RealtimeJson.type(event)) {
                        "session.updated" -> {
                            val session = session(link, events, event, scope, config)
                            open = session
                            opened.complete(session)
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
        } catch (e: LlamaBroException) {
            opened.completeExceptionally(e)
            events.close(e)
        } catch (e: SerializationException) {
            // an event that isn't a JSON object: upstream never sends one
            val failure = LlamaBroException.InvalidRequest("the voice session sent an unreadable event", e)
            opened.completeExceptionally(failure)
            events.close(failure)
        }
    }
    // a caller cancelled while waiting, or a refused setup, ends the session it started
    return runCatching { opened.await() }
        .onFailure { scope.cancel() }
        .getOrThrow()
}

private fun Model.checkCanTalk(config: VoiceConfig) {
    requireTask<ModelTask.Generate>("voiceSession", "ModelTask.Generate")
    if (!info.supportsAudio) throw LlamaBroException.NotSupported("this model doesn't hear: no voice session")
    // the session holds its slot while it is open, so a caption on the same slot would wait for it to end (F170)
    if (config.userTranscription != null && info.totalSlots < 2) {
        throw LlamaBroException.InvalidRequest(
            "transcribeUser needs a second slot: load the model with parallelSlots = 2 (it has ${info.totalSlots})",
        )
    }
}

/** The server's answer to a setup it refused, before the session opened. */
private fun refused(event: JsonObject) = LlamaBroException.InvalidRequest(errorMessage(event))

private fun Model.session(
    link: RealtimeLink,
    events: Channel<VoiceEvent>,
    updated: JsonObject,
    scope: CoroutineScope,
    config: VoiceConfig,
): VoiceSession {
    val session = updated["session"] as? JsonObject ?: JsonObject(emptyMap())
    val audio = session["audio"] as? JsonObject
    val input = audio?.get("input") as? JsonObject
    val output = audio?.get("output") as? JsonObject
    val inputRate = input.rate() ?: DEFAULT_INPUT_RATE
    val captions =
        config.userTranscription?.let { t ->
            TurnCaptions(scope, events, inputRate) { wav ->
                when (val r = transcribe(MediaSource.bytes(wav), t.language, t.prompt)) {
                    is Typed.Value -> r.value
                    is Typed.Incomplete -> r.json
                    is Typed.Invalid -> r.json
                }
            }
        }
    return VoiceSession(
        link,
        events,
        session.string("id").orEmpty(),
        inputRate,
        output.rate(),
        output?.string("voice"),
        output?.string("language"),
        scope,
        captions,
    )
}

private fun JsonObject?.rate(): Int? = ((this?.get("format") as? JsonObject)?.get("rate") as? JsonPrimitive)?.intOrNull

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun errorMessage(event: JsonObject): String =
    (event["error"] as? JsonObject)?.string("message") ?: "the server refused the voice session"

/** Upstream's input rate before the model's is known (server-realtime.cpp); a session always reports its own. */
private const val DEFAULT_INPUT_RATE = 16000
