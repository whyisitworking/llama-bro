package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.api.EngineConfig
import com.suhel.llamabro.sdk.api.LlamaEngine
import com.suhel.llamabro.sdk.api.LlamaSession
import com.suhel.llamabro.sdk.api.SessionConfig

internal class LlamaEngineImpl(engineConfig: EngineConfig) : LlamaEngine {
    private val promptFormat = engineConfig.promptFormat

    private val ptr = Jni.create(
        NativeCreateParams(
            modelPath = engineConfig.modelPath,
            useMmap = engineConfig.useMmap,
            useMlock = engineConfig.useMlock,
            threads = engineConfig.threads,
        )
    )

    override fun createSession(sessionConfig: SessionConfig): LlamaSession {
        return LlamaSessionImpl(ptr, promptFormat, sessionConfig)
    }

    override fun close() {
        Jni.destroy(ptr)
    }

    class NativeCreateParams(
        val modelPath: String,
        val useMmap: Boolean,
        val useMlock: Boolean,
        val threads: Int,
    )

    private object Jni {
        @JvmStatic
        external fun create(params: NativeCreateParams): Long

        @JvmStatic
        external fun destroy(ptr: Long)
    }
}
