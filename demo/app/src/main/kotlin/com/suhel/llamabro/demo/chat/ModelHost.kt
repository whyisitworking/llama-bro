package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.demo.models.HubGateway
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface HostState {
    data object Idle : HostState

    data class Waking(val key: HfModel) : HostState

    data class Ready(val key: HfModel, val model: Model) : HostState

    /** [error] is a `LlamaBroException` (a refused load, with its reason) or whatever stopped the hub's source. */
    data class Failed(val key: HfModel, val error: Exception) : HostState
}

/**
 * Owns the one loaded model. `use` of another key closes the current model before the next one loads, so two are never
 * in memory together; concurrent `use` of one key share one load. The load takes the SDK's defaults: `Speculative`
 * and `ContextSize` are not set (pd-design 4.5).
 */
class ModelHost(private val llamaBro: LlamaBro, private val hub: HubGateway, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val lock = Mutex()

    @Volatile private var current: Load? = null

    private inner class Load(val key: HfModel, previous: Load?) {
        @Volatile var model: Model? = null

        @Volatile var failed = false
        val result: Deferred<Result<Model>> = scope.async(start = CoroutineStart.LAZY) { run(previous) }

        private suspend fun run(previous: Load?): Result<Model> {
            previous?.let { retire(it) }
            return try {
                val loaded = llamaBro.loadModel(hub.source(key))
                model = loaded
                if (current === this) _state.value = HostState.Ready(key, loaded)
                Result.success(loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                if (current === this) _state.value = HostState.Failed(key, e)
                Result.failure(e)
            }
        }
    }

    /** The model for [key], loading it (and closing the one in use) when needed. Throws what the load threw. */
    suspend fun use(key: HfModel): Model {
        val load =
            lock.withLock {
                val existing = current
                if (existing != null && existing.key == key && !existing.failed) {
                    existing
                } else {
                    _state.value = HostState.Waking(key)
                    startLoad(key, existing)
                }
            }
        return load.result.await().getOrThrow()
    }

    /** Closes the model in use, if any, and waits until its memory is free. */
    suspend fun release() {
        val load =
            lock.withLock {
                val existing = current
                current = null
                _state.value = HostState.Idle
                existing
            } ?: return
        retire(load)
    }

    private fun startLoad(key: HfModel, previous: Load?): Load {
        val load = Load(key, previous)
        current = load
        load.result.start() // after `current` is set: the load publishes its state only while it is `current`
        return load
    }

    private suspend fun retire(load: Load) {
        load.result.join()
        load.model?.closeAndJoin()
    }
}
