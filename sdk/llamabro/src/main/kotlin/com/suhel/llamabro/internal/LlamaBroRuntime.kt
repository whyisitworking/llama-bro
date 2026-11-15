package com.suhel.llamabro.internal

import com.suhel.llamabro.Backend
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DeviceInfo
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.FileSource
import com.suhel.llamabro.FitEstimate
import com.suhel.llamabro.LlamaBroConfig
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.LoadReport
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.MemoryCheck
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Speculative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * The process-wide runtime (design §5): initialises the native runtime on first use, off the caller's thread
 * (it costs about 13 ms on the phone, F48), and loads models.
 */
internal class LlamaBroRuntime(
    private val engine: Engine,
    private val noBackupDir: File,
    private val backendDir: String?,
    private val config: LlamaBroConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Backend artifacts on the classpath (P2 T2); runs once per init attempt, on [ioDispatcher]. */
    private val discoverBackends: () -> BackendDiscovery.Result = { BackendDiscovery.Result.None },
    /** Android's memory at load time, for `ContextSize.Auto` (P2 T7); null without it (the P1 default context). */
    private val memorySnapshot: () -> MemoryBudget.Snapshot? = { null },
    /** Where lines go when the app's [LlamaBroConfig.logger] is null (logcat on Android, D66). */
    private val defaultLogger: Logger? = null,
) {
    private val initLock = Mutex()

    @Volatile private var initialized = false

    /** Native shutdowns still running ([Model.close] returns before they finish). */
    private val closing = mutableSetOf<Deferred<Unit>>()

    /** When the last native close returned ([System.nanoTime]); 0 before any. */
    @Volatile private var lastClosedAt = 0L

    /** Runs native init once; a failure is thrown again on the next call. */
    private suspend fun ensureInit() {
        if (initialized) return
        initLock.withLock {
            if (initialized) return
            withContext(ioDispatcher) { init() }
            initialized = true
        }
    }

    /** Backends that never reached native init: a bad manifest entry or an installer that threw. */
    @Volatile private var discoveryFailures: List<Backend> = emptyList()

    private fun init() {
        // before native init, which logs why a backend failed to load; with no logger those lines go to stderr, which
        // Android discards (D66, F144). A second runtime with a mismatched config therefore swaps the process-wide
        // listener before native init returns status 2; W2's create() refuses such a config in Kotlin first (D70).
        attachLogger()
        val discovered = discoverBackends()
        discovered.failed.forEach { log(LogLevel.Warn, "backend ${it.name} not loaded: ${it.error}") }
        val error =
            engine.init(
                buildJsonObject {
                    backendDir?.let { put("backend_dir", it) }
                    put("log_level", config.logLevel.name.lowercase())
                    if (discovered.found.isNotEmpty()) put("backends", discovered.toJson())
                }.toString(),
            )
        discoveryFailures = discovered.failed
        if (error != null) {
            val status =
                try {
                    (Json.parseToJsonElement(error) as JsonObject)["status"]?.jsonPrimitive?.content?.toInt()
                } catch (ignored: Exception) {
                    null
                }
            // LB_ERR_ALREADY_INITIALIZED (2): another LlamaBroConfig was used first in this process (design §5)
            throw LlamaBroException.InvalidRequest(
                if (status == 2) {
                    "LlamaBro is already initialized with a different LlamaBroConfig: ${ErrorMapping.nativeMessage(
                        error,
                    )}"
                } else {
                    "LlamaBro runtime init failed: ${ErrorMapping.nativeMessage(error)}"
                },
            )
        }
    }

    private val logger: Logger? get() = config.logger ?: defaultLogger

    private fun attachLogger() {
        if (logger == null) return
        engine.setLog { level, text ->
            log(LogLevel.entries[level.coerceIn(0, LogLevel.entries.size - 1)], text.trimEnd('\n'))
        }
    }

    /** A logger's own exception must not fail init or reach native code. */
    private fun log(level: LogLevel, text: String) {
        if (level.ordinal < config.logLevel.ordinal) return
        try {
            logger?.log(level, text)
        } catch (ignored: Exception) {
            // dropped on purpose
        }
    }

    /** `noBackupFilesDir/llamabro/slots/` (design §8). */
    val slotsDir: File get() = File(noBackupDir, "llamabro/slots")

    suspend fun devices(): List<DeviceInfo> {
        ensureInit()
        val (json, backends) = withContext(ioDispatcher) { engine.devices() to nativeBackends() }
        // a device's kind comes from the backend that added it: ggml's type calls Hexagon a GPU (F32)
        val kindOfDevice = backends.flatMap { b -> b.devices.map { it to DeviceResolver.kindOf(b.name) } }.toMap()
        return (Json.parseToJsonElement(json) as JsonArray).map {
            val o = it as JsonObject
            val name = o.getValue("name").jsonPrimitive.content
            DeviceInfo(
                name = name,
                description = o.getValue("description").jsonPrimitive.content,
                kind =
                    kindOfDevice[name]
                        ?: if (o.getValue("type").jsonPrimitive.content == "cpu") DeviceKind.Cpu else DeviceKind.Gpu,
                memoryFree = o.getValue("memory_free").jsonPrimitive.long,
                memoryTotal = o.getValue("memory_total").jsonPrimitive.long,
            )
        }
    }

    suspend fun backends(): List<Backend> {
        ensureInit()
        return withContext(ioDispatcher) { nativeBackends() } + discoveryFailures
    }

    private fun nativeBackends(): List<Backend> =
        (Json.parseToJsonElement(engine.backends()) as JsonArray).map {
            val o = it as JsonObject
            Backend(
                name = o.getValue("name").jsonPrimitive.content,
                loaded = o.getValue("loaded").jsonPrimitive.boolean,
                error = o["error"]?.jsonPrimitive?.contentOrNull,
                devices = (o["devices"] as? JsonArray)?.map { d -> d.jsonPrimitive.content } ?: emptyList(),
            )
        }

    /** Starts [block] (a model's native shutdown) on the IO dispatcher; the next [load] waits for it. */
    fun shutdown(block: () -> Unit): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        synchronized(closing) { closing += done }
        shutdownScope.launch {
            try {
                block()
            } finally {
                lastClosedAt = System.nanoTime()
                synchronized(closing) { closing -= done }
                done.complete(Unit)
            }
        }
        return done
    }

    suspend fun load(source: ModelSource, builder: ModelConfig): Model {
        builder.validateSource(source)
        ensureInit()
        // a model being closed still holds its weights and KV cache until its native close returns
        synchronized(closing) { closing.toList() }.awaitAll()
        awaitMemorySettled()
        val listener = builder.progressListener
        return coroutineScope {
            // progress arrives on the loading thread; it's handed to the caller's context, keeping the latest value
            val progress =
                listener?.let { l ->
                    Channel<Float>(Channel.CONFLATED).also { ch -> launch { for (p in ch) l(p) } }
                }
            try {
                val backends = withContext(ioDispatcher) { nativeBackends() } + discoveryFailures
                loadOnFirstDevice(
                    source,
                    builder,
                    DeviceResolver.plan(builder.devicePolicy, backends),
                    backends,
                ) { p -> progress?.trySend(p) }
            } finally {
                progress?.close()
            }
        }
    }

    /** Projects the first device a load would attempt, without opening model weights. */
    suspend fun estimateFit(source: ModelSource, builder: ModelConfig): FitEstimate {
        builder.validateSource(source)
        ensureInit()
        synchronized(closing) { closing.toList() }.awaitAll()
        return withContext(ioDispatcher) {
            val backends = nativeBackends() + discoveryFailures
            requireEncoderDevice(builder.encoderDevice.takeIf { (source as? FileSource)?.mmproj != null }, backends)
            val plan = DeviceResolver.plan(builder.devicePolicy, backends)
            val drafts =
                builder.speculative is Speculative.Mtp || builder.speculative is Speculative.Ngram ||
                    builder.speculative is Speculative.DFlash
            val candidate =
                plan.candidates.firstOrNull { !drafts || it.kind != DeviceKind.Gpu }
                    ?: run {
                        val kind = (builder.devicePolicy as? DevicePolicy.Only)?.kind ?: DeviceKind.Cpu
                        val reasons =
                            plan.skipped.map { it.reason } +
                                plan.candidates
                                    .filter {
                                        drafts && it.kind == DeviceKind.Gpu
                                    }.map { MtpPolicy.GPU_ABORTS }
                        throw LlamaBroException.DeviceUnavailable(
                            kind,
                            "no ${kind.name} device could estimate the model: ${reasons.joinToString("; ")}",
                            null,
                        )
                    }
            slotsDir.mkdirs()
            val configJson = builder.toJson(source, slotsDir.path + "/", candidate.devices, budgetFor(source, builder))
            val result =
                try {
                    Json.parseToJsonElement(engine.fitEstimate(configJson)) as JsonObject
                } catch (
                    error: NativeError,
                ) {
                    throw ErrorMapping.loadFailed(error)
                }
            val mib = BYTES_PER_MIB

            fun nullableLong(key: String): Long? = result[key]?.jsonPrimitive?.longOrNull
            FitEstimate(
                fits = result["fits"]?.jsonPrimitive?.booleanOrNull,
                contextTokens = result["n_ctx"]?.jsonPrimitive?.intOrNull,
                minContextTokens = result.getValue("n_ctx_min").jsonPrimitive.int,
                bytesAtMinimum = result.getValue("used_min_mib").jsonPrimitive.long * mib,
                bytesAtCeiling = nullableLong("used_max_mib")?.times(mib),
                budgetBytes = nullableLong("budget_mib")?.times(mib),
                device = candidate.kind,
                deviceName = candidate.deviceName,
                method = FitEstimate.Method.Projection,
            )
        }
    }

    /**
     * Tries the plan's devices in order, one model load each (P2 T6): a failed load frees itself, its reason goes in
     * the report, and the next device is tried. A missing file stops at once, since no device can fix it.
     */
    private suspend fun loadOnFirstDevice(
        source: ModelSource,
        builder: ModelConfig,
        plan: DeviceResolver.Plan,
        backends: List<Backend>,
        onProgress: (Float) -> Unit,
    ): Model {
        val encoderPolicy = builder.encoderDevice.takeIf { (source as? FileSource)?.mmproj != null }
        requireEncoderDevice(encoderPolicy, backends)
        val attempts = plan.skipped.toMutableList()
        var lastError: LlamaBroException.ModelLoadFailed? = null
        for (candidate in plan.candidates) {
            currentCoroutineContext().ensureActive()
            // drafting on the GPU aborts the process, which no try can catch: skip it (F67, F80)
            if (builder.drafts() && candidate.kind == DeviceKind.Gpu) {
                attempts += LoadReport.Attempt(candidate.kind, candidate.deviceName, MtpPolicy.GPU_ABORTS)
                continue
            }
            try {
                val encoder =
                    if (source is FileSource && source.mmproj != null) {
                        DeviceResolver.encoder(encoderPolicy, candidate, backends)
                    } else {
                        null
                    }
                return loadWithEncoderFallback(source, builder, candidate, encoder, encoderPolicy, attempts, onProgress)
            } catch (e: LlamaBroException.ModelLoadFailed) {
                if (e.isFinal()) throw e
                attempts += LoadReport.Attempt(candidate.kind, candidate.deviceName, e.message ?: "load failed")
                lastError = e
            }
        }
        throw noDeviceLoaded(builder.devicePolicy, attempts, lastError)
    }

    /**
     * One model device: with the encoder moved elsewhere by `Auto` or `Prefer` and the load failing, the same device is
     * tried once more with the encoder following the model, as those policies promise (D53); the failure is recorded.
     * `Only` doesn't fall back.
     */
    private suspend fun loadWithEncoderFallback(
        source: ModelSource,
        builder: ModelConfig,
        candidate: DeviceResolver.Candidate,
        encoder: DeviceResolver.Candidate?,
        encoderPolicy: DevicePolicy?,
        attempts: MutableList<LoadReport.Attempt>,
        onProgress: (Float) -> Unit,
    ): Model {
        val moved = encoder != null && encoder.deviceName != candidate.deviceName
        if (!moved || encoderPolicy is DevicePolicy.Only) {
            return loadModel(source, builder, candidate, encoder, attempts.toList(), onProgress)
        }
        return try {
            loadModel(source, builder, candidate, encoder, attempts.toList(), onProgress)
        } catch (e: LlamaBroException.ModelLoadFailed) {
            if (e.isFinal()) throw e
            attempts +=
                LoadReport.Attempt(
                    encoder!!.kind,
                    encoder.deviceName,
                    "image encoder on ${encoder.deviceName} failed (${e.message ?: "load failed"}); " +
                        "it follows the model instead",
                )
            loadModel(source, builder, candidate, candidate, attempts.toList(), onProgress)
        }
    }

    /** `encoderDevice = Only(kind)` with no device of that kind fails before any load (D53). */
    private fun requireEncoderDevice(policy: DevicePolicy?, backends: List<Backend>) {
        if (policy !is DevicePolicy.Only) return
        val plan = DeviceResolver.plan(policy, backends)
        if (plan.candidates.isEmpty()) {
            throw LlamaBroException.DeviceUnavailable(
                policy.kind,
                "no ${policy.kind.name} device for the image encoder: " + plan.skipped.joinToString("; ") { it.reason },
                null,
            )
        }
    }

    private fun ModelConfig.drafts() =
        speculative is Speculative.Mtp || speculative is Speculative.Ngram || speculative is Speculative.DFlash

    /**
     * Loads [configJson]. When the memory check refuses it and the app asked to be warned ([MemoryCheck.Warn], D57),
     * loads again at the smallest context without the budget, since the check can't see what Android frees by closing
     * cached apps (F128), and returns the check's message as the warning.
     */
    private fun loadOrWarn(
        source: ModelSource,
        builder: ModelConfig,
        candidate: DeviceResolver.Candidate,
        encoder: DeviceResolver.Candidate?,
        configJson: String,
        onProgress: (Float) -> Unit,
    ): Pair<EngineModel, String?> {
        val failure =
            try {
                return engine.load(configJson, onProgress) to null
            } catch (e: NativeError) {
                ErrorMapping.loadFailed(e)
            }
        if (!warnsInstead(builder, failure)) throw failure
        val smallest =
            builder.toJson(
                source,
                slotsDir.path + "/",
                candidate.devices,
                null,
                encoder?.let { DeviceResolver.encoderConfig(it, candidate) },
                smallestContext = true,
            )
        val model =
            try {
                engine.load(smallest, onProgress)
            } catch (e: NativeError) {
                throw ErrorMapping.loadFailed(e)
            }
        return model to failure.message?.removePrefix("failed to load model: ")
    }

    /** A fitted context that doesn't fit even at its smallest, for an app that asked to be warned (F128). */
    private fun warnsInstead(builder: ModelConfig, failure: LlamaBroException.ModelLoadFailed) =
        failure.reason == LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory &&
            builder.memoryCheck == MemoryCheck.Warn &&
            builder.contextSize !is ContextSize.Fixed

    /** No device fixes a missing file, and every device shares the phone's memory. */
    private fun LlamaBroException.ModelLoadFailed.isFinal() =
        reason == LlamaBroException.ModelLoadFailed.Reason.FileNotFound ||
            reason == LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory

    /** What to throw when no candidate loaded the model. */
    private fun noDeviceLoaded(
        policy: DevicePolicy,
        attempts: List<LoadReport.Attempt>,
        lastError: LlamaBroException.ModelLoadFailed?,
    ): Exception {
        if (policy is DevicePolicy.Only || lastError == null) {
            val kind = (policy as? DevicePolicy.Only)?.kind ?: DeviceKind.Cpu
            return LlamaBroException.DeviceUnavailable(
                kind,
                "no ${kind.name} device could load the model: " +
                    attempts.joinToString("; ") { "${it.deviceName ?: it.kind.name}: ${it.reason}" },
                lastError,
            )
        }
        return lastError // the last device tried's own reason (the CPU's, unless Prefer(Cpu) put it first)
    }

    private suspend fun loadModel(
        source: ModelSource,
        builder: ModelConfig,
        candidate: DeviceResolver.Candidate,
        encoder: DeviceResolver.Candidate?,
        attempts: List<LoadReport.Attempt>,
        onProgress: (Float) -> Unit,
    ): Model {
        // The native load can't be interrupted, and withContext drops its result if the caller was cancelled
        // meanwhile (prompt cancellation). So the model is recorded as soon as it exists and closed on cancellation,
        // instead of leaking its weights and KV cache.
        var loaded: Model? = null
        try {
            withContext(ioDispatcher + NonCancellable) {
                slotsDir.mkdirs()
                val budget = budgetFor(source, builder)
                val configJson =
                    builder.toJson(
                        source,
                        slotsDir.path + "/",
                        candidate.devices,
                        budget,
                        encoder?.let { DeviceResolver.encoderConfig(it, candidate) },
                    )
                val (engineModel, memoryWarning) =
                    loadOrWarn(source, builder, candidate, encoder, configJson, onProgress)
                val (contextSize, contextReason) =
                    if (memoryWarning != null) {
                        contextOf(engineModel.infoJson, builder.contextSize).first to
                            "the smallest context, loaded although the memory check said it may not fit " +
                            "(MemoryCheck.Warn)"
                    } else {
                        contextOf(engineModel.infoJson, builder.contextSize)
                    }
                val (speculative, speculativeReason) = speculativeOf(builder.speculative, candidate.kind, source)
                val report =
                    LoadReport(
                        candidate.kind,
                        candidate.deviceName,
                        candidate.backend,
                        attempts,
                        contextSize,
                        contextReason,
                        speculative,
                        speculativeReason,
                        encoder?.kind,
                        encoder?.deviceName,
                        memoryWarning,
                    )
                loaded = Model(engineModel, report, this@LlamaBroRuntime::shutdown)
            }
            currentCoroutineContext().ensureActive()
            return loaded!!
        } catch (e: CancellationException) {
            // freed before the cancellation reaches the caller, as if the load never happened
            loaded?.let { m -> withContext(NonCancellable) { m.closeAndJoin() } }
            throw e
        }
    }

    /** [MemoryBudget.budgetMib] with no side files, in bytes (F97); reads memory on [ioDispatcher]. */
    suspend fun memoryBudgetBytes(builder: ModelConfig): Long? {
        builder.validateReserve()
        return withContext(ioDispatcher) { memorySnapshot() }?.let { memory ->
            MemoryBudget.budgetMib(
                memory.available,
                memory.threshold,
                builder.ramCacheMib,
                0L,
                builder.memoryReserveMib.toLong(),
            ) * BYTES_PER_MIB
        }
    }

    /**
     * After a close, the NPU's buffers come back about a second after the native close returns (F124): a budget read
     * at once would be short by the closed model's size and refuse a model that fits. Within [SETTLE_WINDOW_NANOS] of
     * a close, wait until available memory stops rising, at most [SETTLE_MAX_MILLIS].
     */
    private suspend fun awaitMemorySettled() {
        val closedAt = lastClosedAt
        if (closedAt == 0L || System.nanoTime() - closedAt > SETTLE_WINDOW_NANOS) return
        var last = withContext(ioDispatcher) { memorySnapshot() }?.available ?: return
        val until = System.nanoTime() + SETTLE_MAX_MILLIS * NANOS_PER_MILLI
        while (System.nanoTime() < until) {
            delay(SETTLE_STEP_MILLIS)
            val now = withContext(ioDispatcher) { memorySnapshot() }?.available ?: return
            if (now - last < SETTLE_STILL_BYTES) return
            last = now
        }
    }

    private fun budgetFor(source: ModelSource, builder: ModelConfig): Long? {
        if (builder.contextSize != ContextSize.Auto && builder.contextSize !is ContextSize.UpTo) return null
        return memorySnapshot()?.let { memory ->
            // The draft is included in native projection; only these side files are excluded.
            val mmproj = (source as? FileSource)?.mmproj?.let { File(it).length() } ?: 0L
            val lora = builder.loraAdapters.sumOf { File(it.path).length() }
            MemoryBudget.budgetMib(
                memory.available,
                memory.threshold,
                builder.ramCacheMib,
                mmproj + lora,
                builder.memoryReserveMib.toLong(),
            )
        }
    }

    /** What decodes and why (P2 T8): what the app set, or for [Speculative.Auto] why it is off on this chip. */
    private fun speculativeOf(
        requested: Speculative,
        kind: DeviceKind,
        source: ModelSource,
    ): Pair<Speculative, String> {
        val result =
            when (requested) {
                Speculative.Off -> {
                    Speculative.Off to "off: set by the app"
                }

                Speculative.Auto -> {
                    Speculative.Off to "off: ${MtpPolicy.reason(kind)}"
                }

                is Speculative.Mtp -> {
                    requested to "MTP: set by the app"
                }

                is Speculative.Ngram -> {
                    requested to
                        (if (requested.then != null) "n-gram, then MTP: set by the app" else "n-gram: set by the app")
                }

                is Speculative.DFlash -> {
                    requested to "DFlash draft (D41)"
                }
            }
        val unusedDraft = (source as? FileSource)?.draft != null && requested !is Speculative.DFlash
        return if (unusedDraft) {
            result.first to "${result.second}; draft file unused (Speculative is not DFlash)"
        } else {
            result
        }
    }

    /** The loaded context and why (`lb_model_info`'s `n_ctx` and `ctx_fit`, D33). */
    private fun contextOf(infoJson: String, requested: ContextSize): Pair<Int, String> {
        val info = Json.parseToJsonElement(infoJson) as JsonObject
        val nCtx = info.getValue("n_ctx").jsonPrimitive.int
        val fit = info["ctx_fit"] as? JsonObject
        val reason =
            when {
                requested is ContextSize.Fixed -> {
                    "set by the app"
                }

                fit == null -> {
                    "min(${(requested as? ContextSize.UpTo)?.maxTokens ?: ContextSize.AUTO_CEILING}, the training context): no memory figure to fit to"
                }

                else -> {
                    fun num(key: String) = fit[key]?.jsonPrimitive?.long
                    val budget = num("budget_mib")
                    val used = num("used_max_mib") ?: num("used_min_mib")
                    // n_ctx_cap is per slot (several slots each get it); the other figures are totals
                    if (nCtx.toLong() == (num("n_ctx_cap") ?: 0) * (num("n_streams") ?: 1)) {
                        if (num("n_ctx_cap") == num("n_ctx_train")) {
                            "the training context fits: $used MiB of $budget MiB available"
                        } else {
                            "the ceiling of ${num("n_ctx_cap")} tokens fits: $used MiB of $budget MiB available"
                        }
                    } else {
                        "fitted to $budget MiB available: ${num("used_min_mib")} MiB at ${num("n_ctx_min")} tokens, " +
                            "${num("used_max_mib")} MiB at ${num("n_ctx_max")}"
                    }
                }
            }
        return nCtx to reason
    }

    private val shutdownScope = CoroutineScope(SupervisorJob() + ioDispatcher)
}

private const val BYTES_PER_MIB = 1024L * 1024L
private const val NANOS_PER_MILLI = 1_000_000L
private const val SETTLE_WINDOW_NANOS = 3_000L * NANOS_PER_MILLI
private const val SETTLE_MAX_MILLIS = 2_000L
private const val SETTLE_STEP_MILLIS = 100L
private const val SETTLE_STILL_BYTES = 32L * BYTES_PER_MIB
