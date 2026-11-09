package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.DownloadProgress
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/** Shares one active transfer for each plan key among its collectors. */
internal class Transfers(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private sealed interface Terminal {
        data object Complete : Terminal
        class Failed(val error: HubException) : Terminal
    }

    private class Snapshot(
        val progress: DownloadProgress? = null,
        val terminal: Terminal? = null,
    )

    private class Entry {
        val state = MutableStateFlow(Snapshot())
        var collectors: Int = 0
        lateinit var job: Job
    }

    private val lock = Any()
    private val active = mutableMapOf<String, Entry>()

    /** Starts [run] once for [key]; each collector sees the latest progress and terminal result. */
    fun join(
        key: String,
        onIdle: () -> Unit = {},
        run: suspend (emit: suspend (DownloadProgress) -> Unit) -> Unit,
    ): Flow<DownloadProgress> = kotlinx.coroutines.flow.flow {
        val entry = synchronized(lock) {
            active[key]?.also { it.collectors++ } ?: Entry().also { created ->
                created.collectors = 1
                active[key] = created
                created.job = scope.launch {
                    fun finish(terminal: Terminal) {
                        // A finished transfer leaves the map, so a later collector starts a fresh one.
                        synchronized(lock) { if (active[key] === created) active.remove(key) }
                        created.state.value = Snapshot(created.state.value.progress, terminal)
                    }
                    try {
                        run { progress -> created.state.value = Snapshot(progress) }
                        finish(Terminal.Complete)
                    } catch (cancelled: CancellationException) {
                        // Cancelled from inside (a timeout in the app's token provider, say) while the job still runs:
                        // that is a failure every collector must see, or they all wait forever.
                        if (currentCoroutineContext().isActive) {
                            finish(Terminal.Failed(HubException.Network("download cancelled: ${cancelled.message}",
                                cancelled, retryable = true)))
                        } else {
                            synchronized(lock) { if (active[key] === created) active.remove(key) }
                        }
                        throw cancelled
                    } catch (error: HubException) {
                        finish(Terminal.Failed(error))
                    } catch (error: Exception) {
                        finish(Terminal.Failed(HubException.Network("download failed", error, retryable = true)))
                    } catch (error: Throwable) {
                        // An Error still ends every collector before it reaches the scope's handler.
                        finish(Terminal.Failed(HubException.Network("download failed", error, retryable = false)))
                        throw error
                    }
                }
            }
        }
        try {
            var last: DownloadProgress? = null
            entry.state.transformWhile { snapshot ->
                snapshot.progress?.takeIf { it !== last }?.let {
                    emit(it)
                    last = it
                }
                when (val terminal = snapshot.terminal) {
                    null -> true
                    Terminal.Complete -> false
                    is Terminal.Failed -> throw terminal.error
                }
            }.collect { emit(it) }
        } finally {
            val idle = synchronized(lock) {
                entry.collectors--
                (entry.collectors == 0).also {
                    if (it && active[key] === entry) active.remove(key)
                }
            }
            if (idle) withContext(NonCancellable) {
                entry.job.cancelAndJoin()
                synchronized(lock) {
                    if (active[key] == null) onIdle()
                }
            }
        }
    }
}
