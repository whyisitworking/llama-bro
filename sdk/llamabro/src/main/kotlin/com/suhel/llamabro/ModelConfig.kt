package com.suhel.llamabro

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Settings fixed when a model loads. Defaults are the phone defaults (design §8, D17). */
public class ModelConfig private constructor() {
    public class Builder internal constructor() {
        public var contextSize: ContextSize = ContextSize.Auto
        /** P2 adds `Auto` with per-backend data. */
        public var speculative: Speculative = Speculative.Off
        /** null = llama.cpp's default. */
        public var threads: Int? = null
        public var batchSize: Int = 512
        public var microBatchSize: Int = 512
        public var parallelSlots: Int = 1
        public var ramCacheMib: Int = 1024
        public var checkpointsPerSlot: Int = 8

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
}

public sealed interface ContextSize {
    /** min(8192, the model's training context) in P1; P2 fits it to free memory (design §8). */
    public data object Auto : ContextSize
    public data class Fixed(public val tokens: Int) : ContextSize
}

public sealed interface Speculative {
    public data object Off : Speculative
    /** The GGUF's multi-token-prediction head (`--spec-type draft-mtp`). */
    public data class Mtp(public val draftMax: Int = 3) : Speculative
}
