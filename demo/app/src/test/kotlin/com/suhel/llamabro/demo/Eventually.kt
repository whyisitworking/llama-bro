package com.suhel.llamabro.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits until [condition] holds, polling every 10 ms in the caller's context, and fails naming [what] after
 * [timeoutMillis]. The timeout is generous on purpose: it bounds a hang, it is not a latency check. Under `runTest` the
 * poll's delay is virtual, so wrap the call in `withContext(Dispatchers.Default)` to wait in real time.
 */
suspend fun eventually(what: String, timeoutMillis: Long = 10_000, condition: suspend () -> Boolean) {
    withTimeoutOrNull(timeoutMillis) { while (!condition()) delay(10) }
        ?: throw AssertionError("timed out after $timeoutMillis ms waiting for: $what")
}

/**
 * The grace a "did not happen" check gives after its sync point: the wrong action, if the code took it, would run on
 * another coroutine a moment later, so asserting at once could pass on broken code. Call it only once [eventually] has
 * reached the point where the wrong action would start; it never stands in for that wait.
 */
suspend fun letAWrongActionLand() = delay(200)
