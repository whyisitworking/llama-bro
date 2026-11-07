package com.suhel.llamabro.internal

import com.suhel.llamabro.InternalLlamaBroApi

/**
 * The native layer as the Kotlin API sees it: [JniEngine] in production, a fake in tests. Public only for
 * `llamabro-testing` (D27); apps never implement it.
 */
@InternalLlamaBroApi
public interface Engine {
    /** null = ok, else the error JSON. */
    public fun init(configJson: String): String?
    public fun setLog(listener: ((level: Int, text: String) -> Unit)?)
    /** `lb_runtime_devices` JSON. */
    public fun devices(): String
    /** `lb_runtime_backends` JSON. The default is the CPU alone, loaded, for engines with no other backend. */
    public fun backends(): String = """[{"name":"cpu","loaded":true,"error":null,"devices":["CPU"]}]"""
    /** `lb_model_load`. A real engine throws on failure. */
    public fun load(configJson: String, onProgress: (Float) -> Unit): EngineModel
}

@InternalLlamaBroApi
public interface EngineModel {
    /** `lb_model_info` JSON. */
    public val infoJson: String
    /**
     * `lb_model_request`: [endpoint] is the C API's name for the route (`llamabro.h`). Callbacks may arrive
     * before this returns. A real engine throws if the request couldn't start.
     */
    public fun request(endpoint: String, paramsJson: String?, body: ByteArray, sink: RequestSink): EngineRequest
    /** Cancels every request, waits for their terminal events, frees the model. */
    public fun close()
}

@InternalLlamaBroApi
public interface EngineRequest {
    public fun cancel()
    /** Only after the terminal event. */
    public fun free()
}

/** A request's events: chunks of the HTTP body with its status, then exactly one of [onDone] or [onCancelled]. */
@InternalLlamaBroApi
public interface RequestSink {
    public fun onChunk(httpStatus: Int, data: ByteArray)
    public fun onDone()
    public fun onCancelled()
}
