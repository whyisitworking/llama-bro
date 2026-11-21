package com.suhel.llamabro

import com.suhel.llamabro.chat.RequestOptions
import com.suhel.llamabro.internal.ChatJson
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
 * [Transcript] with the text, [Transcript.truncated] when it was cut at `maxTokens` (D63, D84, D136). Upstream's
 * transcription response has no finish reason, so a cut is inferred from the reply having as many tokens as
 * `maxTokens` (`output_tokens >= max_tokens`); with no `maxTokens`, a transcript stopped by the context's end is not
 * truncated. [language] (e.g. `"fr"`) and [prompt] (words to expect, or the model's instruction in place of its own)
 * are upstream's; [options]' sampling and `extra` apply, as llama-server runs it as a chat request.
 *
 * The clip must be WAV, MP3 or FLAC, the formats llama.cpp decodes. A file or descriptor is read natively during the
 * call, off the caller's thread; the app may close a descriptor once this returns.
 *
 * @throws LlamaBroException.InvalidRequest if the model doesn't hear, or the clip is missing, unreadable or in another
 *   format; no transcription is made. Engine failures stay exceptions too.
 * @throws IllegalStateException if the model was loaded for another task ([ModelTask]).
 */
public suspend fun Model.transcribe(
    source: MediaSource,
    language: String? = null,
    prompt: String? = null,
    options: RequestOptions.Builder.() -> Unit = {},
): Transcript {
    requireTask<ModelTask.Generate>("transcribe", "ModelTask.Generate")
    Media.checkAudio(info, source)
    val body = transcriptionBody(RequestOptions(options), language, prompt)
    val attachment = Media.attachment("file", WAV, source)
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

/** What [transcribe] heard: the [text], and whether it was cut at `maxTokens` before the clip's end ([truncated]). */
public data class Transcript(public val text: String, public val truncated: Boolean = false)

/**
 * The form fields, as upstream reads a multipart upload (convert_transcriptions_to_chatcmpl): `stream`, `max_tokens`
 * and `temperature` as strings, which it converts; every other field is copied into the chat request it runs, so it
 * keeps its JSON type.
 */
private fun transcriptionBody(options: RequestOptions, language: String?, prompt: String?): JsonObject {
    val base =
        buildJsonObject {
            ChatJson.options(options).forEach { (k, v) -> put(k, v) }
            language?.let { put("language", it) }
            prompt?.let { put("prompt", it) }
            put("response_format", "json")
        }
    val merged =
        options.extra?.let {
            JsonMergePatch.apply(base, JsonMergePatch.checkPatch(it, RequestOptions.OWNED)) as JsonObject
        } ?: base
    return JsonObject(
        merged.mapValues { (k, v) -> if (k in STRING_FIELDS && v is JsonPrimitive) JsonPrimitive(v.content) else v } +
            ("stream" to JsonPrimitive("false")),
    )
}

private val STRING_FIELDS = setOf("max_tokens", "temperature")

/** The type a transcription's file is sent as; the native side checks its header, whatever the name says. */
private const val WAV = "audio/wav"
