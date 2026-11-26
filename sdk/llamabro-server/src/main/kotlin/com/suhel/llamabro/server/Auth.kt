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

    /** The running server's throttle, shared with any other route that counts failures; null when it is off. */
    var wrongKeys: WrongKeys? = null
    var onRejected: (client: String, path: String, status: Int, reason: String) -> Unit = { _, _, _, _ -> }
}

/** The 429 a throttled address gets, with `Retry-After` in whole seconds, for [wait] milliseconds more. */
internal suspend fun ApplicationCall.respondThrottled(
    wait: Long,
    onRejected: (client: String, path: String, status: Int, reason: String) -> Unit,
) {
    val seconds = (wait + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    val status = HttpStatusCode.TooManyRequests
    response.header(HttpHeaders.RetryAfter, seconds.toString())
    onRejected(request.local.remoteAddress, request.path(), status.value, "too many wrong API keys")
    respondError(status, "Too many wrong API keys; try again in $seconds s", ErrorType.RATE_LIMIT)
}

internal val ApiKeyAuth =
    createApplicationPlugin("LlamaBroApiKey", ::AuthConfig) {
        val key = pluginConfig.apiKey
        val matching = pluginConfig.keyMatching
        val wrongKeys = pluginConfig.wrongKeys
        val onRejected = pluginConfig.onRejected

        onCall { call ->
            if (key == null) return@onCall
            if (call.request.httpMethod == HttpMethod.Options || call.request.path() in openPaths) return@onCall
            val authorization = call.request.headers[HttpHeaders.Authorization]
            val xApiKey = call.request.headers["x-api-key"]
            // the key is judged first, in constant time, and the throttle then decides and records in one step, so
            // parallel guesses can't all pass its check; a throttled address gets 429 whatever the answer, so no
            // answer tells a right guess apart
            val ok = carriesKey(key, authorization, xApiKey, matching)
            val guessed = !ok && presented(authorization, xApiKey).isNotEmpty()
            val wait = wrongKeys?.check(call.request.local.remoteAddress, ok, guessed)
            when {
                wait != null -> {
                    call.respondThrottled(wait, onRejected)
                }

                !ok -> {
                    val status = HttpStatusCode.Unauthorized
                    onRejected(
                        call.request.local.remoteAddress,
                        call.request.path(),
                        status.value,
                        "invalid or missing API key",
                    )
                    call.respondError(status, "Invalid API Key", ErrorType.AUTHENTICATION)
                }
            }
        }
    }
