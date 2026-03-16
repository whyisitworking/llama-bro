package com.suhel.llamabro.sdk.model

/**
 * The unified error hierarchy for all failures in the llama-bro SDK.
 *
 * Errors propagate from the native (C++) layer through JNI as Java exceptions,
 * are caught and mapped in the internal layer, and surface here as typed subclasses.
 * Consumers can catch [LlamaError] on any Flow or use try/catch directly when 
 * interacting with [com.suhel.llamabro.sdk.LlamaEngine] or [com.suhel.llamabro.sdk.LlamaSession].
 */
sealed class LlamaError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    // ── Engine-level errors ──────────────────────────────────────────────────

    /** 
     * The model file was not found at the specified path. 
     * @property path The absolute path that was attempted.
     */
    class ModelNotFound(val path: String) :
        LlamaError("Model file not found: $path")

    /** 
     * The model file exists but could not be loaded. 
     * This can happen due to a corrupt GGUF file, an unsupported version, or 
     * insufficient memory (OOM).
     * @property path The path to the model file.
     */
    class ModelLoadFailed(val path: String, cause: Throwable? = null) :
        LlamaError("Failed to load model: $path", cause)

    /** 
     * The GGML compute backend shared library could not be loaded for this CPU. 
     * @property backendName The name of the failed backend (e.g., "CPU").
     */
    class BackendLoadFailed(val backendName: String) :
        LlamaError("Failed to load GGML backend: $backendName")

    /** 
     * The operation was explicitly cancelled or aborted. 
     * This is thrown if [com.suhel.llamabro.sdk.LlamaSession.abort] is called or 
     * if the coroutine is cancelled during a native loop.
     */
    class Cancelled :
        LlamaError("The operation was cancelled")

    // ── Session-level errors ─────────────────────────────────────────────────

    /** 
     * llama_init_from_model returned null. 
     * This typically indicates an Out Of Memory (OOM) condition or an invalid 
     * [SessionConfig].
     */
    class ContextInitFailed(cause: Throwable? = null) :
        LlamaError("Failed to initialize inference context", cause)

    /**
     * The context window is full and the configured [OverflowStrategy] cannot recover.
     * This is only thrown by [OverflowStrategy.Halt] — other strategies handle 
     * this silently by clearing or shifting history.
     */
    class ContextOverflow :
        LlamaError("Context window is full; cannot recover with current overflow strategy")

    /** 
     * The low-level llama_decode call returned a non-zero error code. 
     * @property code The native error code returned by llama.cpp.
     */
    class DecodeFailed(val code: Int) :
        LlamaError("llama_decode failed with code: $code")

    // ── Catch-all ────────────────────────────────────────────────────────────

    /** 
     * An unexpected native exception occurred. 
     * @property nativeMessage The raw error message from the C++ layer.
     */
    class NativeException(val nativeMessage: String, cause: Throwable? = null) :
        LlamaError("Native error: $nativeMessage", cause)
}
