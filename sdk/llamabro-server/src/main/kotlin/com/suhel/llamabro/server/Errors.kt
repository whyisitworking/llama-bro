package com.suhel.llamabro.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The error body OpenAI's clients parse (llama-server answers with it too): `{"error":{"code","message","type"}}`. */
internal fun errorBody(status: HttpStatusCode, message: String, type: String): String =
    buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("code", status.value)
                put("message", message)
                put("type", type)
            },
        )
    }.toString()

internal suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String, type: String) =
    respondText(errorBody(status, message, type), ContentType.Application.Json, status)

/** llama-server's names for the error types. */
internal object ErrorType {
    const val AUTHENTICATION = "authentication_error"
    const val INVALID_REQUEST = "invalid_request_error"
    const val NOT_FOUND = "not_found_error"

    /** Not llama-server's, which has no throttle (D173): Anthropic's name, which OpenAI's clients also read as 429. */
    const val RATE_LIMIT = "rate_limit_error"
    const val SERVER = "server_error"
    const val UNAVAILABLE = "unavailable_error"
}
