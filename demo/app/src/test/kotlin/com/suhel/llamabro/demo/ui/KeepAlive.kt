package com.suhel.llamabro.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * Ends [viewModels]' scopes, then resets Main. Main first becomes an unconfined dispatcher on [main]'s scheduler, so a
 * cancelled coroutine's resume, now or later from an IO thread, finishes in place; whatever [main] had already queued
 * runs; and Main is reset only once every scope has ended. A scope still winding down when `resetMain` runs resumes
 * onto no Main at all, and that exception fails whichever test runs next.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun shutDown(main: TestDispatcher, viewModels: Collection<ViewModel>) {
    Dispatchers.setMain(UnconfinedTestDispatcher(main.scheduler))
    val jobs = viewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
    jobs.forEach { it.cancel() }
    main.scheduler.runCurrent()
    runBlocking { withTimeout(5_000) { jobs.joinAll() } }
    Dispatchers.resetMain()
}
