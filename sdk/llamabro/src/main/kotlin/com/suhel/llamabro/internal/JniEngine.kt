package com.suhel.llamabro.internal

import java.util.concurrent.atomic.AtomicBoolean

internal object JniEngine : Engine {
    override fun init(configJson: String): String? = NativeBridge.runtimeInit(configJson)

    override fun setLog(listener: ((level: Int, text: String) -> Unit)?) {
        NativeBridge.runtimeSetLog(listener?.let { l -> LogListener { level, text -> l(level, text) } })
    }

    override fun devices(): String = NativeBridge.runtimeDevices()

    override fun backends(): String = NativeBridge.runtimeBackends()

    override fun fitEstimate(configJson: String): String = NativeBridge.fitEstimate(configJson)

    override fun load(configJson: String, onProgress: (Float) -> Boolean): EngineModel {
        val handle = NativeBridge.modelLoad(configJson) { onProgress(it) }
        return JniModel(handle)
    }
}

private class JniModel(private val handle: Long) : EngineModel {
    private val freed = AtomicBoolean(false)

    override val infoJson: String = NativeBridge.modelInfo(handle)

    override fun request(endpoint: String, paramsJson: String?, body: ByteArray, sink: RequestSink): EngineRequest {
        check(!freed.get()) { "model is closed" }
        return JniRequest(NativeBridge.modelRequest(handle, endpoint, paramsJson, body, sink))
    }

    override fun close() {
        if (freed.compareAndSet(false, true)) NativeBridge.modelFree(handle)
    }
}

private class JniRequest(private val handle: Long) : EngineRequest {
    override fun cancel() = NativeBridge.requestCancel(handle)

    override fun free() = NativeBridge.requestFree(handle)
}
