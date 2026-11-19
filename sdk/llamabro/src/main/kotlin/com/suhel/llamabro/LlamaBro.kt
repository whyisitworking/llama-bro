package com.suhel.llamabro

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.suhel.llamabro.internal.BackendDiscovery
import com.suhel.llamabro.internal.JniEngine
import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.MemoryBudget
import com.suhel.llamabro.spi.BackendInstaller
import com.suhel.llamabro.spi.BackendOptions
import com.suhel.llamabro.spi.Engine
import com.suhel.llamabro.spi.LogcatLogger
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
     *     onProgress { p -> if (p is LoadProgress.Loading) progress.value = p.fraction }
     * }
     * ```
     * Cancelling the calling coroutine stops the load while it reads weights (the model's, a draft's, an image
     * encoder's): it ends at the next progress tick, frees what it read and throws `CancellationException`. The stages
     * after the last tick finish first (creating the context, warm-up, compiling GPU kernels, opening an NPU
     * session; with an image encoder or a draft, the text model's context and warm-up come before their stages, and a
     * cancel then stops at the next stage's tick). The model they produce is then freed and `CancellationException`
     * thrown, so a cancelled call leaves nothing open either way (D67). A device or encoder fallback or a
     * [MemoryCheck.Warn] retry isn't started after a cancel.
     *
     * A [RemoteModelSource] is fetched first ([LoadProgress.Fetching]), then its local files load as above; a cancel
     * during the fetch starts no load (D75).
     *
     * @throws LlamaBroException.ModelLoadFailed
     * @throws LlamaBroException.DeviceUnavailable with [DevicePolicy.Only] when no device of that kind could load it
     *   (or none is in the app), or when every device [DevicePolicy.Auto] or [DevicePolicy.Prefer] tried failed.
     * @throws LlamaBroException.InvalidRequest if the runtime was already initialised in this process with another
     *   [LlamaBroConfig].
     * @throws Exception whatever a [RemoteModelSource]'s [RemoteModelSource.fetch] throws, unchanged: for the hub's
     *   `hub.model(…)` a `HubException` (`Network` when it is offline with nothing installed, `Unauthorized`,
     *   `NotFound`, `InsufficientStorage`), so catch those where the source can fail.
     */
    public suspend fun loadModel(source: ModelSource, configure: ModelConfig.() -> Unit = {}): Model =
        runtime.load(source, ModelConfig().apply(configure))

    /**
     * Projects the chosen device's memory and context from GGUF headers without loading weights. A
     * [RemoteModelSource] lends its headers through [RemoteModelSource.withHeaders] and downloads no weights (D75).
     *
     * @throws Exception whatever a [RemoteModelSource]'s [RemoteModelSource.withHeaders] throws, unchanged: for the
     *   hub's `hub.model(…)` a `HubException` (`Network` when it is offline with nothing installed, `Unauthorized`,
     *   `NotFound`).
     */
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
         * The process's runtime (D70). The first call creates it; a later call with the same settings (log level, NPU
         * polling) returns the same object, so a DI container can hold it as a singleton, and the first call's logger
         * stays in use (D106). The native runtime exists once per process, so different settings throw at once,
         * before anything is built.
         *
         * Runs no native code, so it's cheap on the main thread: the native runtime starts in the first [loadModel]
         * or [devices].
         *
         * @throws LlamaBroException.InvalidRequest if the process's runtime was created with other settings.
         */
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
                        discoverBackends = { manifestBackends(app, BackendOptions(config.npuPolling)) },
                        memorySnapshot = { memorySnapshot(app) },
                        defaultLogger = LogcatLogger("llamabro"),
                        processId = Process.myPid(),
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
            if (!sameSettings(checkNotNull(createdConfig), config)) {
                throw LlamaBroException.InvalidRequest(
                    "LlamaBro.create: the runtime already exists with $createdConfig; it can't be created again with " +
                        "$config. The native runtime is one per process: create the config once and share the object.",
                )
            }
            return existing
        }

        // the logger is not a setting: a second create with a new logger lambda gets the runtime, which keeps the first
        private fun sameSettings(a: LlamaBroConfig, b: LlamaBroConfig): Boolean =
            a.logLevel == b.logLevel && a.npuPolling == b.npuPolling

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
        public fun withEngine(
            engine: Engine,
            filesDir: File,
            config: LlamaBroConfig = LlamaBroConfig.Default,
        ): LlamaBro = LlamaBro(LlamaBroRuntime(engine, filesDir, null, config))

        /**
         * A [LlamaBro] over the real native core built for this machine, for other modules' host end-to-end tests
         * (`llamabro-typed`'s `HostTypedTest`). [libDir] holds the host build's libraries, which must also be on
         * `java.library.path`.
         */
        @InternalLlamaBroApi
        public fun withHostEngine(
            libDir: String,
            filesDir: File,
            config: LlamaBroConfig = LlamaBroConfig.Default,
        ): LlamaBro = LlamaBro(LlamaBroRuntime(JniEngine, filesDir, libDir, config))
    }
}

/** A ggml backend device, from `lb_runtime_devices`. */
public data class DeviceInfo internal constructor(
    public val name: String,
    public val description: String,
    /** The chip kind, from the backend that added it (D29, D32); ggml's own type calls the NPU a GPU (F32). */
    public val kind: DeviceKind,
    /** Bytes, as ggml reports them; not a usable budget on a phone (F33, F64). */
    public val memoryFree: Long,
    public val memoryTotal: Long,
)

/** The backend artifacts announced in [app]'s merged manifest (D24): one `PackageManager` call, no classpath scan. */
private fun manifestBackends(app: Context, options: BackendOptions): BackendDiscovery.Result {
    @Suppress("DEPRECATION") // the flags overload needs API 33
    val bundle =
        app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
            ?: return BackendDiscovery.Result.None

    @Suppress("DEPRECATION") // Bundle.get(String) is the only untyped getter
    val metaData = bundle.keySet().associateWith { bundle.get(it) }
    return BackendDiscovery.discover(metaData) { className ->
        (Class.forName(className).getDeclaredConstructor().newInstance() as BackendInstaller).install(app, options)
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
