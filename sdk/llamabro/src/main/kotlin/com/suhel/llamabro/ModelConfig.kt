package com.suhel.llamabro

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Settings fixed when a model loads: the scope of `loadModel(source) { … }`. Defaults are the phone defaults
 * (design §8, D17).
 */
public class ModelConfig internal constructor() {
    /** Tokens of context (`n_ctx`). */
    public var contextSize: ContextSize = ContextSize.Auto
    /** P2 adds `Auto` with per-backend data. */
    public var speculative: Speculative = Speculative.Off
    /** null = llama.cpp's default. */
    public var threads: Int? = null
    /** Logical batch for prompt processing (`-b`). */
    public var batchSize: Int = 512
    /** Physical batch (`-ub`); bigger didn't help on the phone (F10). */
    public var microBatchSize: Int = 512
    /** Requests the model serves at once (`-np`); each slot has its own cache. One fits a chat app. */
    public var parallelSlots: Int = 1
    /** RAM for llama-server's prompt cache across conversations (`--cache-ram`), in MiB. */
    public var ramCacheMib: Int = 1024
    /** Context checkpoints kept per slot (`--ctx-checkpoints`), which let hybrid models reuse a prefix (§7). */
    public var checkpointsPerSlot: Int = 8

    internal var progressListener: ((Float) -> Unit)? = null

    /**
     * Called with llama.cpp's load progress, 0 to 1, on the context `loadModel` was called from. Values may be
     * skipped if the listener is slow; the last one always arrives.
     */
    public fun onProgress(listener: (fraction: Float) -> Unit) {
        progressListener = listener
    }

    /** The `lb_model_load` config (bridge/src/config.cpp). */
    internal fun toJson(source: ModelSource, slotSavePath: String): String = buildJsonObject {
        when (source) {
            is FileSource -> {
                put("model_path", source.path)
                source.mmproj?.let { put("mmproj_path", it) }
            }
        }
        when (val c = contextSize) {
            ContextSize.Auto -> put("n_ctx", "auto")
            is ContextSize.Fixed -> put("n_ctx", c.tokens)
        }
        threads?.let { put("n_threads", it) }
        put("n_batch", batchSize)
        put("n_ubatch", microBatchSize)
        put("n_parallel", parallelSlots)
        put("cache_ram_mib", ramCacheMib)
        put("n_ctx_checkpoints", checkpointsPerSlot)
        put("slot_save_path", slotSavePath)
        when (val s = speculative) {
            Speculative.Off -> Unit
            is Speculative.Mtp -> put("speculative", buildJsonObject { put("type", "mtp"); put("n_max", s.draftMax) })
        }
    }.toString()
}

/** How big the context is ([ModelConfig.contextSize]). */
public sealed interface ContextSize {
    /** min(8192, the model's training context) in P1; P2 fits it to free memory (design §8). */
    public data object Auto : ContextSize
    /** Exactly [tokens]. */
    public data class Fixed(public val tokens: Int) : ContextSize
}

/** Speculative decoding ([ModelConfig.speculative]). */
public sealed interface Speculative {
    /** None (the default). */
    public data object Off : Speculative
    /** The GGUF's multi-token-prediction head (`--spec-type draft-mtp`). */
    public data class Mtp(public val draftMax: Int = 3) : Speculative
}
