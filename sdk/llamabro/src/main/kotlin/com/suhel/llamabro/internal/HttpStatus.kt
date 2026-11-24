package com.suhel.llamabro.internal

/** The HTTP statuses the core tells apart: llama-server's replies and the errors the SDK raises in their style. */
internal object HttpStatus {
    const val OK: Int = 200

    /** The first status of an error (4xx and 5xx). */
    const val ERROR_FROM: Int = 400

    /** What a native failure that isn't the caller's mistake is reported with. */
    const val INTERNAL_ERROR: Int = 500
}
