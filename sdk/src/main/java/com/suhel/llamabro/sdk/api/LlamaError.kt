package com.suhel.llamabro.sdk.api

/**
 * The unified error hierarchy for all failures in the llama-bro SDK.
 *
 * Errors propagate from the native (C++) layer through JNI as Java exceptions,
 * are caught and mapped in the internal layer, and surface here as typed subclasses.
 * Consumers can `catch { e: LlamaError -> ... }` on any Flow or use try/catch directly.
 */
sealed class LlamaError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    // ── Engine-level errors ──────────────────────────────────────────────────

    /** The model file was not found at the specified path. */
    class ModelNotFound(val path: String) :
        LlamaError("Model file not found: $path")

    /** The model file exists but could not be loaded (corrupt GGUF, wrong format, OOM). */
    class ModelLoadFailed(val path: String, cause: Throwable? = null) :
        LlamaError("Failed to load model: $path", cause)

    /** The GGML compute backend shared library could not be loaded for this CPU. */
    class BackendLoadFailed(val backendName: String) :
        LlamaError("Failed to load GGML backend: $backendName")

    // ── Session-level errors ─────────────────────────────────────────────────

    /** llama_init_from_model returned null — typically OOM or invalid config. */
    class ContextInitFailed(cause: Throwable? = null) :
        LlamaError("Failed to initialize inference context", cause)

    /**
     * The context window is full and the configured [OverflowStrategy] cannot recover.
     * This is only thrown by [OverflowStrategy.Halt] — other strategies handle this silently.
     */
    class ContextOverflow :
        LlamaError("Context window is full; cannot recover with current overflow strategy")

    /** The low-level llama_decode call returned a non-zero error code. */
    class DecodeFailed(val code: Int) :
        LlamaError("llama_decode failed with code: $code")

    // ── Catch-all ────────────────────────────────────────────────────────────

    /** An unexpected native exception occurred. Check [nativeMessage] for details. */
    class NativeException(val nativeMessage: String, cause: Throwable? = null) :
        LlamaError("Native error: $nativeMessage", cause)
}
