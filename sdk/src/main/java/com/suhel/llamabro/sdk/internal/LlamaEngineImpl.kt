package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.LlamaEngine
import com.suhel.llamabro.sdk.LlamaSession

import com.suhel.llamabro.sdk.model.LlamaError
import com.suhel.llamabro.sdk.model.ResourceState
import com.suhel.llamabro.sdk.model.ModelConfig
import com.suhel.llamabro.sdk.model.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class LlamaEngineImpl(
    private val modelConfig: ModelConfig,
    listener: ProgressListener? = null,
) : LlamaEngine {
    private val enginePtr: Long = try {
        val params = NativeCreateParams(
            modelPath = modelConfig.modelPath,
            useMmap = modelConfig.useMmap,
            useMlock = modelConfig.useMlock,
            threads = modelConfig.threads,
        )

        if (listener != null) {
            Jni.createWithProgress(params, listener)
        } else {
            Jni.create(params)
        }
    } catch (e: RuntimeException) {
        throw mapNativeError(e)
    }

    override suspend fun createSession(sessionConfig: SessionConfig): LlamaSession =
        withContext(Dispatchers.IO) {
            LlamaSessionImpl(enginePtr, sessionConfig, modelConfig)
        }

    override fun createSessionFlow(sessionConfig: SessionConfig): Flow<ResourceState<LlamaSession>> =
        callbackFlow {
            var session: LlamaSession? = null

            try {
                send(ResourceState.Loading())
                session = createSession(sessionConfig)
                send(ResourceState.Success(session))
            } catch (e: Exception) {
                val llamaError = e as? LlamaError
                    ?: LlamaError.NativeException(e.message ?: "Unknown", e)
                send(ResourceState.Failure(llamaError))
            }

            awaitClose {
                session?.close()
            }
        }.flowOn(Dispatchers.IO)

    override fun close() {
        Jni.destroy(enginePtr)
    }

    private class NativeCreateParams(
        val modelPath: String,
        val useMmap: Boolean,
        val useMlock: Boolean,
        val threads: Int,
    )

    private object Jni {
        @JvmStatic
        external fun create(params: NativeCreateParams): Long

        @JvmStatic
        external fun createWithProgress(
            params: NativeCreateParams,
            listener: ProgressListener
        ): Long

        @JvmStatic
        external fun destroy(ptr: Long)
    }
}
