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
        HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN -> {
            HubException.Unauthorized(status, unauthorized)
        }

        HttpStatus.NOT_FOUND -> {
            HubException.NotFound(notFound, emptyList())
        }

        HttpStatus.TOO_MANY_REQUESTS -> {
            HubException.RateLimited(
                response.headers["retry-after"]?.firstOrNull()?.toLongOrNull(),
            )
        }

        else -> {
            HubException.Network(other, null, status >= HttpStatus.SERVER_ERROR || status in alsoRetryable)
        }
    }
