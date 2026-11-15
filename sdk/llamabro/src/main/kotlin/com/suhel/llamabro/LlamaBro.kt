package com.suhel.llamabro

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import com.suhel.llamabro.internal.BackendDiscovery
import com.suhel.llamabro.internal.BackendInstaller
import com.suhel.llamabro.internal.Engine
import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.LogcatLogger
import com.suhel.llamabro.internal.MemoryBudget
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

    /**
     * The memory, in bytes, that [ContextSize.Auto] would let a model use if it loaded now with [configure]'s
     * settings (the prompt cache and [ModelConfig.memoryReserveMib] come out of it), before side files such as an
     * image encoder or LoRA adapters. Compare it with a model file's size for a quick fit label without reading the
     * file (F97); [estimateFit] gives the full projection.
     *
     * It moves with free memory, so it's a hint, not a promise. Null where the SDK can't read the phone's memory
     * (a test engine).
     *
     * @throws LlamaBroException.InvalidRequest if [ModelConfig.memoryReserveMib] is negative.
     */
    public suspend fun memoryBudgetBytes(configure: ModelConfig.() -> Unit = {}): Long? =
        runtime.memoryBudgetBytes(ModelConfig().apply(configure))

    /** The backend devices ggml loaded. Initialises the native runtime if needed; runs on `Dispatchers.IO`. */
    public suspend fun devices(): List<DeviceInfo> = runtime.devices()

    /**
     * Every backend and whether it loaded: `cpu` first, then each backend artifact on the classpath, with the reason
     * when one failed. Initialises the native runtime if needed; runs on `Dispatchers.IO`.
     */
    public suspend fun backends(): List<Backend> = runtime.backends()

    public companion object {
        private var created: LlamaBro? = null
        private var createdConfig: LlamaBroConfig? = null

        /**
         * The process's runtime (D70). The first call creates it; a later call with an equal [config] returns the
         * same object, so a DI container can hold it as a singleton. The native runtime exists once per process, so a
         * different config throws at once, before anything is built. [LlamaBroConfig] compares its logger by
         * reference: create the config once, not on each call.
         *
         * Runs no native code, so it's cheap on the main thread: the native runtime starts in the first [loadModel]
         * or [devices].
         *
         * @throws LlamaBroException.InvalidRequest if the process's runtime was created with another [config].
         */
        @JvmStatic
        @JvmOverloads
        public fun create(context: Context, config: LlamaBroConfig = LlamaBroConfig.Default): LlamaBro =
            create(config) {
                val app = context.applicationContext
                // no backend_dir: the backends load by name from the APK, extracted or not (findings F25)
                LlamaBro(
                    LlamaBroRuntime(
                        JniEngine,
                        app.noBackupFilesDir,
                        null,
                        config,
                        discoverBackends = { manifestBackends(app) },
                        memorySnapshot = { memorySnapshot(app) },
                        defaultLogger = LogcatLogger("llamabro"),
                    ),
                )
            }

        /**
         * The registry behind [create], free of `Context`. A refused config never reaches [build]: building attaches
         * the logger before native init (D66), and a second runtime would swap the process-wide native log listener.
         */
        @Synchronized
        internal fun create(config: LlamaBroConfig, build: () -> LlamaBro): LlamaBro {
            val existing = created
            if (existing == null) {
                return build().also {
                    created = it
                    createdConfig = config
                }
            }
            if (createdConfig != config) {
                throw LlamaBroException.InvalidRequest(
                    "LlamaBro.create: the runtime already exists with $createdConfig; it can't be created again with " +
                        "$config. The native runtime is one per process: create the config once and share the object.",
                )
            }
            return existing
        }

        /** Forgets the created runtime so a test can start clean. */
        @Synchronized
        internal fun resetCreatedForTest() {
            created = null
            createdConfig = null
        }

        /**
         * A [LlamaBro] over another engine, for `llamabro-testing`'s `FakeLlamaBro` (D27). [filesDir] stands in for
         * `noBackupFilesDir` (saved slots go under it).
         */
        @InternalLlamaBroApi
        @JvmStatic
        public fun withEngine(
            engine: Engine,
            filesDir: File,
            config: LlamaBroConfig = LlamaBroConfig.Default,
        ): LlamaBro = LlamaBro(LlamaBroRuntime(engine, filesDir, null, config))
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
    val bundle =
        app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
            ?: return BackendDiscovery.Result.None

    @Suppress("DEPRECATION") // Bundle.get(String) is the only untyped getter
    val metaData = bundle.keySet().associateWith { bundle.get(it) }
    return BackendDiscovery.discover(metaData) { className ->
        (Class.forName(className).getDeclaredConstructor().newInstance() as BackendInstaller).install(app)
    }
}

/**
 * Memory now (P2 T7, D33, D34): the kernel's `MemAvailable`, else Android's `availMem`, and the low-memory threshold.
 */
private fun memorySnapshot(app: Context): MemoryBudget.Snapshot? {
    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val kernel = runCatching { MemoryBudget.memAvailable(File("/proc/meminfo").readText()) }.getOrNull()
    return MemoryBudget.Snapshot(kernel ?: info.availMem, info.threshold)
}
