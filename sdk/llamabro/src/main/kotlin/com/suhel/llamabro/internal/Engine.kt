package com.suhel.llamabro.internal

/** The native layer as the Kotlin API sees it. [JniEngine] in production; a fake in JVM tests. */
internal interface Engine {
    /** null = ok, else the error JSON. */
    fun init(configJson: String): String?
    fun setLog(listener: ((level: Int, text: String) -> Unit)?)
    /** `lb_runtime_devices` JSON. */
    fun devices(): String
    /** Throws [NativeError]. */
    fun load(configJson: String, onProgress: (Float) -> Unit): EngineModel
}

internal interface EngineModel {
    val infoJson: String
    /** Throws [NativeError] if the request couldn't start. Callbacks may arrive before this returns. */
    fun request(endpoint: String, paramsJson: String?, body: ByteArray, sink: RequestSink): EngineRequest
    /** Cancels every request, waits for their terminal events, frees the model. */
    fun close()
}

internal interface EngineRequest {
    fun cancel()
    /** Only after the terminal event. */
    fun free()
}
