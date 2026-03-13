package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.internal.LlamaEngineImpl
import com.suhel.llamabro.sdk.internal.ProgressListener
import com.suhel.llamabro.sdk.model.LlamaError
import com.suhel.llamabro.sdk.model.LoadEvent
import com.suhel.llamabro.sdk.model.ModelConfig
import com.suhel.llamabro.sdk.model.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

interface LlamaEngine : AutoCloseable {

    fun createSession(sessionConfig: SessionConfig): LlamaSession

    fun createSessionFlow(sessionConfig: SessionConfig): Flow<LoadEvent<LlamaSession>>

    companion object {
        fun create(modelConfig: ModelConfig, listener: ProgressListener): LlamaEngine {
            return LlamaEngineImpl(modelConfig, listener)
        }

        fun createFlow(modelConfig: ModelConfig): Flow<LoadEvent<LlamaEngine>> = callbackFlow {
            System.loadLibrary("llama_bro")

            val listener = object : ProgressListener {
                override fun onProgress(progress: Float): Boolean {
                    trySend(LoadEvent.Loading(progress))
                    return isActive
                }
            }

            var engine: LlamaEngine? = null

            try {
                send(LoadEvent.Loading())
                engine = LlamaEngineImpl(modelConfig, listener)
                send(LoadEvent.Ready(engine))
            } catch (e: Exception) {
                val llamaError = e as? LlamaError
                    ?: LlamaError.NativeException(e.message ?: "Unknown", e)
                send(LoadEvent.Error(llamaError))
            }

            awaitClose {
                engine?.close()
            }
        }.flowOn(Dispatchers.IO)
    }
}
