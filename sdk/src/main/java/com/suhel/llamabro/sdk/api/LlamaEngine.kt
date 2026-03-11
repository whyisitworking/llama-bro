package com.suhel.llamabro.sdk.api

import com.suhel.llamabro.sdk.internal.LlamaEngineImpl

interface LlamaEngine : AutoCloseable {
    fun createSession(sessionConfig: SessionConfig): LlamaSession

    companion object {
        fun start(engineConfig: EngineConfig): LlamaEngine {
            return LlamaEngineImpl(engineConfig)
        }
    }
}

data class EngineConfig(
    val modelPath: String,
    val useMmap: Boolean,
    val useMlock: Boolean,
    val threads: Int,
    val promptFormat: PromptFormat
)
