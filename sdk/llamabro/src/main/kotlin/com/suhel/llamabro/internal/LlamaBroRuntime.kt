package com.suhel.llamabro.internal

import com.suhel.llamabro.DeviceInfo
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

    private fun init() {
        val error = engine.init(
            buildJsonObject {
                backendDir?.let { put("backend_dir", it) }
                put("log_level", config.logLevel.name.lowercase())
            }.toString(),
        )
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
        val json = withContext(ioDispatcher) { engine.devices() }
        return (Json.parseToJsonElement(json) as JsonArray).map {
            val o = it as JsonObject
            DeviceInfo(
                name = o.getValue("name").jsonPrimitive.content,
                description = o.getValue("description").jsonPrimitive.content,
                type = o.getValue("type").jsonPrimitive.content,
                memoryFree = o.getValue("memory_free").jsonPrimitive.long,
                memoryTotal = o.getValue("memory_total").jsonPrimitive.long,
            )
        }
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
                loadModel(source, builder) { p -> progress?.trySend(p) }
            } finally {
                progress?.close()
            }
        }
    }

    private suspend fun loadModel(source: ModelSource, builder: ModelConfig, onProgress: (Float) -> Unit): Model {
        // The native load can't be interrupted, and withContext drops its result if the caller was cancelled
        // meanwhile (prompt cancellation). So the model is recorded as soon as it exists and closed on cancellation,
        // instead of leaking its weights and KV cache.
        var loaded: Model? = null
        try {
            withContext(ioDispatcher + NonCancellable) {
                slotsDir.mkdirs()
                val configJson = builder.toJson(source, slotsDir.path + "/")
                val engineModel = try {
                    engine.load(configJson, onProgress)
                } catch (e: NativeError) {
                    throw ErrorMapping.loadFailed(e)
                }
                loaded = Model(engineModel, this@LlamaBroRuntime::shutdown)
            }
            currentCoroutineContext().ensureActive()
            return loaded!!
        } catch (e: CancellationException) {
            // freed before the cancellation reaches the caller, as if the load never happened
            loaded?.let { m -> withContext(NonCancellable) { m.closeAndJoin() } }
            throw e
        }
    }

    private val shutdownScope = CoroutineScope(SupervisorJob() + ioDispatcher)
}
