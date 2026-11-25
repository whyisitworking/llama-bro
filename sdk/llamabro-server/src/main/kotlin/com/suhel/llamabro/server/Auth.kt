package com.suhel.llamabro.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import java.security.MessageDigest

/** Paths that need no key, as upstream's `--api-key` leaves them (and a browser's CORS preflight can't send one). */
private val openPaths = setOf("/health", "/v1/models")

private const val BEARER = "Bearer "

/**
 * Whether a request carries [key]. OpenAI clients send `Authorization: Bearer`, Anthropic clients `x-api-key`; every
 * key a request presents must be the right one, so a wrong key in one header is refused even beside the right one in
 * the other.
 */
internal fun carriesKey(key: String, authorization: String?, xApiKey: String?): Boolean {
    val presented = listOfNotNull(authorization?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER), xApiKey)
    val expected = key.toByteArray()
    // no early exit: every presented key is compared, in constant time each
    return presented.isNotEmpty() && presented.map { MessageDigest.isEqual(it.toByteArray(), expected) }.all { it }
}

internal class AuthConfig {
    var apiKey: String? = null
    var onRejected: (client: String, path: String, status: Int, reason: String) -> Unit = { _, _, _, _ -> }
}

internal val ApiKeyAuth =
    createApplicationPlugin("LlamaBroApiKey", ::AuthConfig) {
        val key = pluginConfig.apiKey
        onCall { call ->
            if (key == null) return@onCall
            val path = call.request.path()
            if (call.request.httpMethod == HttpMethod.Options || path in openPaths) return@onCall
            val ok =
                carriesKey(
                    key,
                    call.request.headers[HttpHeaders.Authorization],
                    call.request.headers["x-api-key"],
                )
            if (!ok) {
                val reason = "invalid or missing API key"
                pluginConfig.onRejected(
                    call.request.local.remoteAddress,
                    path,
                    HttpStatusCode.Unauthorized.value,
                    reason,
                )
                call.respondError(HttpStatusCode.Unauthorized, "Invalid API Key", ErrorType.AUTHENTICATION)
            }
        }
    }
