package com.suhel.llamabro.voice

import com.suhel.llamabro.internal.JsonMergePatch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64

/** Upstream's realtime events (`server-realtime.cpp`) to and from Kotlin, as plain functions over JSON. */
internal object RealtimeJson {
    /** The `session.update` that sets [config] up; sent even when nothing is set, so `session.updated` follows. */
    fun sessionUpdate(config: VoiceConfig): String {
        val session =
            buildJsonObject {
                put("type", "realtime")
                config.instructions?.let { put("instructions", it) }
                putJsonArray("output_modalities") {
                    // upstream's order, text first (output_modalities_json)
                    OutputModality.entries.filter { it in config.outputModalities }.forEach {
                        add(JsonPrimitive(it.name.lowercase()))
                    }
                }
                config.maxOutputTokens?.let { put("max_output_tokens", it) }
                val input = config.inputSampleRate
                val output = config.voice != null || config.language != null
                if (input != null || output) {
                    putJsonObject("audio") {
                        if (input != null) {
                            putJsonObject("input") {
                                putJsonObject("format") {
                                    put("type", "audio/pcm")
                                    put("rate", input)
                                }
                            }
                        }
                        if (output) {
                            putJsonObject("output") {
                                config.voice?.let { put("voice", it) }
                                config.language?.let { put("language", it) }
                            }
                        }
                    }
                }
            }
        val merged = JsonMergePatch.applyExtra(session, config.extra, VoiceConfig.OWNED)
        return event("session.update") { put("session", merged) }
    }

    fun append(pcm: ShortArray, offset: Int, length: Int): String {
        val bytes = ByteArray(length * 2)
        for (i in 0 until length) {
            val v = pcm[offset + i].toInt()
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr BYTE_BITS).toByte()
        }
        return event("input_audio_buffer.append") { put("audio", Base64.encode(bytes)) }
    }

    /**
     * The `session.update` upstream's client sends once a transcription session is created (realtime.ts
     * handleSessionCreated): a transcription session, which switches a model that holds realtime conversations to
     * transcribing per commit, with the input rate, language and prompt when set.
     */
    fun transcriptionUpdate(config: TranscriptionConfig): String {
        val session =
            buildJsonObject {
                put("type", "transcription")
                putJsonObject("audio") {
                    putJsonObject("input") {
                        config.inputSampleRate?.let { rate ->
                            putJsonObject("format") {
                                put("type", "audio/pcm")
                                put("rate", rate)
                            }
                        }
                        if (config.language != null || config.prompt != null) {
                            putJsonObject("transcription") {
                                config.language?.let { put("language", it) }
                                config.prompt?.let { put("prompt", it) }
                            }
                        }
                    }
                }
            }
        return event("session.update") { put("session", session) }
    }

    val commit: String = event("input_audio_buffer.commit") {}

    /** Ends the input (`final: true`, server-context.cpp ws_realtime): the rest becomes the last item. */
    val commitFinal: String = event("input_audio_buffer.commit") { put("final", true) }
    val respond: String = event("response.create") {}
    val cancel: String = event("response.cancel") {}

    fun userText(text: String): String =
        event("conversation.item.create") {
            putJsonObject("item") {
                put("type", "message")
                put("role", "user")
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "input_text")
                                put("text", text)
                            },
                        )
                    },
                )
            }
        }

    private fun event(type: String, body: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("type", type)
            body()
        }.toString()

    /** One server event, parsed. @throws SerializationException if it isn't a JSON object. */
    fun parse(text: String): JsonObject =
        Json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("not a JSON object: $text")

    fun type(event: JsonObject): String = event.string("type").orEmpty()

    /** The typed event for [event], or [VoiceEvent.Other] with [text] for one that has no type of its own. */
    fun decode(event: JsonObject, text: String): VoiceEvent {
        val type = type(event)
        return when (type) {
            "input_audio_buffer.committed" -> {
                VoiceEvent.TurnCommitted(event.string("item_id").orEmpty())
            }

            "response.created" -> {
                VoiceEvent.ReplyStarted((event["response"] as? JsonObject)?.string("id").orEmpty())
            }

            "response.output_text.delta" -> {
                VoiceEvent.ReplyText(event.string("response_id").orEmpty(), event.string("delta").orEmpty())
            }

            "response.output_audio.delta" -> {
                VoiceEvent.ReplyAudio(event.string("response_id").orEmpty(), pcm(event.string("delta").orEmpty()))
            }

            "response.done" -> {
                done(event["response"] as? JsonObject ?: JsonObject(emptyMap()))
            }

            "error" -> {
                val error = event["error"] as? JsonObject ?: JsonObject(emptyMap())
                VoiceEvent.Error(error.string("type").orEmpty(), error.string("message").orEmpty())
            }

            else -> {
                VoiceEvent.Other(type, text)
            }
        }
    }

    /** The typed transcription event for [event], or [TranscriptionEvent.Other] with [text]. */
    fun decodeTranscription(event: JsonObject, text: String): TranscriptionEvent {
        val type = type(event)
        val item = event.string("item_id").orEmpty()
        return when (type) {
            "input_audio_buffer.committed" -> {
                TranscriptionEvent.Committed(item)
            }

            "conversation.item.input_audio_transcription.delta" -> {
                TranscriptionEvent.Text(item, event.string("delta").orEmpty())
            }

            "conversation.item.input_audio_transcription.completed" -> {
                TranscriptionEvent.Done(item, event.string("transcript").orEmpty())
            }

            "error" -> {
                val error = event["error"] as? JsonObject ?: JsonObject(emptyMap())
                TranscriptionEvent.Error(error.string("type").orEmpty(), error.string("message").orEmpty())
            }

            else -> {
                TranscriptionEvent.Other(type, text)
            }
        }
    }

    /** The reply's text is its message items' `output_text` parts, as upstream fills them in at the end. */
    private fun done(response: JsonObject): VoiceEvent.ReplyDone {
        val text =
            (response["output"] as? JsonArray)
                .orEmpty()
                .filterIsInstance<JsonObject>()
                .filter { it.string("type") == "message" }
                .flatMap { (it["content"] as? JsonArray).orEmpty() }
                .filterIsInstance<JsonObject>()
                .filter { it.string("type") == "output_text" }
                .joinToString("") { it.string("text").orEmpty() }
        val tokens = ((response["usage"] as? JsonObject)?.get("output_tokens") as? JsonPrimitive)?.intOrNull
        return VoiceEvent.ReplyDone(
            response.string("id").orEmpty(),
            text,
            response.string("status") == "cancelled",
            tokens,
        )
    }

    /** Little-endian 16-bit PCM in base64, as upstream encodes it (encode_pcm16). */
    fun pcm(base64: String): ShortArray {
        val bytes = Base64.decode(base64)
        return ShortArray(bytes.size / 2) { i ->
            ((bytes[2 * i].toInt() and BYTE_MASK) or (bytes[2 * i + 1].toInt() shl BYTE_BITS)).toShort()
        }
    }

    /** Upstream's input rate when a session doesn't report one (server-realtime.cpp); every session does. */
    const val DEFAULT_INPUT_RATE = 16000

    /** A session's audio `format.rate`, from its `audio.input` or `audio.output`. */
    fun rate(audio: JsonObject?): Int? =
        ((audio?.get("format") as? JsonObject)?.get("rate") as? JsonPrimitive)?.intOrNull

    fun stringOf(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull

    /** An `error` event's message, or a plain one naming [what] was refused. */
    fun errorMessage(event: JsonObject, what: String): String =
        (event["error"] as? JsonObject)?.let { stringOf(it, "message") } ?: "the server refused the $what"

    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xff

    private fun JsonObject.string(key: String): String? = stringOf(this, key)
}
