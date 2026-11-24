package com.suhel.llamabro.hub.internal

/** The HTTP statuses the hub tells apart, in one place. */
internal object HttpStatus {
    const val OK: Int = 200
    const val PARTIAL_CONTENT: Int = 206

    /** The first status of an error (4xx and 5xx). */
    const val ERROR_FROM: Int = 400
    const val UNAUTHORIZED: Int = 401
    const val FORBIDDEN: Int = 403
    const val NOT_FOUND: Int = 404
    const val RANGE_NOT_SATISFIABLE: Int = 416
    const val TOO_MANY_REQUESTS: Int = 429

    /** The first status of a server failure. */
    const val SERVER_ERROR: Int = 500
}
