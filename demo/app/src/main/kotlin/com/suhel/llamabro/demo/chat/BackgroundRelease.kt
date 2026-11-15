package com.suhel.llamabro.demo.chat

import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gives the loaded model's memory back while the app is in the background (D59): a model is gigabytes, and a
 * backgrounded app holding it is the first the system kills.
 *
 * - [keepMillis] after the app leaves the screen (the person's choice in Settings, read when it leaves), or never on
 *   a timer when it is null.
 * - At once when Android says it is short of memory: [onTrimMemory] at `TRIM_MEMORY_BACKGROUND` or above, which
 *   arrives when the app is moved towards the cached list, not merely when it is hidden (F134).
 *
 * [release] answers false while a reply is under way (it is never cut off); it is asked again every [retryMillis]
 * until it lets go. The next send loads the model again. Observe the process lifecycle (`ProcessLifecycleOwner`), so
 * switching between the app's own screens never counts.
 */
class BackgroundRelease(
    private val scope: CoroutineScope,
    private val keepMillis: suspend () -> Long?,
    private val retryMillis: Long = DEFAULT_RETRY_MILLIS,
    private val release: suspend () -> Boolean,
) : DefaultLifecycleObserver {
    private var pending: Job? = null
    private var background = false

    override fun onStop(owner: LifecycleOwner) {
        background = true
        pending?.cancel()
        pending = scope.launch { keepMillis()?.let { releaseAfter(it) } }
    }

    override fun onStart(owner: LifecycleOwner) {
        background = false
        pending?.cancel()
        pending = null
    }

    /** From the application's `onTrimMemory`: under memory pressure in the background, release now. */
    fun onTrimMemory(level: Int) {
        if (!background || level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return
        pending?.cancel()
        pending = scope.launch { releaseAfter(0) }
    }

    private suspend fun releaseAfter(wait: Long) {
        delay(wait)
        while (!release()) delay(retryMillis)
    }

    private companion object {
        /** A reply under way is asked again a minute later. */
        const val DEFAULT_RETRY_MILLIS = 60_000L
    }
}
