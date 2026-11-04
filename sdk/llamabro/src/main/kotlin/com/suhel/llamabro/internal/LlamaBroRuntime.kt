package com.suhel.llamabro.internal

import com.suhel.llamabro.DeviceInfo
import com.suhel.llamabro.LlamaBroConfig
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import kotlinx.coroutines.CancellationException
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

/** The process-wide runtime (design §5): initialises the native runtime once and loads models. */
internal class LlamaBroRuntime(
    private val engine: Engine,
    private val noBackupDir: File,
    backendDir: String?,
    private val config: LlamaBroConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        val error = engine.init(
            buildJsonObject {
                backendDir?.let { put("backend_dir", it) }
                put("log_level", config.logLevel.name.lowercase())
            }.toString(),
        )
        if (error != null) {
            val status = runCatching { (Json.parseToJsonElement(error) as JsonObject)["status"]?.jsonPrimitive?.content?.toInt() }.getOrNull()
            // LB_ERR_ALREADY_INITIALIZED (2): another LlamaBroConfig was used first in this process (design §5)
            throw IllegalStateException(
                if (status == 2) "LlamaBro is already initialized with a different LlamaBroConfig: ${ErrorMapping.nativeMessage(error)}"
                else "LlamaBro runtime init failed: ${ErrorMapping.nativeMessage(error)}",
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

    fun devices(): List<DeviceInfo> = (Json.parseToJsonElement(engine.devices()) as JsonArray).map {
        val o = it as JsonObject
        DeviceInfo(
            name = o.getValue("name").jsonPrimitive.content,
            description = o.getValue("description").jsonPrimitive.content,
            type = o.getValue("type").jsonPrimitive.content,
            memoryFree = o.getValue("memory_free").jsonPrimitive.long,
            memoryTotal = o.getValue("memory_total").jsonPrimitive.long,
        )
    }

    suspend fun load(source: ModelSource, builder: ModelConfig.Builder): Model {
        // The native load can't be interrupted, and withContext drops its result if the caller was cancelled
        // meanwhile (prompt cancellation). So the model is recorded as soon as it exists and closed on cancellation,
        // instead of leaking its weights and KV cache.
        var loaded: Model? = null
        try {
            withContext(ioDispatcher + NonCancellable) {
                slotsDir.mkdirs()
                val configJson = builder.toJson(source, slotsDir.path + "/")
                val engineModel = try {
                    engine.load(configJson) { }
                } catch (e: NativeError) {
                    throw LlamaBroException.ModelLoadFailed(ErrorMapping.nativeMessage(e.errorJson))
                }
                loaded = Model(engineModel)
            }
            currentCoroutineContext().ensureActive()
            return loaded!!
        } catch (e: CancellationException) {
            loaded?.let { m -> withContext(ioDispatcher + NonCancellable) { m.close() } }
            throw e
        }
    }
}
