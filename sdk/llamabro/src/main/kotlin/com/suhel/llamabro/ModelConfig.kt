package com.suhel.llamabro

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Settings fixed when a model loads: the scope of `loadModel(source) { … }`. Defaults are the phone defaults
 * (design §8, D17).
 */
public class ModelConfig internal constructor() {

    /** Tokens of context (`n_ctx`). */
    public var contextSize: ContextSize = ContextSize.Auto
    /** Speculative decoding: off by default ([Speculative.Auto]); the app opts in to a drafting mode. */
    public var speculative: Speculative = Speculative.Auto
    /** Which chip runs the model; [DevicePolicy.Auto] picks the fastest that loads ([Model.loadReport] says which). */
    public var devicePolicy: DevicePolicy = DevicePolicy.Auto
    /** null = llama.cpp's default. */
    public var threads: Int? = null
    /** Logical batch for prompt processing (`-b`). */
    public var batchSize: Int = 512
    /** Physical batch (`-ub`); bigger didn't help on the phone (F10). */
    public var microBatchSize: Int = 512
    /**
     * Requests the model serves at once (`-np`); each slot has its own cache. One fits a chat app. With more, the
     * context size applies per slot: [ContextSize.Auto] and [ContextSize.UpTo] fit each slot up to the ceiling, and
     * [ContextSize.Fixed] is split between them, as llama-server does.
     */
    public var parallelSlots: Int = 1
    /** RAM for llama-server's prompt cache across conversations (`--cache-ram`), in MiB. */
    public var ramCacheMib: Int = 1024
    /**
     * Memory [ContextSize.Auto] and [ContextSize.UpTo] leave free for what grows during a chat and isn't projected
     * (context checkpoints, slack), in MiB, like llama-server's `--fit-target` margin. The default, 512, was measured
     * on the OnePlus 13 (D34, F66). Lower it only when the app knows its phone: below the default, a long chat can grow
     * into memory Android needs, and its low-memory killer then ends the whole app, which no exception reports (D45).
     * [LlamaBro.estimateFit] uses the same value. At least 0.
     */
    public var memoryReserveMib: Int = 512 // MemoryBudget.RESERVE_MIB (D34, F66)
    /** Context checkpoints kept per slot (`--ctx-checkpoints`), which let hybrid models reuse a prefix (§7). */
    public var checkpointsPerSlot: Int = 8
    /** What the model is loaded for: chat and completion, embeddings, or reranking ([ModelTask]). */
    public var task: ModelTask = ModelTask.Generate
    /**
     * The context cache's number format (`-ctk`/`-ctv`, both). [KvCacheType.Q8_0] about halves the cache, so
     * [ContextSize.Auto] fits about twice the tokens; llama.cpp's default [KvCacheType.F16] is exact. The model's
     * head size must be a multiple of the type's block (32 for Q8_0), or the load fails with llama.cpp's reason.
     */
    public var kvCacheType: KvCacheType = KvCacheType.F16
    /**
     * LoRA adapters loaded with the model (`--lora`), each at its scale. Their position in the list is the `id` that a
     * request's `lora` field (`extra`) and [Endpoint.LoraAdaptersSet] use to change a scale.
     */
    public var loraAdapters: List<LoraAdapter> = emptyList()

    internal var progressListener: ((Float) -> Unit)? = null

    internal fun validateSource(source: ModelSource) {
        if (memoryReserveMib < 0) {
            throw LlamaBroException.InvalidRequest("memoryReserveMib must be at least 0, was $memoryReserveMib")
        }
        if (speculative is Speculative.DFlash && (source as? FileSource)?.draft == null) {
            throw LlamaBroException.InvalidRequest(
                "Speculative.DFlash needs ModelSource.file(path) { draft = … }")
        }
    }

    /**
     * Called with llama.cpp's load progress, 0 to 1, on the context `loadModel` was called from. Values may be
     * skipped if the listener is slow; the last one always arrives.
     */
    public fun onProgress(listener: (fraction: Float) -> Unit) {
        progressListener = listener
    }

    /** The `lb_model_load` config (bridge/src/config.cpp). */
    internal fun toJson(
        source: ModelSource,
        slotSavePath: String,
        devices: List<String> = emptyList(),
        memoryBudgetMib: Long? = null,
    ): String = buildJsonObject {
        validateSource(source)
        // always explicit (F32): [] is CPU only; one device takes every layer (upstream's `-dev X -ngl 99`)
        put("devices", JsonArray(devices.map { JsonPrimitive(it) }))
        if (devices.isNotEmpty()) put("n_gpu_layers", ALL_LAYERS)
        when (source) {
            is FileSource -> {
                put("model_path", source.path)
                source.mmproj?.let { put("mmproj_path", it) }
                if (speculative is Speculative.DFlash) source.draft?.let { put("draft_path", it) }
            }
        }
        when (val c = contextSize) {
            ContextSize.Auto, is ContextSize.UpTo -> {
                put("n_ctx", "auto")
                put("n_ctx_cap", if (c is ContextSize.UpTo) c.maxTokens else ContextSize.AUTO_CEILING)
                // without a budget (no memory figures, e.g. FakeLlamaBro) the native side uses min(ceiling, trained)
                memoryBudgetMib?.let { put("memory_budget_mib", it) }
            }
            is ContextSize.Fixed -> put("n_ctx", c.tokens)
        }
        threads?.let { put("n_threads", it) }
        put("n_batch", batchSize)
        put("n_ubatch", microBatchSize)
        put("n_parallel", parallelSlots)
        put("cache_ram_mib", ramCacheMib)
        put("n_ctx_checkpoints", checkpointsPerSlot)
        put("slot_save_path", slotSavePath)
        if (kvCacheType != KvCacheType.F16) put("kv_cache_type", kvCacheType.nativeName)
        when (val t = task) {
            ModelTask.Generate -> Unit
            is ModelTask.Embedding -> {
                put("embeddings", true)
                t.pooling.nativeName?.let { put("pooling", it) }
            }
            ModelTask.Rerank -> put("reranking", true)
        }
        if (loraAdapters.isNotEmpty()) {
            put("lora", JsonArray(loraAdapters.map { a ->
                buildJsonObject { put("path", a.path); put("scale", a.scale) }
            }))
        }
        when (val s = speculative) {
            Speculative.Off, Speculative.Auto -> Unit  // Auto is off on every chip (F67)
            is Speculative.Mtp -> put("speculative", buildJsonObject { put("type", "mtp"); put("n_max", s.draftMax) })
            is Speculative.Ngram -> put("speculative", buildJsonObject { put("type", "ngram"); s.then?.let { put("mtp_n_max", it.draftMax) } })
            is Speculative.DFlash -> put("speculative", buildJsonObject { put("type", "dflash"); put("n_max", s.draftMax) })
        }
    }.toString()
}

private const val ALL_LAYERS = 999

/**
 * How big the context is ([ModelConfig.contextSize]): the tokens the model can see at once, the conversation so far
 * plus the reply. Its cache is reserved when the model loads, so a bigger context costs memory up front (on the GPU
 * and NPU fully, on the CPU as it fills). [Model.loadReport] tells you the size picked and why.
 */
public sealed interface ContextSize {
    /**
     * The default: the largest context up to 16 384 tokens that fits in the memory available when the model loads,
     * and at least 4096 (D33, D34).
     *
     * "Fits" uses llama.cpp's own projection of what the model, its context cache and its compute buffers take, summed
     * over the chosen device and the host (a phone has one RAM), compared with the kernel's available memory (`MemAvailable`, what Android itself reports as
     * `availMem`) less Android's low-memory threshold, the prompt cache ([ModelConfig.ramCacheMib]), an image
     * encoder's file and a reserve for what grows during a chat ([ModelConfig.memoryReserveMib], 512 MiB by default). A phone short on memory gets a smaller
     * context; if even 4096 tokens don't fit, the load fails with
     * `LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory` instead of risking the app.
     *
     * Why 16 384: common local defaults are 4096 (Ollama, LM Studio, Apple's on-device model); reasoning and tool
     * results take 1–3K tokens a turn on current models, so four times that leaves room for a real conversation,
     * while a dense 3B model's cache stays near 1.8 GB. `Conversation` compacts when a chat outgrows it. For long
     * documents use [UpTo] with a higher ceiling, or [Fixed].
     */
    public data object Auto : ContextSize

    /**
     * Like [Auto], fitted to available memory, but with your ceiling: at most [maxTokens] (and the model's training
     * context), at least 4096 or [maxTokens] if smaller.
     */
    public data class UpTo(public val maxTokens: Int) : ContextSize {
        init {
            require(maxTokens >= 1) { "maxTokens must be at least 1" }
        }
    }

    /** Exactly [tokens], whatever the free memory; the load fails if it can't be allocated. */
    public data class Fixed(public val tokens: Int) : ContextSize {
        init {
            // 0 would be upstream's "the training context" (262 144 tokens for Qwen3.5), reserved at load
            require(tokens >= 1) { "tokens must be at least 1" }
        }
    }

    public companion object {
        /** [Auto]'s ceiling. */
        public const val AUTO_CEILING: Int = 16384
    }
}

/** Speculative decoding ([ModelConfig.speculative]). [Model.loadReport] says what runs and why. */
public sealed interface Speculative {
    /**
     * The default: no speculative decoding, as upstream. Kept apart from [Off] so a later llama.cpp can turn it on where it
     * wins; at b12052 it wins nowhere for prose (MTP is slower on the CPU and the NPU, F67), and drafting aborts the GPU
     * backend. [Model.loadReport] gives the reason for the chip. For structured output (JSON, tool calls) on the NPU,
     * [Mtp] with `draftMax = 4` decodes about 1.5× faster (F68); for replies that copy their input, [Ngram].
     */
    public data object Auto : Speculative
    /** None. */
    public data object Off : Speculative
    /**
     * The GGUF's multi-token-prediction head (`--spec-type draft-mtp`), for the whole load (llama-server can't switch
     * it per request). Worth it on [DeviceKind.Npu] when replies are mostly structured output (F68). On the NPU a greedy
     * reply can differ slightly from one without it (F72). Never on [DeviceKind.Gpu]: at b12052 ggml-opencl aborts the
     * process on the first drafted batch (F67, F80), so [DevicePolicy.Auto] and [DevicePolicy.Prefer] skip the GPU, with
     * that reason in [Model.loadReport], and `DevicePolicy.Only(DeviceKind.Gpu)` throws
     * [LlamaBroException.DeviceUnavailable].
     *
     * @property draftMax tokens drafted per step (`--spec-draft-n-max`), at least 1.
     */
    public class Mtp(public val draftMax: Int = 3) : Speculative {
        init {
            require(draftMax >= 1) { "draftMax must be at least 1" }
        }

        // not a data class, so that a setting can be added later without breaking callers (D26)
        override fun equals(other: Any?): Boolean = other is Mtp && draftMax == other.draftMax
        override fun hashCode(): Int = draftMax
        override fun toString(): String = "Mtp(draftMax=$draftMax)"
    }

    /**
     * n-gram drafting (upstream's `--spec-default`: ngram-mod, lookup 24, drafts of 48 to 64 tokens): the recent tokens are
     * looked up in the prompt and earlier replies, and what followed them is drafted, with no extra model. For replies
     * that copy their input: code edits, rewrites, quoting, repeated structured output. On the OnePlus 13 a code edit
     * decoded 7x faster on [DeviceKind.Npu] and 2.3x on [DeviceKind.Cpu] with an identical reply; prose drafts nothing,
     * so it is unaffected; output that only partly repeats was 10-15% slower on the CPU (F72, D35). For the whole load,
     * like [Mtp]. The pool is shared across requests, so a reply can draft from an earlier one. Never on the GPU, as
     * for [Mtp]: drafted batches abort ggml-opencl (F80).
     *
     * @property then the MTP head to draft with when n-grams have nothing to offer (`--spec-type ngram-mod,draft-mtp`);
     * the model needs an MTP head ([ModelInfo]). On the NPU this was the fastest for code, quoting and JSON (F72).
     */
    public class Ngram(public val then: Mtp? = null) : Speculative {
        // not a data class, so that the n-gram sizes can be added later without breaking callers (D26)
        override fun equals(other: Any?): Boolean = other is Ngram && then == other.then
        override fun hashCode(): Int = then.hashCode()
        override fun toString(): String = "Ngram(then=$then)"
    }

    /**
     * Drafts with a separate local GGUF supplied by `ModelSource.file(path) { draft = … }`.
     * Structured replies on the NPU benefit from DFlash (D41); drafted batches cannot run on the GPU (F80).
     * The draft retains upstream's F16 KV cache even when [ModelConfig.kvCacheType] differs.
     *
     * @property draftMax tokens drafted per step (`--spec-draft-n-max`), at least 1.
     */
    public class DFlash(public val draftMax: Int = 15) : Speculative {
        init { require(draftMax >= 1) { "draftMax must be at least 1" } }

        override fun equals(other: Any?): Boolean = other is DFlash && draftMax == other.draftMax
        override fun hashCode(): Int = draftMax
        override fun toString(): String = "DFlash(draftMax=$draftMax)"
    }
}

/**
 * What a model is loaded for ([ModelConfig.task]). llama-server serves one task per load: an embedding or reranking
 * model answers [Endpoint.Embeddings], [Endpoint.EmbeddingsNative] or [Endpoint.Rerank], and nothing else.
 */
public sealed interface ModelTask {
    /** Chat and text completion, the default. */
    public data object Generate : ModelTask
    /** Embedding vectors (`--embeddings`), pooled per input as [pooling] says. */
    public data class Embedding(public val pooling: Pooling = Pooling.ModelDefault) : ModelTask
    /** Scores documents against a query (`--reranking`), for a reranker model. */
    public data object Rerank : ModelTask
}

/** How an embedding model turns per-token vectors into one per input (`--pooling`). */
public enum class Pooling(internal val nativeName: String?) {
    /** The model file's own pooling. */
    ModelDefault(null),
    /** A vector per token (llama-server's native [Endpoint.EmbeddingsNative] only). */
    None("none"),
    Mean("mean"),
    Cls("cls"),
    Last("last"),
}

/** The context cache's number formats llama.cpp accepts (`-ctk`/`-ctv`, `common/arg.cpp`). */
public enum class KvCacheType(internal val nativeName: String) {
    F32("f32"), F16("f16"), Bf16("bf16"), Q8_0("q8_0"), Q4_0("q4_0"), Q4_1("q4_1"), Iq4Nl("iq4_nl"), Q5_0("q5_0"), Q5_1("q5_1"),
}

/** A LoRA adapter GGUF made for the base model ([ModelConfig.loraAdapters]), applied at [scale] (0 = off). */
public class LoraAdapter(public val path: String, public val scale: Float = 1f) {
    // not a data class: upstream's adapters have more settings (task name, prompt prefix) that may come later (D26)
    override fun equals(other: Any?): Boolean = other is LoraAdapter && path == other.path && scale == other.scale
    override fun hashCode(): Int = 31 * path.hashCode() + scale.hashCode()
    override fun toString(): String = "LoraAdapter(path=$path, scale=$scale)"
}
