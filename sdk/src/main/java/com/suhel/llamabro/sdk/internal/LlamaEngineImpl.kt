package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.api.LlamaEngine
import com.suhel.llamabro.sdk.api.LlamaError
import com.suhel.llamabro.sdk.api.LlamaSession
import com.suhel.llamabro.sdk.api.ModelConfig
import com.suhel.llamabro.sdk.api.SessionConfig

internal class LlamaEngineImpl(config: ModelConfig) : LlamaEngine {

    private val promptFormat = config.promptFormat

    private val ptr: Long = try {
        Jni.create(
            NativeCreateParams(
                modelPath = config.modelPath,
                useMmap = config.useMmap,
                useMlock = config.useMlock,
                threads = config.threads,
            )
        )
    } catch (e: RuntimeException) {
        throw mapNativeError(e)
    }

    override fun createSession(config: SessionConfig): LlamaSession {
        return LlamaSessionImpl(ptr, promptFormat, config)
    }

    override fun close() {
        Jni.destroy(ptr)
    }

    // ── JNI params ───────────────────────────────────────────────────────────

    class NativeCreateParams(
        val modelPath: String,
        val useMmap: Boolean,
        val useMlock: Boolean,
        val threads: Int,
    )

    private object Jni {
        @JvmStatic external fun create(params: NativeCreateParams): Long
        @JvmStatic external fun destroy(ptr: Long)
    }
}
