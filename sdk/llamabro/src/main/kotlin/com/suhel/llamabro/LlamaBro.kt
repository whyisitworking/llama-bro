package com.suhel.llamabro

import android.content.Context
import com.suhel.llamabro.internal.Engine
import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import java.io.File

/**
 * The SDK's entry point: one per process (design §5, D6). Models are loaded from it as independent handles.
 */
public class LlamaBro internal constructor(private val runtime: LlamaBroRuntime) {

    /**
     * Loads a model and starts its worker. Runs on `Dispatchers.IO`; first waits for any model still closing, so
     * two never share memory. The first call in the process also initialises the native runtime.
     *
     * ```
     * val model = llama.loadModel(ModelSource.file(path)) {
     *     contextSize = ContextSize.Fixed(4096)
     *     onProgress { fraction -> progress.value = fraction }
     * }
     * ```
     * @throws LlamaBroException.ModelLoadFailed
     * @throws LlamaBroException.InvalidRequest if the runtime was already initialised in this process with another
     *   [LlamaBroConfig].
     */
    public suspend fun loadModel(source: ModelSource, configure: ModelConfig.() -> Unit = {}): Model =
        runtime.load(source, ModelConfig().apply(configure))

    /** The backend devices ggml loaded (CPU in P1). Initialises the native runtime if needed; runs on `Dispatchers.IO`. */
    public suspend fun devices(): List<DeviceInfo> = runtime.devices()

    /** Implement on your `Application` to configure the runtime. */
    public interface ConfigProvider {
        /** Read once, by the first [getInstance]. */
        public val llamaBroConfig: LlamaBroConfig
    }

    public companion object {
        @Volatile
        private var instance: LlamaBro? = null

        /**
         * The process's runtime. Config comes from `(context.applicationContext as? ConfigProvider)`, else defaults.
         * Runs no native code, so it's cheap on the main thread: the native runtime starts in the first [loadModel]
         * or [devices], which also reports a config conflict.
         */
        @JvmStatic
        public fun getInstance(context: Context): LlamaBro = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val config = (app as? ConfigProvider)?.llamaBroConfig ?: LlamaBroConfig.Default
                // no backend_dir: the backends load by name from the APK, extracted or not (findings F25)
                LlamaBro(LlamaBroRuntime(JniEngine, app.noBackupFilesDir, null, config))
            }.also { instance = it }
        }

        /**
         * A [LlamaBro] over another engine, for `llamabro-testing`'s `FakeLlamaBro` (D27). [filesDir] stands in for
         * `noBackupFilesDir` (saved slots go under it).
         */
        @InternalLlamaBroApi
        @JvmStatic
        public fun withEngine(engine: Engine, filesDir: File, config: LlamaBroConfig = LlamaBroConfig.Default): LlamaBro =
            LlamaBro(LlamaBroRuntime(engine, filesDir, null, config))
    }
}

/** A ggml backend device, from `lb_runtime_devices`. */
public class DeviceInfo internal constructor(
    public val name: String,
    public val description: String,
    /** ggml's device type: `cpu`, `gpu`, `igpu`, `accel` or `meta`. */
    public val type: String,
    /** Bytes, as ggml reports them. */
    public val memoryFree: Long,
    public val memoryTotal: Long,
) {
    override fun equals(other: Any?): Boolean = other is DeviceInfo && parts() == other.parts()
    override fun hashCode(): Int = parts().hashCode()
    override fun toString(): String =
        "DeviceInfo(name=$name, description=$description, type=$type, memoryFree=$memoryFree, memoryTotal=$memoryTotal)"

    private fun parts() = listOf(name, description, type, memoryFree, memoryTotal)
}
