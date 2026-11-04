package com.suhel.llamabro.internal

import com.suhel.llamabro.LlamaBroException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * llama-server error responses to [LlamaBroException]. The types are llama-server's `error_type` names
 * (tools/server/server-common.cpp format_error_response); `exceed_context_size_error` carries both
 * numbers (server-context.cpp send_error).
 */
internal object ErrorMapping {
    private val json = Json { ignoreUnknownKeys = true }

    /** [body] is `{"error": {...}}` (or anything else for a non-JSON failure). */
    fun fromBody(httpStatus: Int, body: String): LlamaBroException {
        val error = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.get("error") as? JsonObject
            ?: return LlamaBroException.ServerError(httpStatus, body.ifBlank { "HTTP $httpStatus" })
        val type = error.string("type")
        val message = error.string("message") ?: body
        val status = error["code"]?.jsonPrimitive?.intOrNull ?: httpStatus
        return when (type) {
            "exceed_context_size_error" -> LlamaBroException.ContextExceeded(
                promptTokens = error["n_prompt_tokens"]?.jsonPrimitive?.intOrNull ?: -1,
                contextSize = error["n_ctx"]?.jsonPrimitive?.intOrNull ?: -1,
            )
            "invalid_request_error", "not_found_error", "authentication_error", "permission_error" ->
                LlamaBroException.InvalidRequest(message)
            "not_supported_error" -> LlamaBroException.NotSupported(message)
            "unavailable_error" -> LlamaBroException.Unavailable(message)
            else -> LlamaBroException.ServerError(status, message)
        }
    }

    /** The message of a C API error JSON (`{"error": "..."}`). */
    fun nativeMessage(errorJson: String): String =
        runCatching { (json.parseToJsonElement(errorJson) as JsonObject).string("error") }.getOrNull() ?: errorJson

    private fun JsonObject.string(key: String): String? =
        (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
