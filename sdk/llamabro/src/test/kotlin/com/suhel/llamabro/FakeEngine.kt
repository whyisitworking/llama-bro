package com.suhel.llamabro

import com.suhel.llamabro.internal.Engine
import com.suhel.llamabro.internal.EngineModel
import com.suhel.llamabro.internal.EngineRequest
import com.suhel.llamabro.internal.LlamaBroRuntime
import com.suhel.llamabro.internal.NativeError
import com.suhel.llamabro.internal.RequestSink
import java.io.File
import java.nio.file.Files

/** Stands in for the native layer (JniEngine) in JVM tests. Records what the Kotlin layer sends. */
internal class FakeEngine : Engine {
    val initConfigs = mutableListOf<String>()
    var initResult: (String) -> String? = { null }
    var logListener: ((Int, String) -> Unit)? = null
    val loadConfigs = mutableListOf<String>()

    /** `setLog` and `init` in the order they were called (D66: the logger goes first). */
    val calls = mutableListOf<String>()

    /** Lines `init` emits through the log listener, as native init's backend loading does. */
    var initLines: List<Pair<Int, String>> = emptyList()
    var devicesJson = """[{"name":"CPU","description":"cpu","type":"cpu","memory_free":1,"memory_total":2}]"""
    val model = FakeModel()

    /** The model each load returns; [model] unless a test loads several. */
    var makeModel: () -> FakeModel = { model }

    /** Reported through onProgress during each load, like llama.cpp's load progress. */
    var progress: List<Float> = emptyList()

    /** Runs after each [progress] step the load went on from, on the loading thread: block here to model a slow read. */
    var onStep: (Float) -> Unit = {}

    /** The step whose onProgress said stop, as the native load stops at that tick (D67); null if none did. */
    @Volatile var stoppedAt: Float? = null

    override fun init(configJson: String): String? {
        calls += "init"
        initConfigs += configJson
        initLines.forEach { (level, text) -> logListener?.invoke(level, text) }
        return initResult(configJson)
    }

    override fun setLog(listener: ((level: Int, text: String) -> Unit)?) {
        calls += "setLog"
        logListener = listener
    }

    override fun devices(): String = devicesJson

    /** `lb_runtime_backends`; null = the [Engine] default (the CPU alone). */
    var backendsJson: String? = null

    override fun backends(): String = backendsJson ?: super.backends()

    /** Each `lb_fit_estimate` config; the answer is the [Engine] default (memory unreadable). */
    val fitConfigs = mutableListOf<String>()

    override fun fitEstimate(configJson: String): String {
        fitConfigs += configJson
        return super.fitEstimate(configJson)
    }

    /** A load error for a config (the C API's error JSON), or null to load. */
    var loadError: (String) -> String? = { null }

    override fun load(configJson: String, onProgress: (Float) -> Boolean): EngineModel {
        loadConfigs += configJson
        loadError(configJson)?.let { throw NativeError(5, it) }
        for (p in progress) {
            if (!onProgress(p)) {
                stoppedAt = p
                throw NativeError(NativeError.CANCELLED, """{"error":"model load cancelled"}""")
            }
            onStep(p)
        }
        return makeModel()
    }
}

internal class FakeModel : EngineModel {
    override var infoJson: String = DEFAULT_INFO
    val requests = mutableListOf<FakeRequest>()

    /** Runs on the calling thread when a request starts: script the reply here. */
    var onRequest: (FakeRequest) -> Unit = {}

    @Volatile var closed = false

    /** Runs at the start of close(), on the closing thread: block here to model a slow native shutdown. */
    var onClose: () -> Unit = {}

    override fun request(endpoint: String, paramsJson: String?, body: ByteArray, sink: RequestSink): EngineRequest {
        val r = FakeRequest(endpoint, paramsJson, String(body, Charsets.UTF_8), sink)
        synchronized(requests) { requests += r }
        onRequest(r)
        return r
    }

    /** Like lb_model_free: every unfinished request gets its terminal event before this returns. */
    override fun close() {
        onClose()
        closed = true
        synchronized(requests) { requests.toList() }.forEach { it.cancelled() }
    }

    companion object {
        const val DEFAULT_INFO = """{"n_ctx":8192,"n_ctx_train":262144,"n_params":4000000000,"size":2800000000,
            "desc":"qwen35 4B Q4_K - Medium","metadata":{"general.architecture":"qwen35","qwen35.nextn_predict_layers":"1"},
            "props":{"total_slots":1,"chat_template":"{{ messages }}","modalities":{"vision":true,"video":false,"audio":false},
            "chat_template_caps":{"supports_tools":true,"supports_parallel_tool_calls":true,"supports_system_role":true}}}"""
    }
}

internal class FakeRequest(val endpoint: String, val paramsJson: String?, val body: String, val sink: RequestSink) :
    EngineRequest {
    @Volatile var cancelCount = 0

    @Volatile var freed = false

    @Volatile var terminal = false

    /** Like the native request: a cancel is followed by the CANCELLED terminal event. */
    var terminateOnCancel = true

    fun chunk(data: String, status: Int = 200) = sink.onChunk(status, data.toByteArray(Charsets.UTF_8))

    fun done() {
        if (markTerminal()) sink.onDone()
    }

    fun cancelled() {
        if (markTerminal()) sink.onCancelled()
    }

    private fun markTerminal(): Boolean =
        synchronized(this) {
            if (terminal) {
                false
            } else {
                terminal = true
                true
            }
        }

    override fun cancel() {
        cancelCount++
        if (terminateOnCancel) cancelled()
    }

    override fun free() {
        check(terminal) { "free() before the terminal event" }
        freed = true
    }
}

internal fun tempDir(): File = Files.createTempDirectory("llamabro-test").toFile()

internal fun testLlamaBro(
    engine: FakeEngine = FakeEngine(),
    config: LlamaBroConfig = LlamaBroConfig.Default,
): LlamaBro = LlamaBro(LlamaBroRuntime(engine, tempDir(), "/backends", config))
