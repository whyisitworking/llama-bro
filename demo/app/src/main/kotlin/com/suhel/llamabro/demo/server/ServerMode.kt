package com.suhel.llamabro.demo.server

import com.suhel.llamabro.demo.chat.HostState
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.loadConfig
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.server.Advertise
import com.suhel.llamabro.server.Cors
import com.suhel.llamabro.server.LlamaBroServer
import com.suhel.llamabro.server.ServerConfig
import com.suhel.llamabro.server.ServerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where server mode is (docs/design/server.md §3). */
sealed interface ServingState {
    data object Off : ServingState

    /** The model is loading: [progress] is ModelHost's, 0 to 1, once the load reports any. */
    data class Starting(val progress: Float? = null) : ServingState

    /** Answering on [port] (every network the phone is on) with [model], loaded with [slots] requests at once. */
    data class Serving(val model: HfModel, val port: Int, val slots: Int) : ServingState

    /** The model didn't load, or no port could be bound. */
    data class Failed(val reason: String) : ServingState
}

/**
 * Server mode: serves one model to the computers on the Wi-Fi with `llamabro-server`, on [port] (any free one when it
 * is taken), always behind the [keys]' key, with CORS open so a laptop's browser (`npx llama-chat`) can call it, and
 * announced through [advertise]. From [start] to [stop] it holds a [ModelHost] lease on the model, so the model stays
 * loaded; a chat on the same model shares it, and a chat that would switch to another asks first ([blocksSwitch]).
 * Calls are taken one at a time; [stop] cancels a start still loading.
 */
class ServerMode(
    private val host: ModelHost,
    private val settings: SettingsRepository,
    private val keys: ServerKey,
    private val scope: CoroutineScope,
    private val port: Int = DEFAULT_PORT,
    private val advertise: Advertise? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ServingState>(ServingState.Off)
    val state: StateFlow<ServingState> = _state.asStateFlow()

    private val _served = MutableStateFlow<HfModel?>(null)

    /** The model being served, from the start of its load to [stop] or a failure; null when off. */
    val served: StateFlow<HfModel?> = _served.asStateFlow()

    private val book = ServerLog(clock)

    /** The requests served and refused, newest first, at most 200. */
    val log: StateFlow<List<LogLine>> = book.lines

    /** The requests being answered now, of the slots the model was loaded with. */
    val busy: StateFlow<Int> = book.busy

    /** start, stop and renew, one at a time. */
    private val control = Mutex()
    private var starting: Deferred<Unit>? = null
    private var session: Session? = null

    /** [models] answers every request from [lease]'s model, through each server [running] in turn (a renew rebinds). */
    private class Session(
        val lease: ModelHost.Lease,
        val model: HfModel,
        val models: HostServedModels,
        var running: Running,
    )

    private class Running(val server: LlamaBroServer, val log: Job, val port: Int)

    fun isServing(model: HfModel): Boolean = _served.value == model

    /** Whether a chat choosing [to] must first ask to stop serving: another model than the one served. */
    fun blocksSwitch(to: HfModel): Boolean = _served.value.let { it != null && it != to }

    /**
     * Loads [model] with [slots] requests at once (1, 2 or 4) on the chip and context chosen in Settings › Memory,
     * and serves it; returns once it serves or failed ([state]). A server already on stops first.
     */
    suspend fun start(model: HfModel, slots: Int) {
        require(slots in SLOTS) { "slots $slots is not one of $SLOTS" }
        val work =
            control.withLock {
                shutDown()
                _state.value = ServingState.Starting()
                _served.value = model
                scope.async(start = CoroutineStart.UNDISPATCHED) { open(model, slots) }.also { starting = it }
            }
        work.join()
    }

    /** Stops answering (requests in flight are cancelled) and releases the model; cancels a start still loading. */
    suspend fun stop() {
        control.withLock { shutDown() }
    }

    /**
     * A new key ([ServerKey.renew]); a running server restarts with it, which drops the clients connected with the old
     * one. Returns the new key.
     */
    suspend fun renewKey(): String =
        control.withLock {
            starting?.join()
            val key = keys.renew()
            val current = session ?: return@withLock key
            withContext(NonCancellable) { current.running.close() }
            val running = bind(current.model, current.lease.config.slots, current.models, key)
            if (running == null) {
                withContext(NonCancellable) { current.models.drained() }
                current.lease.release()
                session = null
                _served.value = null
            } else {
                current.running = running
            }
            key
        }

    private suspend fun shutDown() {
        starting?.cancelAndJoin()
        starting = null
        session?.let { current ->
            withContext(NonCancellable) {
                current.running.close()
                // the stop cancelled the requests in flight; the model stays leased until each has let go of it
                current.models.drained()
            }
            current.lease.release()
        }
        session = null
        _served.value = null
        _state.value = ServingState.Off
    }

    private suspend fun open(model: HfModel, slots: Int) {
        val config =
            settings.settings
                .first()
                .loadConfig
                .copy(slots = slots)
        // the progress runs beside the hold, not around it: nothing suspends between the hold returning its lease and
        // the try below that keeps or releases it, so a stop landing then can't drop the lease and pin the model
        val progress = scope.launch(start = CoroutineStart.UNDISPATCHED) { showLoadProgress(model) }
        val lease =
            try {
                host.hold(model, config)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                fail(e.message ?: e.javaClass.simpleName)
                return
            } finally {
                progress.cancel()
            }
        try {
            val models = HostServedModels(lease.model, model, clock() / MILLIS_PER_SECOND)
            val running = bind(model, lease.config.slots, models, keys.current())
            if (running == null) {
                lease.release()
            } else {
                session = Session(lease, model, models, running)
            }
        } catch (e: CancellationException) {
            lease.release()
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // the key's store failed: not serving, and the state says so rather than staying Starting
            lease.release()
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** ModelHost's load progress for [model], as [ServingState.Starting]'s, until cancelled. */
    private suspend fun showLoadProgress(model: HfModel) {
        host.state.collect { hosted ->
            if (hosted is HostState.Waking && hosted.key == model) {
                _state.update { if (it is ServingState.Starting) ServingState.Starting(hosted.progress) else it }
            }
        }
    }

    /**
     * A server for [models] on [port], or on any free one when that fails, with its events going to the log; null,
     * with [state] failed, when neither binds.
     */
    private suspend fun bind(model: HfModel, slots: Int, models: HostServedModels, key: String): Running? {
        var reason = "no port"
        for (candidate in listOf(port, 0).distinct()) {
            val server = LlamaBroServer(serverConfig(candidate, key), models)
            // subscribed before the server starts, so its first request is not missed
            val log = scope.launch(start = CoroutineStart.UNDISPATCHED) { server.events.collect(book::record) }
            try {
                server.start()
            } catch (e: CancellationException) {
                log.cancel()
                throw e
            }
            when (val bound = server.state.value) {
                is ServerState.Serving -> {
                    _state.value = ServingState.Serving(model, bound.port, slots)
                    return Running(server, log, bound.port)
                }

                is ServerState.Failed -> {
                    reason = bound.reason.message ?: bound.reason.javaClass.simpleName
                }

                else -> {
                    Unit
                }
            }
            log.cancel()
        }
        fail(reason)
        return null
    }

    private fun serverConfig(port: Int, key: String) =
        ServerConfig {
            host = ANY_ADDRESS
            this.port = port
            apiKey = key
            cors = Cors.Any
            advertise = this@ServerMode.advertise
        }

    private fun fail(reason: String) {
        _served.value = null
        _state.value = ServingState.Failed(reason)
    }

    private suspend fun Running.close() {
        server.stop()
        log.cancel()
        book.forgetOpen()
    }

    companion object {
        /** llama-server's own port. */
        const val DEFAULT_PORT = 8080
        val SLOTS = setOf(1, 2, 4)
        private const val ANY_ADDRESS = "0.0.0.0"
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
