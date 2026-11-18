package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.Format
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The model in memory now, as the person sees it before unloading it (D59): "Qwen3.5 4B", "2.8 GB · NPU · 16k context".
 * The size is the model's weights; its context and caches come on top. The context is the size the SDK picked.
 */
data class InMemory(val name: String, val detail: String)

/**
 * A token count in whole thousands of 1,024, rounded down so it never claims more than there is: "15k" for 15 872.
 * Settings › Memory's sizes and lines read it.
 */
internal fun kTokens(tokens: Int): String = "${tokens / TOKENS_PER_K}k"

/** The context the SDK picked, as the In memory now row reads it: "16k context". */
internal fun contextLabel(tokens: Int): String = "${kTokens(tokens)} context"

private const val TOKENS_PER_K = 1_024

/**
 * The loaded model [HostState] holds: the one that is ready, or the one a load waits to replace (it stays in memory
 * until its reply ends); null when idle, failed or waking with nothing loaded.
 */
fun HostState.loaded(): HostState.Ready? = this as? HostState.Ready ?: (this as? HostState.Waking)?.outgoing

/** What [HostState] holds in memory: a loaded model, or nothing (idle, waking with nothing loaded, failed). */
fun HostState.inMemory(): InMemory? =
    loaded()?.let { ready ->
        InMemory(
            Catalog.nameOf(ready.key),
            listOf(
                Format.sizeInGb(ready.model.info.sizeBytes),
                ready.model.loadReport.device.label,
                contextLabel(ready.model.loadReport.contextSize),
            ).joinToString(" · "),
        )
    }

/** What the person sees and controls of the model's memory: what is loaded, whether a reply holds it, unloading it. */
class ModelMemory(private val host: ModelHost, private val engine: ChatEngine) {
    val inMemory: Flow<InMemory?> = host.state.map { it.inMemory() }

    /** A reply is under way somewhere: unloading waits for it rather than cutting it off. */
    val replying: StateFlow<Boolean> get() = engine.replying

    /** The model in memory with the config (chip choice, D72, and context length) it was loaded for, or null. */
    val ready: Flow<HostState.Ready?> = host.state.map { it.loaded() }

    /** Frees the model now unless a reply holds it; the next send loads it again. True when it was freed. */
    suspend fun unload(): Boolean = engine.releaseModelIfIdle()

    /**
     * The chip choice (D72) or the context length changed: a model loaded under another config than the one chosen now
     * ([chosen], read once no reply holds the model, since the choice may have changed again meanwhile) is freed, and
     * the next send loads it under that config. A reply is never cut off. Should this wait be cancelled, a send that
     * runs alone still reloads it: only a send beside another reply shares a model loaded under another config.
     */
    suspend fun loadConfigChanged(chosen: suspend () -> LoadConfig) {
        engine.replying.first { !it }
        val ready = host.state.value as? HostState.Ready ?: return
        if (!ready.config.serves(chosen())) engine.releaseModelIfIdle()
    }
}
