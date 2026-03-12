package com.suhel.llamabro.sdk.api

import com.suhel.llamabro.sdk.internal.LlamaEngineImpl

/**
 * The entry point for the llama-bro SDK. Loads a model and provides [LlamaSession] instances.
 *
 * **Lifecycle:** This is a heavy object — model weights are loaded into memory on creation.
 * Create once, reuse across sessions, and [close] when done (ideally in a `use {}` block).
 *
 * Multiple [LlamaSession]s can be created from one engine, but each session holds its own
 * llama context, so memory scales linearly with the number of concurrent sessions.
 *
 * Throws [LlamaError] subclasses on construction failure.
 */
interface LlamaEngine : AutoCloseable {

    /**
     * Creates a new low-level inference session.
     * For a higher-level conversation interface, call [LlamaSession.createChatSession]
     * on the returned session.
     */
    fun createSession(config: SessionConfig = SessionConfig()): LlamaSession

    companion object {
        /**
         * Loads the model at [ModelConfig.modelPath] and returns a ready-to-use engine.
         *
         * @throws LlamaError.ModelNotFound   if the file does not exist at the given path.
         * @throws LlamaError.ModelLoadFailed if the file is corrupt or not a valid GGUF.
         * @throws LlamaError.BackendLoadFailed if the GGML compute backend cannot be loaded.
         */
        fun create(config: ModelConfig): LlamaEngine {
            return LlamaEngineImpl(config)
        }
    }
}
