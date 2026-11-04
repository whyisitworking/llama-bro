package com.suhel.llamabro

import android.content.Context
import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime

/**
 * The SDK's entry point: one per process (design §5, D6). Models are loaded from it as independent handles.
 */
public class LlamaBro internal constructor(private val runtime: LlamaBroRuntime) {

    /**
     * Loads a model and starts its worker. Runs on `Dispatchers.IO`.
     * @throws LlamaBroException.ModelLoadFailed
     */
    public suspend fun loadModel(source: ModelSource, configure: ModelConfig.Builder.() -> Unit = {}): Model =
        runtime.load(source, ModelConfig.Builder().apply(configure))

    /** The backend devices ggml loaded (CPU in P1). */
    public val devices: List<DeviceInfo> get() = runtime.devices()

    /** Implement on your `Application` to configure the runtime. */
    public interface ConfigProvider {
        public val llamaBroConfig: LlamaBroConfig
    }

    public companion object {
        @Volatile
        private var instance: LlamaBro? = null

        /**
         * The process's runtime. Config comes from `(context.applicationContext as? ConfigProvider)`, else defaults.
         * @throws IllegalStateException if the native runtime was already initialised with another config.
         */
        @JvmStatic
        public fun getInstance(context: Context): LlamaBro = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val config = (app as? ConfigProvider)?.llamaBroConfig ?: LlamaBroConfig()
                // no backend_dir: the backends load by name from the APK, extracted or not (findings F25)
                LlamaBro(LlamaBroRuntime(JniEngine, app.noBackupFilesDir, null, config))
            }.also { instance = it }
        }
    }
}

/** A ggml backend device, from `lb_runtime_devices`. */
public data class DeviceInfo(
    public val name: String,
    public val description: String,
    public val type: String,
    public val memoryFree: Long,
    public val memoryTotal: Long,
)
