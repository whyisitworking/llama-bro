package com.suhel.llamabro.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import java.util.concurrent.locks.LockSupport

/**
 * The screens' state is derived with `stateIn(WhileSubscribed)`, so it only runs while something collects, as in the
 * app. A test holds a collector the way a screen would, then reads `.value`.
 */
fun <T> StateFlow<T>.keepAlive(scope: CoroutineScope): StateFlow<T> {
    scope.launch { collect { } }
    return this
}

/**
 * Ends [viewModels]' scopes, then resets Main. A cancelled coroutine winding down on an IO thread resumes onto Main,
 * which for a standard [main] queues on its scheduler that nothing runs once the test has returned; so this runs that
 * queue until every scope has ended, and only then resets Main. Main is never swapped meanwhile: setting it while an IO
 * thread dispatches onto it fails ("Dispatchers.Main is used concurrently with setting it"), and resetting it while a
 * scope still winds down leaves that scope's last resume with no Main, failing whichever test runs next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun shutDown(main: TestDispatcher, viewModels: Collection<ViewModel>) {
    val jobs = viewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
    jobs.forEach { it.cancel() }
    val deadline = System.nanoTime() + SHUT_DOWN_NANOS
    while (jobs.any { !it.isCompleted }) {
        main.scheduler.runCurrent()
        check(System.nanoTime() < deadline) { "a view model's scope was still running 5 s after its cancel" }
        LockSupport.parkNanos(POLL_NANOS)
    }
    main.scheduler.runCurrent()
    Dispatchers.resetMain()
}

private const val SHUT_DOWN_NANOS = 5_000_000_000L
private const val POLL_NANOS = 1_000_000L
