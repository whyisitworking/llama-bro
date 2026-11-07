package com.suhel.llamabro.internal

import com.suhel.llamabro.Backend
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.FileSource
import com.suhel.llamabro.DeviceInfo
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.LoadReport
import com.suhel.llamabro.Speculative
import com.suhel.llamabro.LlamaBroConfig
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
) {
    private val initLock = Mutex()
    @Volatile private var initialized = false

    /** Native shutdowns still running ([Model.close] returns before they finish). */
    private val closing = mutableSetOf<Deferred<Unit>>()

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
        val discovered = discoverBackends()
        val error = engine.init(
            buildJsonObject {
                backendDir?.let { put("backend_dir", it) }
                put("log_level", config.logLevel.name.lowercase())
                if (discovered.found.isNotEmpty()) put("backends", discovered.toJson())
            }.toString(),
        )
        discoveryFailures = discovered.failed
        if (error != null) {
            val status = runCatching { (Json.parseToJsonElement(error) as JsonObject)["status"]?.jsonPrimitive?.content?.toInt() }.getOrNull()
            // LB_ERR_ALREADY_INITIALIZED (2): another LlamaBroConfig was used first in this process (design §5)
            throw LlamaBroException.InvalidRequest(
                if (status == 2) {
                    "LlamaBro is already initialized with a different LlamaBroConfig: ${ErrorMapping.nativeMessage(error)}"
                } else {
                    "LlamaBro runtime init failed: ${ErrorMapping.nativeMessage(error)}"
                },
            )
        }
        config.logger?.let { logger ->
            engine.setLog { level, text ->
                if (level >= config.logLevel.ordinal) {
                    logger.log(LogLevel.entries[level.coerceIn(0, LogLevel.entries.size - 1)], text.trimEnd('\n'))
                }
            }
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
                kind = kindOfDevice[name] ?: if (o.getValue("type").jsonPrimitive.content == "cpu") DeviceKind.Cpu else DeviceKind.Gpu,
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
                synchronized(closing) { closing -= done }
                done.complete(Unit)
            }
        }
        return done
    }

    suspend fun load(source: ModelSource, builder: ModelConfig): Model {
        ensureInit()
        // a model being closed still holds its weights and KV cache until its native close returns
        synchronized(closing) { closing.toList() }.awaitAll()
        val listener = builder.progressListener
        return coroutineScope {
            // progress arrives on the loading thread; it's handed to the caller's context, keeping the latest value
            val progress = listener?.let { l ->
                Channel<Float>(Channel.CONFLATED).also { ch -> launch { for (p in ch) l(p) } }
            }
            try {
                val backends = withContext(ioDispatcher) { nativeBackends() } + discoveryFailures
                loadOnFirstDevice(source, builder, DeviceResolver.plan(builder.devicePolicy, backends)) { p -> progress?.trySend(p) }
            } finally {
                progress?.close()
            }
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
        onProgress: (Float) -> Unit,
    ): Model {
        val attempts = plan.skipped.toMutableList()
        var lastError: LlamaBroException.ModelLoadFailed? = null
        // drafting on the GPU aborts the process, which no try can catch: skip it (F67, F80)
        val drafts = builder.speculative is Speculative.Mtp || builder.speculative is Speculative.Ngram
        for (candidate in plan.candidates) {
            currentCoroutineContext().ensureActive()
            if (drafts && candidate.kind == DeviceKind.Gpu) {
                attempts += LoadReport.Attempt(candidate.kind, candidate.deviceName, MtpPolicy.GPU_ABORTS)
                continue
            }
            try {
                return loadModel(source, builder, candidate, attempts.toList(), onProgress)
            } catch (e: LlamaBroException.ModelLoadFailed) {
                // no device fixes a missing file, and every device shares the phone's memory
                if (e.reason == LlamaBroException.ModelLoadFailed.Reason.FileNotFound ||
                    e.reason == LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory
                ) {
                    throw e
                }
                attempts += LoadReport.Attempt(candidate.kind, candidate.deviceName, e.message ?: "load failed")
                lastError = e
            }
        }
        val policy = builder.devicePolicy
        if (policy is DevicePolicy.Only || lastError == null) {
            val kind = (policy as? DevicePolicy.Only)?.kind ?: DeviceKind.Cpu
            throw LlamaBroException.DeviceUnavailable(
                kind,
                "no ${kind.name} device could load the model: " +
                    attempts.joinToString("; ") { "${it.deviceName ?: it.kind.name}: ${it.reason}" },
                lastError,
            )
        }
        throw lastError  // the last device tried's own reason (the CPU's, unless Prefer(Cpu) put it first)
    }

    private suspend fun loadModel(
        source: ModelSource,
        builder: ModelConfig,
        candidate: DeviceResolver.Candidate,
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
                val budget = if (builder.contextSize == ContextSize.Auto || builder.contextSize is ContextSize.UpTo) {
                    memorySnapshot()?.let { m ->
                        // files loaded beside the model that upstream's projection leaves out
                        val mmproj = (source as? FileSource)?.mmproj?.let { File(it).length() } ?: 0L
                        val lora = builder.loraAdapters.sumOf { File(it.path).length() }
                        MemoryBudget.budgetMib(m.available, m.threshold, builder.ramCacheMib, mmproj + lora)
                    }
                } else {
                    null
                }
                val configJson = builder.toJson(source, slotsDir.path + "/", candidate.devices, budget)
                val engineModel = try {
                    engine.load(configJson, onProgress)
                } catch (e: NativeError) {
                    throw ErrorMapping.loadFailed(e)
                }
                val (contextSize, contextReason) = contextOf(engineModel.infoJson, builder.contextSize)
                val (speculative, speculativeReason) = speculativeOf(builder.speculative, candidate.kind)
                val report = LoadReport(
                    candidate.kind, candidate.deviceName, candidate.backend, attempts,
                    contextSize, contextReason, speculative, speculativeReason,
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

    /** What decodes and why (P2 T8): what the app set, or for [Speculative.Auto] why it is off on this chip. */
    private fun speculativeOf(requested: Speculative, kind: DeviceKind): Pair<Speculative, String> = when (requested) {
        Speculative.Off -> Speculative.Off to "off: set by the app"
        Speculative.Auto -> Speculative.Off to "off: ${MtpPolicy.reason(kind)}"
        is Speculative.Mtp -> requested to "MTP: set by the app"
        is Speculative.Ngram -> requested to (if (requested.then != null) "n-gram, then MTP: set by the app" else "n-gram: set by the app")
    }

    /** The loaded context and why (`lb_model_info`'s `n_ctx` and `ctx_fit`, D33). */
    private fun contextOf(infoJson: String, requested: ContextSize): Pair<Int, String> {
        val info = Json.parseToJsonElement(infoJson) as JsonObject
        val nCtx = info.getValue("n_ctx").jsonPrimitive.int
        val fit = info["ctx_fit"] as? JsonObject
        val reason = when {
            requested is ContextSize.Fixed -> "set by the app"
            fit == null -> "min(${(requested as? ContextSize.UpTo)?.maxTokens ?: ContextSize.AUTO_CEILING}, the training context): no memory figure to fit to"
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
