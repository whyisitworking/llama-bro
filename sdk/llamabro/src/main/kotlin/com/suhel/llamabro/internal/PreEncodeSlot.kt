package com.suhel.llamabro.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * The model's one background pre-encode, like tools/ui's store-wide preEncodeAbortController: a new one replaces the
 * old, and any chat request cancels it ([cancel]), whichever conversation started it. The model holds it so that a
 * pre-encode one conversation started never delays another's reply (a single slot serves both), and so closing the
 * model ends it ([close]).
 */
internal class PreEncodeSlot(private val scope: CoroutineScope) {
    private val lock = Any()
    private var job: Job? = null

    /** Starts [block] in the background, cancelling the pre-encode running now. */
    fun start(block: suspend CoroutineScope.() -> Unit) {
        synchronized(lock) {
            job?.cancel()
            job = scope.launch(block = block)
        }
    }

    /** Cancels the pre-encode running now and waits for it to end. */
    suspend fun cancel() {
        val running = synchronized(lock) { job.also { job = null } }
        running?.cancelAndJoin()
    }

    /** Ends the slot for good: the running pre-encode is cancelled, and a later [start] runs nothing. */
    fun close() {
        scope.cancel()
    }
}
