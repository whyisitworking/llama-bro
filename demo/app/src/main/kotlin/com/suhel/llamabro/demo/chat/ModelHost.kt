package com.suhel.llamabro.demo.chat

import android.util.Log
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.LoadProgress
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

sealed interface HostState {
    data object Idle : HostState

    /**
     * [progress] is the SDK's load progress, 0 to 1, once it has reported any; null before. [outgoing] is the model
     * this load replaces while it is still in memory: the load waits for its reply to end before closing it.
     */
    data class Waking(val key: HfModel, val progress: Float? = null, val outgoing: Ready? = null) : HostState

    /**
     * [config] is what it was loaded under: the chip choice (D72) and the context length. Where it runs and the context
     * picked are in its `loadReport`.
     */
    data class Ready(val key: HfModel, val model: Model, val config: LoadConfig) : HostState

    /**
     * [error] is a `LlamaBroException` (a refused load, with its reason), a `HubException` from the hub's source, or
     * `NotOnPhone` for a model deleted since it was chosen.
     */
    data class Failed(val key: HfModel, val error: Exception) : HostState
}

/**
 * What Settings › Memory says a model loads under: the chip choice's [policy] (D72) and the context length's [context]
 * (a ceiling the SDK fits to memory), and the requests it serves at once, [slots]: two while voice mode captions what
 * the person says (D99, D100), one otherwise. A model loaded under another config is not reused, except that one loaded
 * with more slots serves a turn that needs fewer ([serves]).
 */
data class LoadConfig(
    val policy: DevicePolicy = DevicePolicy.Auto,
    val context: ContextSize = ContextSize.Auto,
    val slots: Int = 1,
) {
    /** A model loaded under this config can serve a turn that [wanted]: the same chip and context, enough slots. */
    fun serves(wanted: LoadConfig): Boolean =
        policy == wanted.policy && context == wanted.context && slots >= wanted.slots
}

/**
 * Owns the one loaded model. `hold` of another key, or of the same key under another [LoadConfig] (D72), closes the
 * current model before the next one loads, so two are never in memory together and a model loaded for an old choice
 * is not reused; concurrent `hold` of one key share one load. Each `hold` is a [Lease] its turn releases when it ends,
 * and a model is closed only once its leases are all released: a load that replaces it waits for them (the wake line
 * shows meanwhile), so a model is never closed under a reply streaming from it. A turn that runs beside another passes
 * `keepConfig`, so it shares the load in use whatever its config rather than waiting. Every load waits for the one
 * before it to close, whether it was replaced or released, so a [release] that waits for a reply still keeps the next
 * load out of memory until that reply ends. The load sets the config's `DevicePolicy` and `ContextSize` and leaves the
 * rest to the SDK: `Speculative` is not set (demo design §3). [source] names a model's files: the app passes the hub's
 * installed copy (`hub.model`, D64, which never downloads here); tests pass their own.
 */
class ModelHost(
    private val llamaBro: LlamaBro,
    private val source: suspend (HfModel) -> ModelSource,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val lock = Mutex()

    @Volatile private var current: Load? = null

    /**
     * The last load started, `current` or not: the next load waits for it to close, and through it for every load
     * before (each one's `closed` waits for its predecessor's).
     */
    private var newest: Load? = null

    private inner class Load(
        val key: HfModel,
        val config: LoadConfig,
        @Volatile var previous: Load?,
    ) {
        @Volatile var model: Model? = null

        @Volatile var failed = false

        /** The leases on this load not yet released; taken under the host's lock, and only while it is `current`. */
        val holders = MutableStateFlow(0)

        /**
         * Whether this load is [key] under [config] (or under any config, with [anyConfig]) and didn't fail: `hold`
         * then shares it.
         */
        fun serves(key: HfModel, config: LoadConfig, anyConfig: Boolean): Boolean =
            this.key == key && (anyConfig || this.config.serves(config)) && !failed

        val result: Deferred<Result<Model>> = scope.async(start = CoroutineStart.LAZY) { run() }

        /**
         * Closes this load's model once its load has ended and its leases are released; started by the first `retire`,
         * so a load that replaces it and a [release] close it once between them. Not a child of the host's scope: the
         * app's `close` cancels that scope first and then releases the model, which must still close. Started only once
         * this load is no longer `current`, and complete only once every load before it is closed too.
         */
        val closed: Deferred<Unit> =
            scope.async(NonCancellable, start = CoroutineStart.LAZY) {
                try {
                    result.join()
                    holders.first { it == 0 }
                    model?.let { open ->
                        try {
                            open.closeAndJoin()
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Exception,
                        ) {
                            // a close that fails must not block every later load; the SDK has let go of what it could
                            Log.w("ModelHost", "close failed: ${e.javaClass.simpleName}")
                        }
                        _state.update { s ->
                            if (s is HostState.Waking && s.outgoing?.model === open) s.copy(outgoing = null) else s
                        }
                    }
                } finally {
                    previous?.closed?.await()
                    previous = null // the chain of closed loads is not kept
                }
            }

        /** The first load from this one back (each waits for the one before) that is [key] and not yet closed. */
        fun closingFor(key: HfModel): Load? =
            generateSequence(this) { it.previous }
                .takeWhile { !it.closed.isCompleted }
                .firstOrNull { it.key == key }

        /** This load as the model in memory, for the wake line's [HostState.Waking.outgoing]; null once it closes. */
        fun asReady(): HostState.Ready? =
            model?.takeIf { !closed.isCompleted }?.let { HostState.Ready(key, it, config) }

        private suspend fun run(): Result<Model> {
            previous?.let { retire(it) }
            // replaced while it waited, and no turn holds it (none can now): loading it would only close it again
            if (current !== this && holders.value == 0) {
                failed = true
                return Result.failure(CancellationException("replaced before it loaded"))
            }
            return try {
                val loaded =
                    llamaBro.loadModel(source(key)) {
                        devicePolicy = config.policy
                        contextSize = config.context
                        parallelSlots = config.slots
                        // the load bar under the top bar, from the load's own phase: an installed copy is not fetched
                        // (D64); a value that arrives after the load ended changes nothing
                        onProgress { progress ->
                            if (current === this@Load && progress is LoadProgress.Loading) {
                                _state.update { s ->
                                    if (s is HostState.Waking &&
                                        s.key == key
                                    ) {
                                        s.copy(progress = progress.fraction)
                                    } else {
                                        s
                                    }
                                }
                            }
                        }
                    }
                model = loaded
                if (current === this) _state.value = HostState.Ready(key, loaded, config)
                Result.success(loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                failed = true
                if (current === this) _state.value = HostState.Failed(key, e)
                Result.failure(e)
            }
        }
    }

    /**
     * A model in use, and the config it was loaded under. It stays open until [release], which the turn that holds it
     * calls when it ends; a second release does nothing.
     */
    class Lease internal constructor(
        val model: Model,
        val config: LoadConfig,
        private val holders: MutableStateFlow<Int>,
    ) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) holders.update { it - 1 }
        }
    }

    /** [hold]'s model alone, its lease released at once: the model may be closed by the next `hold` of another key. */
    suspend fun use(key: HfModel, config: LoadConfig, keepConfig: () -> Boolean = { false }): Model =
        hold(key, config, keepConfig).also { it.release() }.model

    /**
     * The model for [key] under [config], loading it (and closing the one in use) when needed. When [keepConfig] says
     * so, a load of [key] under another config is shared rather than replaced: another turn may be streaming from it.
     * It is asked under the host's lock, so it sees every turn that started before. A load that replaces one still
     * leased waits for its leases before closing it. The caller releases the lease when its turn ends; on a throw (what
     * the load threw, or a cancel while waiting) there is no lease to release.
     */
    suspend fun hold(key: HfModel, config: LoadConfig, keepConfig: () -> Boolean = { false }): Lease {
        val load =
            lock.withLock {
                val existing = current
                val chosen =
                    if (existing != null && existing.serves(key, config, keepConfig())) {
                        existing
                    } else {
                        val previous = newest
                        val outgoing = previous?.asReady() ?: (_state.value as? HostState.Waking)?.outgoing
                        _state.value = HostState.Waking(key, outgoing = outgoing)
                        startLoad(key, config, previous)
                    }
                chosen.also { it.holders.update { n -> n + 1 } }
            }
        var leased = false
        try {
            val lease = Lease(load.result.await().getOrThrow(), load.config, load.holders)
            leased = true
            return lease
        } finally {
            if (!leased) load.holders.update { it - 1 }
        }
    }

    /**
     * Closes the model in use, if any, and waits until its memory is free, along with any model still closing; false,
     * closing nothing, when [busy] says a turn holds it. [busy] is asked under the host's lock, as `hold`'s
     * `keepConfig` is. A model still leased is closed once its leases are released, and this waits for that too.
     */
    suspend fun release(busy: () -> Boolean = { false }): Boolean {
        val last =
            lock.withLock {
                if (busy()) return false
                current = null
                _state.value = HostState.Idle
                newest
            } ?: return true
        retire(last)
        return true
    }

    /**
     * Makes sure [key] is not in memory and waits until it is closed: the model in use is released as [release] does;
     * a model a load waits to replace, or one still closing, is left to close when its reply ends, and the load in use
     * goes on. For deleting a model's files: this returns only once no reply streams from it.
     */
    suspend fun close(key: HfModel) {
        val target =
            lock.withLock {
                val existing = current
                if (existing?.key == key) {
                    current = null
                    _state.value = HostState.Idle
                    newest
                } else {
                    (if (existing != null) existing.previous else newest)?.closingFor(key)
                }
            } ?: return
        retire(target)
    }

    private fun startLoad(key: HfModel, config: LoadConfig, previous: Load?): Load {
        val load = Load(key, config, previous)
        current = load
        newest = load
        load.result.start() // after `current` is set: the load publishes its state only while it is `current`
        return load
    }

    private suspend fun retire(load: Load) = load.closed.await()
}
