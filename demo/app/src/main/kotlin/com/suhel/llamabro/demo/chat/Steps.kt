package com.suhel.llamabro.demo.chat

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The one shape of the audio features (voice mode, dictation, reading aloud): an actor whose state is touched only on
 * [dispatcher], one thread at a time of the one it is given, so it needs no locks. Each public call is a [run]: a step
 * launched onto it that begins once the step started before it has ended, so a step that suspends (a model loading, a
 * player stopping) still finishes before the next one begins. Work that goes on between steps (a reading, a player, a
 * session's events) is launched onto [dispatcher] too and reaches the state only there; blocking audio I/O runs on an
 * IO dispatcher and hands its results back.
 *
 * Steps are ordered by when they start, which is the order they were asked in for callers on one thread: a
 * `limitedParallelism(1)` view runs its tasks first in, first out. `limitedParallelism(1)` of
 * `Dispatchers.Main.immediate` is `Main.immediate` itself, so there a step asked on the main thread runs inline in its
 * caller until it first suspends; it has taken its place in the order before then, so the order holds.
 */
internal class Steps(private val scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
    val dispatcher: CoroutineDispatcher = dispatcher.limitedParallelism(1)

    // the step started last; read and replaced only on [dispatcher], before the step first suspends
    private var last: Job? = null

    /** Runs [step] after the step started before it; join the job to wait for it. */
    fun run(step: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(dispatcher) {
            val before = last
            last = coroutineContext.job
            before?.join()
            step()
        }
}
