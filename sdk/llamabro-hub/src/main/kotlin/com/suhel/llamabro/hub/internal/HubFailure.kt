package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubException

/**
 * What an HTTP error from the Hub means, the same for every request the hub makes: a token problem, a missing
 * repository or file, a rate limit (with the Hub's `Retry-After`), or a network failure that is worth retrying when the
 * server failed (5xx) or the status is one of [alsoRetryable]. [unauthorized], [notFound] and [other] word each case
 * for what was asked.
 */
internal fun hubFailure(
    response: Response,
    unauthorized: String,
    notFound: String,
    other: String,
    alsoRetryable: Set<Int> = emptySet(),
): HubException =
    when (val status = response.status) {
        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> {
            HubException.Unauthorized(status, unauthorized)
        }

        HTTP_NOT_FOUND -> {
            HubException.NotFound(notFound, emptyList())
        }

        HTTP_TOO_MANY_REQUESTS -> {
            HubException.RateLimited(
                response.headers["retry-after"]?.firstOrNull()?.toLongOrNull(),
            )
        }

        else -> {
            HubException.Network(other, null, status >= HTTP_SERVER_ERROR || status in alsoRetryable)
        }
    }

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
