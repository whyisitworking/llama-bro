package com.suhel.llamabro.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The screens' state is derived with `stateIn(WhileSubscribed)`, so it only runs while something collects, as in the
 * app. A test holds a collector the way a screen would, then reads `.value`.
 */
fun <T> StateFlow<T>.keepAlive(scope: CoroutineScope): StateFlow<T> {
    scope.launch { collect { } }
    return this
}

/**
 * Cancels a view model's scope and waits for it to finish. A scope that is still winding down on another thread when
 * `Dispatchers.resetMain()` runs fails its completion handlers there, and the stray exception lands in whichever test
 * runs next.
 */
fun ViewModel.shutDown() {
    val job = viewModelScope.coroutineContext[Job] ?: return
    job.cancel()
    runBlocking { withTimeout(5_000) { job.join() } }
}
