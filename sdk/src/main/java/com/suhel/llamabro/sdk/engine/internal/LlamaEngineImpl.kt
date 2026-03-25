package com.suhel.llamabro.sdk.engine.internal

import com.suhel.llamabro.sdk.engine.LlamaEngine
import com.suhel.llamabro.sdk.engine.LlamaSession
import com.suhel.llamabro.sdk.config.LoadableModel
import com.suhel.llamabro.sdk.config.SessionConfig
import com.suhel.llamabro.sdk.ProgressListener
import com.suhel.llamabro.sdk.model.ResourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Concrete implementation of [LlamaEngine] that interacts with the native llama.cpp backend.
 *
 * This class handles the native lifecycle of the llama_model pointer and serves as a
 * bridge for session creation.
 */
internal class LlamaEngineImpl(
    private val loadableModel: LoadableModel,
    listener: ProgressListener? = null,
) : LlamaEngine {

    /** Pointer to the native llama_bro_engine structure. */
    private val enginePtr: Long = try {
        val params = NativeCreateParams(
            modelPath = loadableModel.loadConfig.path,
            useMMap = loadableModel.loadConfig.useMMap,
            useMLock = loadableModel.loadConfig.useMLock,
            threads = loadableModel.loadConfig.threads,
        )

        if (listener != null) {
            Jni.createWithProgress(params, listener)
        } else {
            Jni.create(params)
        }
    } catch (e: Exception) {
        throw NativeErrorMapper.map(e, loadableModel.loadConfig.path)
    }

    override suspend fun createSession(sessionConfig: SessionConfig): LlamaSession =
        withContext(Dispatchers.IO) {
            LlamaSessionImpl(enginePtr, sessionConfig, loadableModel)
        }

    override fun createSessionFlow(sessionConfig: SessionConfig): Flow<ResourceState<LlamaSession>> =
        callbackFlow {
            var session: LlamaSession? = null

            try {
                send(ResourceState.Loading())
                session = createSession(sessionConfig)
                send(ResourceState.Success(session))
            } catch (e: Exception) {
                send(ResourceState.Failure(NativeErrorMapper.map(e)))
            }

            awaitClose {
                // If the flow is cancelled, we ensure the session is closed to prevent leaks.
                session?.close()
            }
        }.flowOn(Dispatchers.IO)

    override fun close() {
        Jni.destroy(enginePtr)
    }

    /** Helper class for passing model configuration to the Jni layer. */
    private class NativeCreateParams(
        val modelPath: String,
        val useMMap: Boolean,
        val useMLock: Boolean,
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
