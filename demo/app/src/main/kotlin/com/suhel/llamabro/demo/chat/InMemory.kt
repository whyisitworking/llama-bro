package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.Format
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * The model in memory now, as the person sees it before unloading it (D59): "Qwen3.5 4B", "2.8 GB · NPU". The size is
 * the model's weights; its context and caches come on top.
 */
data class InMemory(val name: String, val detail: String)

/** What [HostState] holds in memory: a loaded model, or nothing (idle, waking, failed). */
fun HostState.inMemory(): InMemory? =
    (this as? HostState.Ready)?.let { ready ->
        InMemory(
            Catalog.nameOf(ready.key),
            "${Format.sizeInGb(ready.model.info.sizeBytes)} · ${ready.model.loadReport.device.name.uppercase()}",
        )
    }

/** What the person sees and controls of the model's memory: what is loaded, whether a reply holds it, unloading it. */
class ModelMemory(private val host: ModelHost, private val engine: ChatEngine) {
    val inMemory: Flow<InMemory?> = host.state.map { it.inMemory() }

    /** A reply is under way somewhere: unloading waits for it rather than cutting it off. */
    val replying: StateFlow<Boolean> get() = engine.replying

    /** Frees the model now unless a reply holds it; the next send loads it again. True when it was freed. */
    suspend fun unload(): Boolean = engine.releaseModelIfIdle()
}
