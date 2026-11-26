package com.suhel.llamabro.demo.library

import android.util.Log
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What `LlamaBro.estimateFit` says of the embedder: whether it [fits] (null when the phone's memory can't be read),
 * the memory it needs at its smallest context, and what is free for it.
 */
data class EmbedderEstimate(val fits: Boolean?, val needBytes: Long, val budgetBytes: Long?)

/**
 * The chat model the embedder loads beside: its [name], and the memory to keep free for it, [reserveBytes]. That is 0
 * while it is in memory (the free memory the estimate reads already leaves it out) and its files' size while it is
 * not, so the embedder doesn't take the room the next chat needs.
 */
data class ChatBeside(val name: String, val reserveBytes: Long)

/** Whether the embedder fits beside the chat model. */
sealed interface EmbedderFit {
    data object Fits : EmbedderFit

    /** It needs [needBytes]; [budgetBytes] are free (null: unknown) beside [beside] (null: no chat model). */
    data class DoesNotFit(val beside: String?, val needBytes: Long, val budgetBytes: Long?) : EmbedderFit
}

/** The embedder wasn't loaded: [fit] says what it needs and what is free. */
class EmbedderDoesNotFit(val fit: EmbedderFit.DoesNotFit) :
    Exception("the embedder needs ${fit.needBytes} bytes; ${fit.budgetBytes} are free beside ${fit.beside}")

/**
 * The embedding model's own host, beside the chat's `ModelHost` (one runtime holds both, F190): the embedder is small
 * and serves no chat, so it has no part in the chat host's one-model rule or its `served` hook. It loads with
 * `ModelTask.Embedding` under the chip Settings › Processor chose ([policy], the `DevicePolicy` a chat model loads
 * under, D72), after [fit] says it fits beside the chat model; a model whose fit is unknown loads. Each [use] leases
 * it; [idleMillis] after the last lease ends it is closed, and the next use loads it again. A use under another chip
 * loads it again under that one once no use holds it.
 */
class EmbedderHost(
    private val llamaBro: LlamaBro,
    private val source: suspend (HfModel) -> ModelSource,
    private val policy: suspend () -> DevicePolicy,
    private val estimate: suspend (HfModel, DevicePolicy, reserveBytes: Long) -> EmbedderEstimate,
    private val beside: suspend () -> ChatBeside?,
    private val scope: CoroutineScope,
    private val idleMillis: Long = IDLE_MILLIS,
) {
    private class Held(val key: HfModel, val policy: DevicePolicy, val model: Model) {
        var holders = 0
    }

    private val lock = Mutex()
    private var held: Held? = null
    private var idle: Job? = null

    // the host's own source: which embedder is in memory
    private val _loaded = MutableStateFlow<HfModel?>(null)

    /** The embedder in memory, or null. */
    val loaded: StateFlow<HfModel?> = _loaded.asStateFlow()

    /** Whether [key] fits beside the chat model now; one in memory fits. */
    suspend fun fit(key: HfModel): EmbedderFit {
        if (held?.key == key) return EmbedderFit.Fits
        return fitUnder(key, policy())
    }

    /**
     * Runs [block] with [key] loaded, loading it first when it isn't (or is, under another chip, and no use holds it).
     * @throws EmbedderDoesNotFit before a load, when the estimate says it doesn't fit; nothing is loaded.
     */
    suspend fun <T> use(key: HfModel, block: suspend (Model) -> T): T {
        val lease = lock.withLock { take(key) }
        try {
            return block(lease.model)
        } finally {
            withContext(NonCancellable) { lock.withLock { end(lease) } }
        }
    }

    /** Closes the embedder now unless a use holds it. */
    suspend fun release() {
        lock.withLock {
            idle?.cancel()
            idle = null
            held?.takeIf { it.holders == 0 }?.let { close(it) }
        }
    }

    // under the lock
    private suspend fun take(key: HfModel): Held {
        idle?.cancel()
        idle = null
        val wanted = policy()
        val current = held
        val lease =
            when {
                current != null && current.key == key && (current.policy == wanted || current.holders > 0) -> {
                    current
                }

                else -> {
                    check((current?.holders ?: 0) == 0) { "another embedder is in use" }
                    current?.let { close(it) }
                    load(key, wanted)
                }
            }
        lease.holders++
        return lease
    }

    // under the lock
    private fun end(lease: Held) {
        lease.holders--
        if (lease.holders == 0 && held === lease) {
            idle =
                scope.launch {
                    delay(idleMillis)
                    lock.withLock { if (held === lease && lease.holders == 0) close(lease) }
                }
        }
    }

    private suspend fun load(key: HfModel, wanted: DevicePolicy): Held {
        val fit = fitUnder(key, wanted)
        if (fit is EmbedderFit.DoesNotFit) throw EmbedderDoesNotFit(fit)
        val model =
            llamaBro.loadModel(source(key)) {
                task = ModelTask.Embedding()
                devicePolicy = wanted
            }
        return Held(key, wanted, model).also {
            held = it
            _loaded.value = key
        }
    }

    private suspend fun close(lease: Held) {
        held = null
        _loaded.value = null
        lease.model.closeAndJoin()
    }

    private suspend fun fitUnder(key: HfModel, wanted: DevicePolicy): EmbedderFit {
        val chat = beside()
        val estimate =
            try {
                estimate(key, wanted, chat?.reserveBytes ?: 0)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // an estimate that can't be made doesn't stop the load: the load itself says whether it fits
                Log.w("EmbedderHost", "no fit estimate: ${e.javaClass.simpleName}")
                return EmbedderFit.Fits
            }
        return if (estimate.fits == false) {
            EmbedderFit.DoesNotFit(chat?.name, estimate.needBytes, estimate.budgetBytes)
        } else {
            EmbedderFit.Fits
        }
    }

    private companion object {
        /** Two minutes unused, then its memory goes back to the chat. */
        const val IDLE_MILLIS = 120_000L
    }
}
