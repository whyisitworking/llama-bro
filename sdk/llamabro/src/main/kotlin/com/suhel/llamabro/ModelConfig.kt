package com.suhel.llamabro

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Settings fixed when a model loads, as a value: `ModelConfig { contextSize = ContextSize.Fixed(4096) }`, derived with
 * [copy] (a phone profile an app keeps and adjusts), and given to `loadModel(source, config)`, or built in the scope
 * of `loadModel(source) { … }` ([D102](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d102)).
 * Defaults are the phone defaults ([D17](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d17)).
 */
public class ModelConfig internal constructor(builder: Builder) {
    /** Tokens of context (`n_ctx`). */
    public val contextSize: ContextSize = builder.contextSize

    /**
     * Speculative decoding: [Speculative.Off] by default, as upstream; the app opts in to a drafting mode. Drafting
     * with a model of its own ([Speculative.DFlash]) also needs that model's file on the source:
     * `ModelSource.file(path) { draft = … }`. The source names the files; this config says how to run them.
     */
    public val speculative: Speculative = builder.speculative

    /** Which chip runs the model; [DevicePolicy.Auto] picks the fastest that loads ([Model.loadReport] says which). */
    public val devicePolicy: DevicePolicy = builder.devicePolicy

    /**
     * Which chip runs the image and audio encoder (the source's `mmproj`), as llama-server's `--mmproj-device`. null,
     * the default, follows the model's device. [DevicePolicy.Only] fails the load with
     * [LlamaBroException.DeviceUnavailable] if that kind isn't available; [DevicePolicy.Prefer] and
     * [DevicePolicy.Auto] (the fastest available) fall back to the model's device when theirs is missing or fails to
     * load. [Model.loadReport] says where the SDK placed it.
     */
    public val encoderDevice: DevicePolicy? = builder.encoderDevice

    /** null = llama.cpp's default. */
    public val threads: Int? = builder.threads

    /**
     * The sampling seed of requests that set none (`--seed`); null keeps llama-server's default, a random seed. A
     * voice session's replies take this seed and none of their own
     * ([F168](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f168)), so a fixed one
     * makes them reproducible.
     */
    public val seed: Long? = builder.seed

    /** Logical batch for prompt processing (`-b`). */
    public val batchSize: Int = builder.batchSize

    /**
     * Physical batch (`-ub`); bigger didn't help on the phone
     * ([F10](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f10)).
     */
    public val microBatchSize: Int = builder.microBatchSize

    /**
     * Requests the model serves at once (`-np`); each slot has its own cache. One fits a chat app. With more, the
     * context size applies per slot: [ContextSize.Auto] and [ContextSize.UpTo] fit each slot up to the ceiling, and
     * [ContextSize.Fixed] is split between them, as llama-server does.
     */
    public val parallelSlots: Int = builder.parallelSlots

    /** RAM for llama-server's prompt cache across conversations (`--cache-ram`), in MiB. */
    public val ramCacheMib: Int = builder.ramCacheMib

    /**
     * Memory [ContextSize.Auto] and [ContextSize.UpTo] leave free for what grows during a chat and isn't projected
     * (context checkpoints, slack), in MiB, like llama-server's `--fit-target` margin. The default, 512, was measured
     * on the OnePlus 13 ([D34](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d34),
     * [F66](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f66)). Lower it only when
     * the app knows its phone: below the default, a long chat can grow into memory Android needs, and its low-memory
     * killer then ends the whole app, which no exception reports
     * ([D45](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d45)). [LlamaBro.estimateFit] uses
     * the same value. At least 0.
     */
    public val memoryReserveMib: Int = builder.memoryReserveMib

    /**
     * What happens when [ContextSize.Auto] or [ContextSize.UpTo] finds that even the smallest context doesn't fit:
     * [MemoryCheck.Warn] (the default) loads at the smallest context and says so in [LoadReport.memoryWarning];
     * [MemoryCheck.Refuse] fails the load. The check can't see what Android frees by closing background apps, so it
     * refuses models that run
     * ([F128](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f128)).
     * [ContextSize.Fixed] has no check.
     */
    public val memoryCheck: MemoryCheck = builder.memoryCheck

    /** Context checkpoints kept per slot (`--ctx-checkpoints`), which let hybrid models reuse a prefix (§7). */
    public val checkpointsPerSlot: Int = builder.checkpointsPerSlot

    /** What the model is loaded for: chat and completion, embeddings, or reranking ([ModelTask]). */
    public val task: ModelTask = builder.task

    /**
     * The context cache's number format (`-ctk`/`-ctv`, both). [KvCacheType.Q8_0] about halves the cache, so
     * [ContextSize.Auto] fits about twice the tokens; llama.cpp's default [KvCacheType.F16] is exact. The model's
     * head size must be a multiple of the type's block (32 for Q8_0), or the load fails with llama.cpp's reason.
     */
    public val kvCacheType: KvCacheType = builder.kvCacheType

    /**
     * LoRA adapters loaded with the model (`--lora`), each at its scale. Their position in the list is the `id` that a
     * request's `lora` field (`extra`) and [Endpoint.LoraAdaptersSet] use to change a scale.
     */
    public val loraAdapters: List<LoraAdapter> = builder.loraAdapters

    /** This config with [configure]'s changes. */
    public fun copy(configure: Builder.() -> Unit): ModelConfig = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean = other is ModelConfig && other.parts() == parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "ModelConfig(" +
            "contextSize=$contextSize, speculative=$speculative, " +
            "devicePolicy=$devicePolicy, encoderDevice=$encoderDevice, threads=$threads, " +
            "seed=$seed, batchSize=$batchSize, microBatchSize=$microBatchSize, " +
            "parallelSlots=$parallelSlots, ramCacheMib=$ramCacheMib, " +
            "memoryReserveMib=$memoryReserveMib, memoryCheck=$memoryCheck, " +
            "checkpointsPerSlot=$checkpointsPerSlot, task=$task, kvCacheType=$kvCacheType, " +
            "loraAdapters=$loraAdapters)" +
            ")"

    private fun parts(): List<Any?> =
        listOf(
            contextSize,
            speculative,
            devicePolicy,
            encoderDevice,
            threads,
            seed,
            batchSize,
            microBatchSize,
            parallelSlots,
            ramCacheMib,
            memoryReserveMib,
            memoryCheck,
            checkpointsPerSlot,
            task,
            kvCacheType,
            loraAdapters,
        )

    /**
     * Mutable scope of `ModelConfig { }` and [copy], starting from the config it was made from
     * ([D17](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d17)); each setting is described
     * on [ModelConfig]'s property of the same name.
     */
    public class Builder internal constructor() {
        internal constructor(from: ModelConfig) : this() {
            contextSize = from.contextSize
            speculative = from.speculative
            devicePolicy = from.devicePolicy
            encoderDevice = from.encoderDevice
            threads = from.threads
            seed = from.seed
            batchSize = from.batchSize
            microBatchSize = from.microBatchSize
            parallelSlots = from.parallelSlots
            ramCacheMib = from.ramCacheMib
            memoryReserveMib = from.memoryReserveMib
            memoryCheck = from.memoryCheck
            checkpointsPerSlot = from.checkpointsPerSlot
            task = from.task
            kvCacheType = from.kvCacheType
            loraAdapters = from.loraAdapters
        }

        public var contextSize: ContextSize = ContextSize.Auto
        public var speculative: Speculative = Speculative.Off
        public var devicePolicy: DevicePolicy = DevicePolicy.Auto
        public var encoderDevice: DevicePolicy? = null
        public var threads: Int? = null
        public var seed: Long? = null
        public var batchSize: Int = 512
        public var microBatchSize: Int = 512
        public var parallelSlots: Int = 1
        public var ramCacheMib: Int = 1024

        /** @throws IllegalArgumentException when set below 0. */
        public var memoryReserveMib: Int = 512
            set(value) {
                require(value >= 0) { "memoryReserveMib must be at least 0, was $value" }
                field = value
            }

        public var memoryCheck: MemoryCheck = MemoryCheck.Warn
        public var checkpointsPerSlot: Int = 8
        public var task: ModelTask = ModelTask.Generate
        public var kvCacheType: KvCacheType = KvCacheType.F16
        public var loraAdapters: List<LoraAdapter> = emptyList()

        internal fun build(): ModelConfig = ModelConfig(this)
    }

    public companion object {
        /** The phone defaults ([D17](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d17)). */
        public val Default: ModelConfig = Builder().build()
    }

    /**
     * Checks the settings against [source]. A [RemoteModelSource] is checked again on the files its fetch returns
     * (D75), since only those say whether there is a draft.
     */
    internal fun validateSource(source: ModelSource) {
        when (source) {
            is FileSource -> {
                require(speculative !is Speculative.DFlash || source.draft != null) {
                    "Speculative.DFlash needs ModelSource.file(path) { draft = … }"
                }
            }

            is RemoteModelSource -> {
                // nothing to check before the fetch: the load checks the files it returns
            }
        }
    }

    /** The `lb_model_load` config (bridge/src/config.cpp). */
    internal fun toJson(
        source: ModelSource,
        slotSavePath: String,
        devices: List<String> = emptyList(),
        memoryBudgetMib: Long? = null,
        mmprojDevice: String? = null,
        /** Load at the smallest context with no budget: the memory check said no, and the app chose to be warned. */
        smallestContext: Boolean = false,
        /** llama-server's media root: path attachments reach it as `file://` links made there (D84, F157). */
        mediaDir: String? = null,
    ): String =
        buildJsonObject {
            validateSource(source)
            putDevices(devices)
            // upstream's -mmdev: "none" is the CPU, else a device name; absent follows the model's device
            mmprojDevice?.let { put("mmproj_device", it) }
            putSource(source)
            putContext(memoryBudgetMib, smallestContext)
            putBatching(slotSavePath)
            mediaDir?.let { put("media_dir", it) }
            putTask()
            putLoraAdapters()
            putSpeculative()
        }.toString()

    /** Always explicit (F32): [] is CPU only; one device takes every layer (upstream's `-dev X -ngl 99`). */
    private fun JsonObjectBuilder.putDevices(devices: List<String>) {
        put("devices", JsonArray(devices.map { JsonPrimitive(it) }))
        if (devices.isNotEmpty()) put("n_gpu_layers", ALL_LAYERS)
    }

    private fun JsonObjectBuilder.putSource(source: ModelSource) {
        // validateSource let only local files and remote sources through, and a remote one is fetched first (D75)
        check(source is FileSource) { "a ModelSource must be resolved to local files before it loads" }
        put("model_path", source.path)
        source.mmproj?.let { put("mmproj_path", it) }
        if (speculative is Speculative.DFlash) source.draft?.let { put("draft_path", it) }
    }

    private fun JsonObjectBuilder.putContext(memoryBudgetMib: Long?, smallestContext: Boolean) {
        when (val c = contextSize) {
            ContextSize.Auto, is ContextSize.UpTo -> {
                if (smallestContext) {
                    put("n_ctx", ContextSize.smallest(c))
                    return
                }
                put("n_ctx", "auto")
                put("n_ctx_cap", if (c is ContextSize.UpTo) c.maxTokens else ContextSize.AUTO_CEILING)
                // without a budget (no memory figures, e.g. FakeLlamaBro) the native side uses min(ceiling, trained)
                memoryBudgetMib?.let { put("memory_budget_mib", it) }
            }

            is ContextSize.Fixed -> {
                put("n_ctx", c.tokens)
            }
        }
    }

    private fun JsonObjectBuilder.putBatching(slotSavePath: String) {
        threads?.let { put("n_threads", it) }
        seed?.let { put("seed", it) }
        put("n_batch", batchSize)
        put("n_ubatch", microBatchSize)
        put("n_parallel", parallelSlots)
        put("cache_ram_mib", ramCacheMib)
        put("n_ctx_checkpoints", checkpointsPerSlot)
        put("slot_save_path", slotSavePath)
        if (kvCacheType != KvCacheType.F16) put("kv_cache_type", kvCacheType.nativeName)
    }

    private fun JsonObjectBuilder.putTask() {
        when (val t = task) {
            ModelTask.Generate -> {
                Unit
            }

            is ModelTask.Embedding -> {
                put("embeddings", true)
                t.pooling.nativeName?.let { put("pooling", it) }
            }

            ModelTask.Rerank -> {
                put("reranking", true)
            }
        }
    }

    private fun JsonObjectBuilder.putLoraAdapters() {
        if (loraAdapters.isEmpty()) return
        put(
            "lora",
            JsonArray(
                loraAdapters.map { a ->
                    buildJsonObject {
                        put("path", a.path)
                        put("scale", a.scale)
                    }
                },
            ),
        )
    }

    /** Off sends no setting: upstream's plain decoding (F67). */
    private fun JsonObjectBuilder.putSpeculative() {
        when (val s = speculative) {
            Speculative.Off -> {
                Unit
            }

            is Speculative.Mtp -> {
                put("speculative", speculation("mtp") { put("n_max", s.draftMax) })
            }

            is Speculative.Ngram -> {
                put(
                    "speculative",
                    speculation("ngram") { s.then?.let { put("mtp_n_max", it.draftMax) } },
                )
            }

            is Speculative.DFlash -> {
                put("speculative", speculation("dflash") { put("n_max", s.draftMax) })
            }
        }
    }

    private fun speculation(type: String, extra: JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            put("type", type)
            extra()
        }
}

/** `ModelConfig { contextSize = ContextSize.Fixed(4096) }`: the phone defaults with [configure]'s changes. */
public fun ModelConfig(configure: ModelConfig.Builder.() -> Unit): ModelConfig = ModelConfig.Default.copy(configure)

private const val ALL_LAYERS = 999

/**
 * How big the context is ([ModelConfig.contextSize]): the tokens the model can see at once, the conversation so far
 * plus the reply. Its cache is reserved when the model loads, so a bigger context costs memory up front (on the GPU
 * and NPU fully, on the CPU as it fills). [Model.loadReport] tells you the size picked and why.
 */
public sealed interface ContextSize {
    /**
     * The default: the largest context up to 16 384 tokens that fits in the memory available when the model loads,
     * and at least 4096 ([D33](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d33),
     * [D34](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d34)).
     *
     * "Fits" uses llama.cpp's own projection of what the model, its context cache and its compute buffers take, summed
     * over the chosen device and the host (a phone has one RAM), compared with the kernel's available memory
     * (`MemAvailable`, what Android itself reports as `availMem`) less Android's low-memory threshold, the prompt cache
     * ([ModelConfig.ramCacheMib]), an image encoder's file and a reserve for what grows during a chat
     * ([ModelConfig.memoryReserveMib], 512 MiB by default). A phone short on memory gets a smaller context. If even
     * 4096 tokens don't fit, [ModelConfig.memoryCheck] decides: by default the model loads at 4096 tokens and
     * [LoadReport.memoryWarning] says by how much it was short ([MemoryCheck.Warn]); with [MemoryCheck.Refuse] the load
     * fails with `LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory`.
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

        /** The smallest context [Auto] fits (D33). */
        internal const val AUTO_FLOOR: Int = 4096

        /** The context a fitted size falls back to when nothing fits: 4096, or an [UpTo] ceiling below it. */
        internal fun smallest(size: ContextSize): Int = minOf(AUTO_FLOOR, (size as? UpTo)?.maxTokens ?: AUTO_FLOOR)
    }
}

/** Speculative decoding ([ModelConfig.speculative]). [Model.loadReport] says what runs and why. */
public sealed interface Speculative {
    /**
     * None, the default, as upstream: measured, drafting wins nowhere for prose (MTP is slower on the CPU and the NPU,
     * [F67](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f67)), and it aborts the
     * GPU backend. [Model.loadReport] gives the reason for the chip. For structured output (JSON, tool calls) on the
     * NPU, [Mtp] with `draftMax = 4` decodes about 1.5× faster
     * ([F68](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f68)); for replies that
     * copy their input, [Ngram].
     */
    public data object Off : Speculative

    /**
     * The GGUF's multi-token-prediction head (`--spec-type draft-mtp`), for the whole load (llama-server can't switch
     * it per request). Worth it on [DeviceKind.Npu] when replies are mostly structured output
     * ([F68](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f68)). On the NPU a greedy
     * reply can differ slightly from one without it
     * ([F72](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f72)). Never on
     * [DeviceKind.Gpu]: ggml-opencl aborts the process on the first drafted batch
     * ([F67](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f67),
     * [F80](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f80)), so
     * [DevicePolicy.Auto] and [DevicePolicy.Prefer] skip the GPU, with that reason in [Model.loadReport], and
     * `DevicePolicy.Only(DeviceKind.Gpu)` throws [LlamaBroException.DeviceUnavailable].
     *
     * @property draftMax tokens drafted per step (`--spec-draft-n-max`), at least 1.
     */
    public data class Mtp(public val draftMax: Int = 3) : Speculative {
        init {
            require(draftMax >= 1) { "draftMax must be at least 1" }
        }
    }

    /**
     * n-gram drafting (upstream's `--spec-default`: ngram-mod, lookup 24, drafts of 48 to 64 tokens): the recent tokens
     * are looked up in the prompt and earlier replies, and what followed them is drafted, with no extra model. For
     * replies that copy their input: code edits, rewrites, quoting, repeated structured output. On the OnePlus 13 a
     * code edit decoded 7x faster on [DeviceKind.Npu] and 2.3x on [DeviceKind.Cpu] with an identical reply; prose
     * drafts nothing, so it is unaffected; output that only partly repeats was 10-15% slower on the CPU
     * ([F72](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f72),
     * [D35](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d35)). For the whole load, like
     * [Mtp]. The pool is shared across requests, so a reply can draft from an earlier one. Never on the GPU, as for
     * [Mtp]: drafted batches abort ggml-opencl
     * ([F80](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f80)).
     *
     * @property then the MTP head to draft with when n-grams have nothing to offer (`--spec-type ngram-mod,draft-mtp`);
     * the model needs an MTP head ([ModelInfo]). On the NPU this was the fastest for code, quoting and JSON
     * ([F72](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f72)).
     */
    public data class Ngram(public val then: Mtp? = null) : Speculative

    /**
     * Drafts with a separate local GGUF supplied by `ModelSource.file(path) { draft = … }`.
     * Structured replies on the NPU benefit from DFlash
     * ([D41](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d41)); drafted batches cannot run
     * on the GPU ([F80](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f80)). The
     * draft retains upstream's F16 KV cache even when [ModelConfig.kvCacheType] differs.
     *
     * @property draftMax tokens drafted per step (`--spec-draft-n-max`), at least 1.
     */
    public data class DFlash(public val draftMax: Int = 15) : Speculative {
        init {
            require(draftMax >= 1) { "draftMax must be at least 1" }
        }
    }
}

/**
 * What a model is loaded for ([ModelConfig.task]). llama-server serves one task per load: an embedding or reranking
 * model answers [Endpoint.Embeddings], [Endpoint.EmbeddingsNative] or [Endpoint.Rerank] and, as upstream's does, the
 * routes that only read the tokenizer, [Endpoint.Tokenize] and [Endpoint.Detokenize]
 * ([F190](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f190)); it does not generate.
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

/**
 * The context cache's number formats (`-ctk`/`-ctv`, `common/arg.cpp`). b12352's `f8_e4m3` is left out: only the CPU
 * backend has it, and Q8_0 already halves the cache
 * ([F121](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f121)).
 */
public enum class KvCacheType(internal val nativeName: String) {
    F32("f32"),
    F16("f16"),
    Bf16("bf16"),
    Q8_0("q8_0"),
    Q4_0("q4_0"),
    Q4_1("q4_1"),
    Iq4Nl("iq4_nl"),
    Q5_0("q5_0"),
    Q5_1("q5_1"),
}

/** A LoRA adapter GGUF made for the base model ([ModelConfig.loraAdapters]), applied at [scale] (0 = off). */
public data class LoraAdapter(public val path: String, public val scale: Float = 1f)
