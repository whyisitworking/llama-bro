package com.suhel.llamabro.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A request body after the checks: the bytes to forward and their JSON, or why the request is turned away. */
internal sealed interface GuardedBody {
    /** [json] is null for an empty body, which llama-server answers itself. */
    class Passed(val bytes: ByteArray, val json: JsonElement?) : GuardedBody

    /** [message] is the client's, in an `invalid_request_error`; [reason] is the request log's. */
    class Refused(val status: HttpStatusCode, val message: String, val reason: String) : GuardedBody
}

internal const val MEDIA_REFUSED = "media must be a data: URL or base64; URLs are not fetched or read"

private const val NOT_JSON = "the request body is not valid JSON"

private const val LOGGED_URL_CHARS = 64

/**
 * The body, checked ([design](https://github.com/whyisitworking/llama-bro/blob/main/docs/design/server.md)): at most
 * [maxBytes], counted as it is read, so a client can't make the device hold more; JSON; and only `data:` media
 * ([firstNonDataMedia]). A request with no body ([hasBody] false) passes empty.
 */
internal suspend fun ApplicationCall.guardedBody(hasBody: Boolean, maxBytes: Long): GuardedBody {
    if (!hasBody) return GuardedBody.Passed(ByteArray(0), null)
    val bytes = receiveAtMost(maxBytes) ?: return tooLarge(maxBytes)
    if (bytes.isEmpty()) return GuardedBody.Passed(bytes, null)
    val json = parsed(bytes) ?: return GuardedBody.Refused(HttpStatusCode.BadRequest, NOT_JSON, "invalid JSON")
    val media = firstNonDataMedia(json) ?: return GuardedBody.Passed(bytes, json)
    return GuardedBody.Refused(HttpStatusCode.BadRequest, MEDIA_REFUSED, "media '${media.take(LOGGED_URL_CHARS)}'")
}

/**
 * [bytes] as JSON, or null when they aren't. kotlinx.serialization reads arrays recursively, so arrays nested a few
 * thousand deep overflow the stack: no request needs them, and they get the same 400.
 */
private fun parsed(bytes: ByteArray): JsonElement? =
    try {
        Json.parseToJsonElement(bytes.decodeToString())
    } catch (_: SerializationException) {
        null
    } catch (_: StackOverflowError) {
        null
    }

/** The body, or null once it's over [maxBytes]: a declared length over it is refused before a byte is read. */
private suspend fun ApplicationCall.receiveAtMost(maxBytes: Long): ByteArray? {
    val declared = request.contentLength()
    if (declared != null && declared > maxBytes) return null
    // one byte past the limit tells a body at the limit from one over it, without reading the rest
    val bytes = receiveChannel().readRemaining(maxBytes.coerceAtMost(Long.MAX_VALUE - 1) + 1).readByteArray()
    return bytes.takeIf { it.size <= maxBytes }
}

private fun tooLarge(maxBytes: Long) =
    GuardedBody.Refused(
        HttpStatusCode.PayloadTooLarge,
        "the request body is larger than $maxBytes bytes",
        "body over $maxBytes bytes",
    )
