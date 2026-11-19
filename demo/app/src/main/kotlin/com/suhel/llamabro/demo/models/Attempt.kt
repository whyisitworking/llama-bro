package com.suhel.llamabro.demo.models

import kotlinx.coroutines.CancellationException

/**
 * Runs [block]; a failure becomes null so a flow can carry on with what it already has (no tint),
 * but cancellation is never swallowed, unlike `runCatching`.
 */
internal inline fun <T> attempt(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        null
    }
