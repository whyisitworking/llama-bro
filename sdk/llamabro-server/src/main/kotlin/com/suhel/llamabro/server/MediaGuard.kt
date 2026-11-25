package com.suhel.llamabro.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Keys whose string value is media, anywhere in a body: chat's `image_url`, `video_url` and `audio_url`, Responses'
 * `input_image.image_url` and `input_file`'s `file_url` and `file_data`, rerank's `image` (a string or a list).
 */
private val urlKeys = setOf("image_url", "video_url", "audio_url", "file_url", "file_data", "image")

/**
 * Keys whose object holds media in `url` or `data`: chat's `image_url`, `input_audio`, `input_video`, `video_url` and
 * `audio_url`, Anthropic's `source` of an `image` or `document` block, and rerank's `image`.
 */
private val holderKeys = setOf("image_url", "input_audio", "input_video", "video_url", "audio_url", "source", "image")

/** Anthropic's `source` types whose `data` is the content itself, not a URL; upstream makes the `data:` URL. */
private val rawDataTypes = setOf("base64", "text")

/**
 * The first media value in [body] that is a URL to fetch or read, or null when there is none. llama-server's
 * `handle_media` (server-common.cpp) fetches what starts with `http`, reads `file://` from the media folder the SDK
 * gives it for chat attachments, decodes `data:` URLs and decodes anything else as base64 (OpenAI's `input_audio`
 * sends raw base64). So a remote client could read another chat's pictures or reach the LAN through the device;
 * only a `data:` URL or a value with no scheme passes
 * ([D157](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d157)). The keys are
 * checked wherever they are, so a nesting upstream adds (a history, a tool result) can't slip past; text is never
 * media, so `file://` in a message's text passes.
 */
internal fun firstNonDataMedia(body: JsonElement): String? = walk(body)

// deep recursion on the heap: a body nested thousands deep can't overflow the stack of the request's thread
private val walk =
    DeepRecursiveFunction<JsonElement, String?> { element ->
        when (element) {
            is JsonObject -> {
                element.entries.firstNotNullOfOrNull { (key, value) ->
                    refusedAt(key, value)
                        ?: callRecursive(value)
                }
            }

            is JsonArray -> {
                element.firstNotNullOfOrNull { callRecursive(it) }
            }

            is JsonPrimitive -> {
                null
            }
        }
    }

/** A refused URL held directly by [key]'s [value]; deeper values are left to the walk. */
private fun refusedAt(key: String, value: JsonElement): String? =
    when {
        key in urlKeys && value is JsonPrimitive -> refused(value)
        key in urlKeys && value is JsonArray -> value.filterIsInstance<JsonPrimitive>().firstNotNullOfOrNull(::refused)
        key in holderKeys && value is JsonObject -> heldUrls(key, value).firstNotNullOfOrNull(::refused)
        else -> null
    }

// upstream reads `data` as a URL everywhere but in Anthropic's base64 or text `source`
private fun heldUrls(key: String, holder: JsonObject): List<JsonElement> {
    val type = (holder["type"] as? JsonPrimitive)?.contentOrNull
    val rawData = key == "source" && type in rawDataTypes
    return listOfNotNull(holder["url"], holder["data"]?.takeUnless { rawData })
}

/**
 * [value] when it's a string llama-server would fetch or read: anything but a `data:` URL or base64. Base64 has no
 * colon, so it can't name a scheme; one starting `http` upstream fetches instead of decoding, so it's refused too.
 * Numbers, booleans and nulls aren't media llama-server reads.
 */
private fun refused(value: JsonElement): String? {
    val media = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val base64 = ':' !in media && !media.startsWith("http")
    return media.takeUnless { base64 || it.startsWith("data:", ignoreCase = true) }
}
