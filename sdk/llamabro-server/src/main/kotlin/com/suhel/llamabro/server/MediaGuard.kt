package com.suhel.llamabro.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Keys whose string value is a media URL, anywhere in a body: chat's `image_url` and `video_url`, Responses'
 * `input_image.image_url` and `input_file`'s `file_url` and `file_data`, rerank's `image` (a string or a list).
 */
private val urlKeys = setOf("image_url", "video_url", "audio_url", "file_url", "file_data", "image")

/**
 * Keys whose object holds a media URL in `url` or `data`: chat's `image_url`, `input_audio`, `input_video` and
 * `video_url`, and Anthropic's `source` of an `image` or `document` block.
 */
private val holderKeys = setOf("image_url", "input_audio", "input_video", "video_url", "audio_url", "source", "image")

/** Anthropic's `source` types whose `data` is the content itself, not a URL; upstream makes the `data:` URL. */
private val rawDataTypes = setOf("base64", "text")

/**
 * The first media URL in [body] that isn't a `data:` URL, or null when there is none. llama-server reads `file://`
 * media from the media folder the SDK gives it for chat attachments (server-common.cpp `handle_media`) and fetches
 * `http(s)`, so a remote client could read another chat's pictures or reach the LAN through the device. The keys are
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

/** [value] when it's a string that isn't a `data:` URL; numbers, booleans and nulls aren't URLs llama-server reads. */
private fun refused(value: JsonElement): String? {
    val url = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return url.takeUnless { it.startsWith("data:", ignoreCase = true) }
}
