package com.suhel.llamabro.internal

import com.suhel.llamabro.Backend
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DeviceInfo
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.FileSource
import com.suhel.llamabro.FitEstimate
import com.suhel.llamabro.LlamaBroConfig
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.LoadProgress
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.RemoteModelSource
import com.suhel.llamabro.spi.Engine
import com.suhel.llamabro.spi.NativeError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * The process-wide runtime: initialises the native runtime on first use, off the caller's thread
 * (it costs about 13 ms on the phone, F48), routes native logs, settles memory between loads, and wires the classes
 * that load models ([DeviceFallback]), estimate fit ([FitEstimator]) and own the media folder ([MediaFolders]).
 */
internal class LlamaBroRuntime(
    private val engine: Engine,
    private val noBackupDir: File,
    private val backendDir: String?,
    private val config: LlamaBroConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Backend artifacts on the classpath; runs once per init attempt, on [ioDispatcher]. */
    private val discoverBackends: () -> BackendDiscovery.Result = { BackendDiscovery.Result.None },
    /** Android's memory at load time, for `ContextSize.Auto`; null without it (the default context). */
    private val memorySnapshot: () -> MemoryBudget.Snapshot? = { null },
    /** Where lines go when the app's [LlamaBroConfig.logger] is null (logcat on Android, D66). */
    private val defaultLogger: Logger? = null,
    /** This process's id (`Process.myPid()`), which names its media folder. */
    processId: Int = 0,
    /** Whether a process runs: an app sees `/proc/<pid>` of its own processes, which are all that use the folder. */
    processAlive: (Int) -> Boolean = { File("/proc/$it").exists() },
) {
    private val initLock = Mutex()

    /**
     * One load at a time, from reading the free memory to the model existing: two loads at once would both fit
     * `ContextSize.Auto` to the same free memory, and together take about twice it.
     */
    private val loadLock = Mutex()

    /** `noBackupFilesDir/llamabro/slots/`. */
    val slotsDir: File get() = File(noBackupDir, "llamabro/slots")

    private val media = MediaFolders(noBackupDir, processId, processAlive)

    /** How each model reads its attachments (D84). */
    private val mediaIo = MediaIo(engine::readFd, ioDispatcher)

    private val fallback =
        DeviceFallback(engine, ioDispatcher, slotsDir, media.dir, mediaIo, ::budgetFor, ::shutdown)

    private val fitEstimator = FitEstimator(engine, slotsDir, media.dir, ::budgetFor)

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
        // listener before native init returns ALREADY_INITIALIZED; W2's create() refuses such a config in Kotlin first
        // (D70).
        attachLogger()
        media.prepare()
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
            // another LlamaBroConfig was used first in this process, a misuse
            check(status != NativeError.ALREADY_INITIALIZED) {
                "LlamaBro is already initialized with a different LlamaBroConfig: ${ErrorMapping.nativeMessage(error)}"
            }
            throw LlamaBroException.InvalidRequest("LlamaBro runtime init failed: ${ErrorMapping.nativeMessage(error)}")
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
                devices = (o["devices"] as? JsonArray)?.map { d -> d.jsonPrimitive.content }.orEmpty(),
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
        val listener = builder.progressListener
        return coroutineScope {
            // progress arrives on the loading (or downloading) thread; it's handed to the caller's context, keeping the
            // latest value
            val progress =
                listener?.let { l ->
                    Channel<LoadProgress>(Channel.CONFLATED).also { ch -> launch { for (p in ch) l(p) } }
                }
            try {
                // before a fetch, so that a runtime config conflict fails before a long download
                ensureInit()
                // a remote source is fetched first, then its files load like any local file (D64, D75); a cancel
                // during the fetch is the fetch's to handle, and no load starts
                val local =
                    if (source is RemoteModelSource) {
                        source.fetch { d -> progress?.trySend(LoadProgress.Fetching(d)) }.also {
                            requireLocal(it, "fetch")
                            builder.validateSource(it)
                        }
                    } else {
                        source
                    }
                loadLock.withLock {
                    // a model being closed still holds its weights and KV cache until its native close returns
                    synchronized(closing) { closing.toList() }.awaitAll()
                    awaitMemorySettled()
                    val backends = withContext(ioDispatcher) { nativeBackends() } + discoveryFailures
                    fallback.loadOnFirstDevice(local, builder, backends) { p ->
                        progress?.trySend(LoadProgress.Loading(p))
                    }
                }
            } finally {
                progress?.close()
            }
        }
    }

    /**
     * Projects the first device a load would attempt, without opening model weights. A remote source lends header
     * stubs for the estimate and removes them after it, also when it throws (D75); nothing else is downloaded.
     */
    suspend fun estimateFit(source: ModelSource, builder: ModelConfig): FitEstimate {
        builder.validateSource(source)
        if (source is RemoteModelSource) {
            return source.withHeaders { local ->
                requireLocal(local, "withHeaders")
                estimateFit(local, builder)
            }
        }
        ensureInit()
        synchronized(closing) { closing.toList() }.awaitAll()
        return withContext(ioDispatcher) {
            fitEstimator.estimate(source, builder, nativeBackends() + discoveryFailures)
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

    private val shutdownScope = CoroutineScope(SupervisorJob() + ioDispatcher)
}

private const val NANOS_PER_MILLI = 1_000_000L
private const val SETTLE_WINDOW_NANOS = 3_000L * NANOS_PER_MILLI
private const val SETTLE_MAX_MILLIS = 2_000L
private const val SETTLE_STEP_MILLIS = 100L
private const val SETTLE_STILL_BYTES = 32L * BYTES_PER_MIB

/** What a [RemoteModelSource] hands back must be local files: a second remote hop isn't followed. */
private fun requireLocal(local: ModelSource, by: String) {
    check(local is FileSource) {
        "RemoteModelSource.$by must give local files (ModelSource.file), not ${local::class.java.name}"
    }
}
