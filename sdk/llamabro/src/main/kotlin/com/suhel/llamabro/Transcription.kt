package com.suhel.llamabro

import com.suhel.llamabro.audio.TranscribeConfig
import com.suhel.llamabro.audio.Transcript
import com.suhel.llamabro.internal.ErrorMapping
import com.suhel.llamabro.internal.JsonMergePatch
import com.suhel.llamabro.internal.Media
import kotlinx.coroutines.flow.single
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What is said in [source] (`POST /v1/audio/transcriptions`), on a model that hears ([ModelInfo.supportsAudio]): a
 * [Transcript] with the text, [Transcript.truncated] when it was cut at `maxTokens`
 * ([D63](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d63),
 * [D84](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d84),
 * [D136](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d136)). Upstream's transcription
 * response has no finish reason, so a cut is inferred from the reply having as many tokens as `maxTokens`
 * (`output_tokens >= max_tokens`); with no `maxTokens`, a transcript stopped by the context's end is not truncated.
 * [configure] sets the language (e.g. `"fr"`), the prompt (words to expect, or the model's instruction in place of its
 * own), `maxTokens`, `temperature`, `seed` and `extra` ([TranscribeConfig]), as upstream reads them: llama-server runs
 * a transcription as a chat request. It is a builder like [speak] and the sessions, one shape across the audio family
 * ([D143](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d143)).
 *
 * The clip must be WAV, MP3 or FLAC, the formats llama.cpp decodes. A file or descriptor is read natively during the
 * call, off the caller's thread; the app may close a descriptor once this returns.
 *
 * @throws LlamaBroException.NotSupported if the model doesn't hear ([ModelInfo.supportsAudio]); nothing is sent.
 * @throws LlamaBroException.InvalidRequest if the clip is missing, unreadable or in another format; no transcription is
 *   made. Engine failures stay exceptions too.
 * @throws LlamaBroException.ServerError if llama-server's reply can't be read.
 * @throws IllegalArgumentException if a setting [configure] sets is refused (each [TranscribeConfig.Builder] setter
 *   says which).
 * @throws IllegalStateException if the model was loaded for another task ([ModelTask]).
 */
public suspend fun Model.transcribe(
    source: MediaSource,
    configure: TranscribeConfig.Builder.() -> Unit = {},
): Transcript {
    requireTask<ModelTask.Generate>("transcribe", "ModelTask.Generate")
    requireHears("transcribe")
    Media.checkAudio(info, source)
    val body = transcriptionBody(TranscribeConfig(configure))
    val attachment = Media.attachment("file", Media.CLIP_TYPE, source)
    val reply = request(Endpoint.Transcriptions, body.toString(), listOf(attachment))
    val (text, written) =
        ErrorMapping.readReply("transcription") {
            val response = Json.parseToJsonElement(reply.single()).jsonObject
            val text = response["text"]?.jsonPrimitive?.content.orEmpty()
            text to (response["usage"] as? JsonObject)?.get("output_tokens")?.jsonPrimitive?.intOrNull
        }
    // upstream's transcription response has no finish reason (to_json_oaicompat_asr): a reply as long as the limit
    // was cut there
    val limit = (body["max_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()
    return Transcript(text, truncated = limit != null && written != null && written >= limit)
}

/**
 * The form fields, as upstream reads a multipart upload (convert_transcriptions_to_chatcmpl): `stream`, `max_tokens`
 * and `temperature` as strings, which it converts; every other field is copied into the chat request it runs, so it
 * keeps its JSON type. The keys come in the order a chat request's sampling gives them.
 */
private fun transcriptionBody(config: TranscribeConfig): JsonObject {
    val base =
        buildJsonObject {
            config.temperature?.let { put("temperature", it) }
            config.seed?.let { put("seed", it) }
            config.maxTokens?.let { put("max_tokens", it) }
            config.language?.let { put("language", it) }
            config.prompt?.let { put("prompt", it) }
            put("response_format", "json")
        }
    val merged = JsonMergePatch.applyExtra(base, config.extra, TranscribeConfig.OWNED)
    return JsonObject(
        merged.mapValues { (k, v) -> if (k in STRING_FIELDS && v is JsonPrimitive) JsonPrimitive(v.content) else v } +
            ("stream" to JsonPrimitive("false")),
    )
}

private val STRING_FIELDS = setOf("max_tokens", "temperature")
