package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.LlamaEngine
import com.suhel.llamabro.sdk.LlamaSession
import com.suhel.llamabro.sdk.model.LlamaError
import com.suhel.llamabro.sdk.model.LoadEvent
import com.suhel.llamabro.sdk.model.ModelConfig
import com.suhel.llamabro.sdk.model.SessionConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal class LlamaEngineImpl(
    modelConfig: ModelConfig,
    listener: ProgressListener? = null,
) : LlamaEngine {

    private val promptFormat = modelConfig.promptFormat

    private val enginePtr: Long = try {
        if (listener != null) {
            Jni.createWithProgress(
                NativeCreateParams(
                    modelPath = modelConfig.modelPath,
                    useMmap = modelConfig.useMmap,
                    useMlock = modelConfig.useMlock,
                    threads = modelConfig.threads,
                ),
                listener
            )
        } else {
            Jni.create(
                NativeCreateParams(
                    modelPath = modelConfig.modelPath,
                    useMmap = modelConfig.useMmap,
                    useMlock = modelConfig.useMlock,
                    threads = modelConfig.threads,
                )
            )
        }
    } catch (e: RuntimeException) {
        throw mapNativeError(e)
    }

    override fun createSession(sessionConfig: SessionConfig): LlamaSession {
        return LlamaSessionImpl(enginePtr, promptFormat, sessionConfig)
    }

    override fun createSessionFlow(sessionConfig: SessionConfig): Flow<LoadEvent<LlamaSession>> =
        callbackFlow {
            var session: LlamaSession? = null

            try {
                send(LoadEvent.Loading())
                session = createSession(sessionConfig)
                send(LoadEvent.Ready(session))
            } catch (e: Exception) {
                val llamaError = e as? LlamaError
                    ?: LlamaError.NativeException(e.message ?: "Unknown", e)
                send(LoadEvent.Error(llamaError))
            }

            awaitClose {
                session?.close()
            }
        }

    override fun close() {
        Jni.destroy(enginePtr)
    }

    class NativeCreateParams(
        // TODO: Can be made private?
        val modelPath: String,
        val useMmap: Boolean,
        val useMlock: Boolean,
        val threads: Int,
    )

    object Jni {
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
