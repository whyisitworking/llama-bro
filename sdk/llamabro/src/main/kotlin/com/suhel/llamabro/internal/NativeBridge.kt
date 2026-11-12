package com.suhel.llamabro.internal

import com.suhel.llamabro.InternalLlamaBroApi

/**
 * JNI over the C API in `sdk/native/bridge/include/llamabro.h` (P1 T6). One function per `lb_*` call;
 * handles are the native pointers as `Long`. Callbacks arrive on native threads.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("llamabro")
    }

    /** `lb_runtime_init`. Returns null when it succeeded, else the error JSON (`{"error": "..."}`). */
    external fun runtimeInit(configJson: String): String?

    /** `lb_runtime_devices`: `[{"name","description","type","memory_free","memory_total"}]`. */
    external fun runtimeDevices(): String

    /** `lb_runtime_backends`: `[{"name","loaded","error","devices"}]`, `cpu` first. */
    external fun runtimeBackends(): String

    /** `lb_runtime_set_log`; null restores llama.cpp's default logging. */
    external fun runtimeSetLog(listener: LogListener?)

    /** `lb_model_load`. Blocks until loaded. Throws [NativeError] on failure. */
    external fun modelLoad(configJson: String, progress: ProgressListener?): Long

    /** `lb_fit_estimate`. Projects from headers and throws [NativeError] on failure. */
    external fun fitEstimate(configJson: String): String

    external fun modelInfo(model: Long): String

    /** `lb_model_request`. Throws [NativeError] if the request couldn't start. */
    external fun modelRequest(
        model: Long,
        endpoint: String,
        paramsJson: String?,
        body: ByteArray,
        sink: RequestSink,
    ): Long

    external fun requestCancel(request: Long)

    external fun requestFree(request: Long)

    external fun modelFree(model: Long)
}

internal fun interface ProgressListener {
    fun onProgress(progress: Float)
}

/** [level] is `lb_log_level`: 0 debug, 1 info, 2 warn, 3 error. */
internal fun interface LogListener {
    fun onLog(level: Int, text: String)
}

/**
 * A failed native call: [status] is `lb_status`, [errorJson] is `{"error": "..."}`, with `"code"` set for a
 * missing file (`file_not_found`) or too little memory (`insufficient_memory`). Public under the opt-in marker so
 * that an [Engine] in `llamabro-testing` can fail a load as the native side does.
 */
@InternalLlamaBroApi
public class NativeError(public val status: Int, public val errorJson: String) : RuntimeException(errorJson)
