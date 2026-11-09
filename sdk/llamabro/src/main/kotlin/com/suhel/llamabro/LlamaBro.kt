package com.suhel.llamabro

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.suhel.llamabro.internal.BackendDiscovery
import com.suhel.llamabro.internal.BackendInstaller
import com.suhel.llamabro.internal.MemoryBudget
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
     * @throws LlamaBroException.DeviceUnavailable with [DevicePolicy.Only] when no device of that kind could load it
     *   (or none is in the app), or when every device [DevicePolicy.Auto] or [DevicePolicy.Prefer] tried failed.
     * @throws LlamaBroException.InvalidRequest if the runtime was already initialised in this process with another
     *   [LlamaBroConfig].
     */
    public suspend fun loadModel(source: ModelSource, configure: ModelConfig.() -> Unit = {}): Model =
        runtime.load(source, ModelConfig().apply(configure))

    /** Projects the chosen device's memory and context from GGUF headers without loading weights. */
    public suspend fun estimateFit(source: ModelSource, configure: ModelConfig.() -> Unit = {}): FitEstimate =
        runtime.estimateFit(source, ModelConfig().apply(configure))

    /** The backend devices ggml loaded. Initialises the native runtime if needed; runs on `Dispatchers.IO`. */
    public suspend fun devices(): List<DeviceInfo> = runtime.devices()

    /**
     * Every backend and whether it loaded: `cpu` first, then each backend artifact on the classpath, with the reason
     * when one failed. Initialises the native runtime if needed; runs on `Dispatchers.IO`.
     */
    public suspend fun backends(): List<Backend> = runtime.backends()

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
                LlamaBro(
                    LlamaBroRuntime(
                        JniEngine, app.noBackupFilesDir, null, config,
                        discoverBackends = { manifestBackends(app) },
                        memorySnapshot = { memorySnapshot(app) },
                    ),
                )
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
    /** The chip kind, from the backend that added it (D29, D32); ggml's own type calls the NPU a GPU (F32). */
    public val kind: DeviceKind,
    /** Bytes, as ggml reports them; not a usable budget on a phone (F33, F64). */
    public val memoryFree: Long,
    public val memoryTotal: Long,
) {
    override fun equals(other: Any?): Boolean = other is DeviceInfo && parts() == other.parts()
    override fun hashCode(): Int = parts().hashCode()
    override fun toString(): String =
        "DeviceInfo(name=$name, description=$description, kind=$kind, memoryFree=$memoryFree, memoryTotal=$memoryTotal)"

    private fun parts() = listOf(name, description, kind, memoryFree, memoryTotal)
}

/** The backend artifacts announced in [app]'s merged manifest (D24): one `PackageManager` call, no classpath scan. */
private fun manifestBackends(app: Context): BackendDiscovery.Result {
    @Suppress("DEPRECATION") // the flags overload needs API 33
    val bundle = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        ?: return BackendDiscovery.Result.None
    @Suppress("DEPRECATION") // Bundle.get(String) is the only untyped getter
    val metaData = bundle.keySet().associateWith { bundle.get(it) }
    return BackendDiscovery.discover(metaData) { className ->
        (Class.forName(className).getDeclaredConstructor().newInstance() as BackendInstaller).install(app)
    }
}

/** Memory now (P2 T7, D33, D34): the kernel's `MemAvailable`, else Android's `availMem`, and the low-memory threshold. */
private fun memorySnapshot(app: Context): MemoryBudget.Snapshot? {
    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val kernel = runCatching { MemoryBudget.memAvailable(File("/proc/meminfo").readText()) }.getOrNull()
    return MemoryBudget.Snapshot(kernel ?: info.availMem, info.threshold)
}
