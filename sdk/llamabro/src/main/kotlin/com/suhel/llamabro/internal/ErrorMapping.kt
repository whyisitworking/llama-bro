package com.suhel.llamabro.internal

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.spi.NativeError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * llama-server error responses to [LlamaBroException]. The types are llama-server's `error_type` names
 * (tools/server/server-common.cpp format_error_response); `exceed_context_size_error` carries both
 * numbers (server-context.cpp send_error).
 */
internal object ErrorMapping {
    private const val HTTP_OK = 200
    private val json = Json { ignoreUnknownKeys = true }

    /** [body] is `{"error": {...}}` (or anything else for a non-JSON failure). */
    fun fromBody(httpStatus: Int, body: String): LlamaBroException {
        val error =
            runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.get("error") as? JsonObject
                ?: return LlamaBroException.ServerError(httpStatus, body.ifBlank { "HTTP $httpStatus" })
        val type = error.string("type")
        val message = error.string("message") ?: body
        val status = error["code"]?.jsonPrimitive?.intOrNull ?: httpStatus
        return when (type) {
            "exceed_context_size_error" -> {
                LlamaBroException.ContextExceeded(
                    promptTokens = error["n_prompt_tokens"]?.jsonPrimitive?.intOrNull,
                    contextSize = error["n_ctx"]?.jsonPrimitive?.intOrNull,
                    message = message,
                )
            }

            "invalid_request_error", "not_found_error", "authentication_error", "permission_error" -> {
                LlamaBroException.InvalidRequest(message, code = error.string("lb_code"))
            }

            "not_supported_error" -> {
                LlamaBroException.NotSupported(message)
            }

            "unavailable_error" -> {
                LlamaBroException.Unavailable(message)
            }

            else -> {
                inputTooLong(message) ?: undecodable(status, message) ?: LlamaBroException.ServerError(status, message)
            }
        }
    }

    /** The bridge's code for a request's attachment it couldn't read (request.cpp). */
    const val ATTACHMENT = "attachment"

    /**
     * Upstream's words, matched because upstream gives no code for these: the decoder's refusal of a picture or clip
     * (server-common.cpp, a `runtime_error`, so a `server_error`). `UpstreamMessagesTest` checks each against the
     * pinned llama.cpp source, so an upgrade that rewords one fails there.
     */
    const val UNDECODABLE = "Failed to load image or audio file"

    /** A model without the projector for a picture's or clip's kind (server-common.cpp, "image input is not …"). */
    const val NOT_SUPPORTED = " input is not supported"

    /** An embedding or rerank input longer than the batch (server-context.cpp), with its two numbers. */
    const val TOO_LARGE = "is too large to process"
    const val BATCH_SIZE = "current batch size: "

    /** Each upstream source file and the words the SDK matches in it, for `UpstreamMessagesTest`. */
    val UPSTREAM_WORDS =
        mapOf(
            "tools/server/server-common.cpp" to listOf(UNDECODABLE, NOT_SUPPORTED),
            "tools/server/server-context.cpp" to listOf(TOO_LARGE, BATCH_SIZE),
        )

    /**
     * A picture or clip llama.cpp can't decode (HEIC, WebP, AAC, …): the request's fault, though upstream reports it
     * as a server error. The [LlamaBroException.ServerError] it was is the cause.
     */
    private fun undecodable(status: Int, message: String): LlamaBroException.InvalidRequest? =
        if (message.contains(UNDECODABLE)) {
            LlamaBroException.InvalidRequest(
                "a picture or clip is in a format llama.cpp can't decode: a picture must be JPEG, PNG, BMP or GIF " +
                    "(not HEIC or WebP), a clip WAV, MP3 or FLAC; convert it first",
                LlamaBroException.ServerError(status, message),
            )
        } else {
            null
        }

    /**
     * An [LlamaBroException.InvalidRequest] that a request's media caused, found after it was sent: a file or
     * descriptor the bridge can't read or whose audio header is wrong ([ATTACHMENT], request.cpp), or one the
     * decoder refused ([UNDECODABLE]).
     */
    fun isMediaError(e: Throwable): Boolean =
        e is LlamaBroException.InvalidRequest &&
            // the whole chain: coroutines' stack-trace recovery may hand over a copy whose cause is the original
            generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSES).any {
                (it is LlamaBroException.InvalidRequest && it.code == ATTACHMENT) ||
                    (it is LlamaBroException.ServerError && it.message.orEmpty().contains(UNDECODABLE))
            }

    private const val MAX_CAUSES = 8

    /**
     * An input that can't be split (embeddings, rerank) and is longer than the batch: upstream sends it as a plain
     * `server_error` whose message alone has the numbers (server-context.cpp, "is too large to process").
     */
    private val tooLarge =
        Regex("^input \\((\\d+) tokens\\) ${Regex.escape(TOO_LARGE)}.*${Regex.escape(BATCH_SIZE)}(\\d+)")

    private fun inputTooLong(message: String): LlamaBroException.InputTooLong? {
        val (tokens, limit) = tooLarge.find(message)?.destructured ?: return null
        return LlamaBroException.InputTooLong(
            tokens.toInt(),
            limit.toInt(),
            "input ($tokens tokens) is longer than the batch ($limit tokens), which an embedding or rerank input " +
                "must fit: shorten it, or load with a larger microBatchSize and batchSize",
        )
    }

    /**
     * Media a model can't read (no projector, or one without that modality): upstream throws a `runtime_error`, which
     * reaches the app as a `server_error`, though it's the request that is wrong (oaicompat_content_load_media).
     */
    fun unsupportedMedia(e: LlamaBroException): LlamaBroException =
        if (e is LlamaBroException.ServerError && e.message?.contains(NOT_SUPPORTED) == true) {
            LlamaBroException.InvalidRequest(e.message.orEmpty(), e)
        } else {
            e
        }

    /** A failed `lb_model_load`: its error JSON carries `"code": "file_not_found"` when a file can't be opened. */
    fun loadFailed(e: NativeError): LlamaBroException.ModelLoadFailed {
        val obj = runCatching { json.parseToJsonElement(e.errorJson) as? JsonObject }.getOrNull()
        val reason =
            when (obj?.string("code")) {
                "file_not_found" -> LlamaBroException.ModelLoadFailed.Reason.FileNotFound
                "insufficient_memory" -> LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory
                else -> LlamaBroException.ModelLoadFailed.Reason.Other
            }
        return LlamaBroException.ModelLoadFailed(reason, nativeMessage(e.errorJson), e)
    }

    /**
     * Runs [read] over a successful reply and reports a reply it can't read (malformed JSON, a missing field, an
     * empty body) as [LlamaBroException.ServerError], so a call fails only with the SDK's own errors.
     */
    inline fun <T> readReply(what: String, read: () -> T): T =
        try {
            read()
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization's errors, and a bad number
            throw unreadable(what, e)
        } catch (e: NoSuchElementException) {
            // a missing field, or an empty body
            throw unreadable(what, e)
        } catch (e: ClassCastException) {
            // a field of another JSON type
            throw unreadable(what, e)
        }

    fun unreadable(what: String, e: Throwable): LlamaBroException.ServerError =
        LlamaBroException.ServerError(HTTP_OK, "llama-server sent a $what reply the SDK can't read: ${e.message}", e)

    /** The message of a C API error JSON (`{"error": "..."}`). */
    fun nativeMessage(errorJson: String): String =
        runCatching { (json.parseToJsonElement(errorJson) as JsonObject).string("error") }.getOrNull() ?: errorJson

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
