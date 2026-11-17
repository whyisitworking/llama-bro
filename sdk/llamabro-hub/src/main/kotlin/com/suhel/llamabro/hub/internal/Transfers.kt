package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.DownloadProgress
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Shares one active transfer for each plan key among its collectors. */
internal class Transfers(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private sealed interface Terminal {
        data object Complete : Terminal

        class Failed(val error: Throwable) : Terminal
    }

    private class Snapshot(val progress: DownloadProgress? = null, val terminal: Terminal? = null)

    private class Entry {
        val state = MutableStateFlow(Snapshot())
        var collectors: Int = 0
        lateinit var job: Job

        /**
         * Set by [cancel] before it stops the job: how the transfer ends, whatever the stop makes it throw on the way
         * (a read that fails as the connection closes is an IOException, a retryable failure).
         */
        @Volatile var stoppedWith: HubException? = null
    }

    private val lock = Any()
    private val active = mutableMapOf<String, Entry>()

    /** Starts [run] once for [key]; each collector sees the latest progress and terminal result. */
    fun join(
        key: String,
        onIdle: () -> Unit = {},
        run: suspend (emit: suspend (DownloadProgress) -> Unit) -> Unit,
    ): Flow<DownloadProgress> =
        flow {
            val entry = attach(key, run)
            try {
                emitAll(updates(entry))
            } finally {
                detach(key, entry, onIdle)
            }
        }

    /** The running transfer for [key] with one more collector, or a new one. */
    private fun attach(key: String, run: suspend (emit: suspend (DownloadProgress) -> Unit) -> Unit): Entry =
        synchronized(lock) {
            active[key]?.also { it.collectors++ } ?: Entry().also { created ->
                created.collectors = 1
                active[key] = created
                created.job = scope.launch { execute(key, created, run) }
            }
        }

    /** Runs the transfer and turns how it ended into the terminal state every collector sees. */
    private suspend fun execute(
        key: String,
        entry: Entry,
        run: suspend (emit: suspend (DownloadProgress) -> Unit) -> Unit,
    ) {
        try {
            run { progress -> entry.state.value = Snapshot(progress) }
            finish(key, entry, Terminal.Complete)
        } catch (cancelled: CancellationException) {
            // Cancelled from inside (a timeout in the app's token provider, say) while the job still runs: that is a
            // failure every collector must see, or they all wait forever.
            if (currentCoroutineContext().isActive || entry.stoppedWith != null) {
                val error =
                    HubException.Network(
                        "download cancelled: ${cancelled.message}",
                        cancelled,
                        retryable = true,
                    )
                finish(key, entry, Terminal.Failed(error))
            } else {
                forget(key, entry)
            }
            throw cancelled
        } catch (error: HubException) {
            finish(key, entry, Terminal.Failed(error))
        } catch (error: IOException) {
            finish(key, entry, Terminal.Failed(HubException.Network("download failed: ${error.message}", error, true)))
        } catch (error: Exception) {
            // A bug (a parse error, a broken invariant) reaches the collectors as it is: as a retryable network failure
            // a background worker would retry it for minutes.
            finish(key, entry, Terminal.Failed(error))
        } catch (error: Throwable) {
            // An Error still ends every collector before it reaches the scope's handler.
            finish(key, entry, Terminal.Failed(HubException.Network("download failed", error, retryable = false)))
            throw error
        }
    }

    /**
     * Stops each transfer whose key [matches] and waits until it has let go of its files, then ends its collectors with
     * [error]. A later [join] of the key starts a fresh transfer.
     */
    suspend fun cancel(error: HubException, matches: (String) -> Boolean) {
        val stopped =
            synchronized(lock) {
                val keys = active.keys.filter(matches)
                keys.map { active.remove(it)!! }
            }
        for (entry in stopped) entry.stoppedWith = error
        // Out of the map, an entry must still reach its end, or its collectors wait forever.
        withContext(NonCancellable) {
            for (entry in stopped) {
                entry.job.cancelAndJoin()
                if (entry.state.value.terminal == null) {
                    entry.state.value = Snapshot(entry.state.value.progress, Terminal.Failed(error))
                }
            }
        }
    }

    /** One collector's view: each new progress once, then the end (a failure is thrown). */
    private fun updates(entry: Entry): Flow<DownloadProgress> {
        var last: DownloadProgress? = null
        return entry.state.transformWhile { snapshot ->
            snapshot.progress?.takeIf { it !== last }?.let {
                emit(it)
                last = it
            }
            when (val terminal = snapshot.terminal) {
                null -> true
                Terminal.Complete -> false
                is Terminal.Failed -> throw terminal.error
            }
        }
    }

    /** A finished transfer leaves the map, so a later collector starts a fresh one. */
    private fun finish(key: String, entry: Entry, terminal: Terminal) {
        forget(key, entry)
        val end = entry.stoppedWith?.let { Terminal.Failed(it) } ?: terminal
        entry.state.value = Snapshot(entry.state.value.progress, end)
    }

    private fun forget(key: String, entry: Entry) {
        synchronized(lock) { if (active[key] === entry) active.remove(key) }
    }

    /** A collector leaves; the last one out stops the transfer and, once it is gone, reports idle. */
    private suspend fun detach(key: String, entry: Entry, onIdle: () -> Unit) {
        val idle =
            synchronized(lock) {
                entry.collectors--
                (entry.collectors == 0).also { if (it && active[key] === entry) active.remove(key) }
            }
        if (idle) {
            withContext(NonCancellable) {
                entry.job.cancelAndJoin()
                synchronized(lock) { if (active[key] == null) onIdle() }
            }
        }
    }
}
