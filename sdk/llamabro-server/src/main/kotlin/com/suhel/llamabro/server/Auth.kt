package com.suhel.llamabro.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import java.security.MessageDigest

/** Paths that need no key, as upstream's `--api-key` leaves them (and a browser's CORS preflight can't send one). */
private val openPaths = setOf("/health", "/v1/models")

private const val BEARER = "Bearer "
private const val MILLIS_PER_SECOND = 1_000L

/** The keys a request presents: OpenAI clients send `Authorization: Bearer`, Anthropic clients `x-api-key`. */
private fun presented(authorization: String?, xApiKey: String?): List<String> =
    listOfNotNull(authorization?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER), xApiKey)

/**
 * Whether a request carries [key], as [matching] compares them. Every key a request presents must be the right one,
 * so a wrong key in one header is refused even beside the right one in the other.
 */
internal fun carriesKey(
    key: String,
    authorization: String?,
    xApiKey: String?,
    matching: KeyMatching = KeyMatching.Exact,
): Boolean {
    val presented = presented(authorization, xApiKey)
    val expected = matching.normalise(key).toByteArray()
    // no early exit: every presented key is compared, in constant time each; matching only normalises both sides
    return presented.isNotEmpty() &&
        presented.map { MessageDigest.isEqual(matching.normalise(it).toByteArray(), expected) }.all { it }
}

internal class AuthConfig {
    var apiKey: String? = null
    var keyMatching: KeyMatching = KeyMatching.Exact
    var throttle: Throttle = Throttle.Default
    var onRejected: (client: String, path: String, status: Int, reason: String) -> Unit = { _, _, _, _ -> }
}

internal val ApiKeyAuth =
    createApplicationPlugin("LlamaBroApiKey", ::AuthConfig) {
        val key = pluginConfig.apiKey
        val matching = pluginConfig.keyMatching
        val wrongKeys = pluginConfig.throttle.takeIf { it.enabled }?.let { WrongKeys(it.clock) }
        val onRejected = pluginConfig.onRejected

        suspend fun ApplicationCall.reject(status: HttpStatusCode, reason: String, message: String, type: String) {
            onRejected(request.local.remoteAddress, request.path(), status.value, reason)
            respondError(status, message, type)
        }

        onCall { call ->
            if (key == null) return@onCall
            if (call.request.httpMethod == HttpMethod.Options || call.request.path() in openPaths) return@onCall
            val client = call.request.local.remoteAddress
            // a throttled address is refused before its key is looked at, so no answer tells a right guess apart
            val wait = wrongKeys?.waitFor(client)
            if (wait != null) {
                val seconds = (wait + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
                call.response.header(HttpHeaders.RetryAfter, seconds.toString())
                call.reject(
                    HttpStatusCode.TooManyRequests,
                    "too many wrong API keys",
                    "Too many wrong API keys; try again in $seconds s",
                    ErrorType.RATE_LIMIT,
                )
                return@onCall
            }
            val authorization = call.request.headers[HttpHeaders.Authorization]
            val xApiKey = call.request.headers["x-api-key"]
            when {
                carriesKey(key, authorization, xApiKey, matching) -> {
                    wrongKeys?.right(client)
                }

                else -> {
                    // a request with no key guesses nothing, so only a wrong one counts towards the throttle
                    if (presented(authorization, xApiKey).isNotEmpty()) wrongKeys?.wrong(client)
                    call.reject(
                        HttpStatusCode.Unauthorized,
                        "invalid or missing API key",
                        "Invalid API Key",
                        ErrorType.AUTHENTICATION,
                    )
                }
            }
        }
    }
