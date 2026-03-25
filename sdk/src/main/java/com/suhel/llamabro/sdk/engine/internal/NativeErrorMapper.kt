package com.suhel.llamabro.sdk.engine.internal

import com.suhel.llamabro.sdk.engine.TokenGenerationResultCode
import com.suhel.llamabro.sdk.model.LlamaError

/**
 * Single point of translation from JNI exceptions to typed [LlamaError]s.
 *
 * Every JNI function that can fail throws a `RuntimeException` whose message is the
 * integer value of the native `ResultCode`. This object parses that integer and maps
 * it to the appropriate [LlamaError] subclass.
 */
internal object NativeErrorMapper {

    /**
     * Maps any exception from the JNI boundary to a typed [LlamaError].
     *
     * @param e The exception caught at the JNI boundary.
     * @param modelPath Optional model path for engine-level errors.
     */
    fun map(e: Exception, modelPath: String = ""): LlamaError {
        if (e is LlamaError) return e

        val code = e.message?.toIntOrNull()
            ?: return LlamaError.NativeException(e.message ?: "Unknown", e)

        return when (TokenGenerationResultCode.parse(code)) {
            TokenGenerationResultCode.MODEL_NOT_FOUND     -> LlamaError.ModelNotFound(modelPath)
            TokenGenerationResultCode.MODEL_LOAD_FAILED   -> LlamaError.ModelLoadFailed(modelPath, e)
            TokenGenerationResultCode.BACKEND_LOAD_FAILED -> LlamaError.BackendLoadFailed("CPU")
            TokenGenerationResultCode.CANCELLED           -> LlamaError.Cancelled()
            TokenGenerationResultCode.CONTEXT_INIT_FAILED -> LlamaError.ContextInitFailed(e)
            TokenGenerationResultCode.CONTEXT_OVERFLOW    -> LlamaError.ContextOverflow()
            TokenGenerationResultCode.DECODE_FAILED       -> LlamaError.DecodeFailed(code)
            else -> LlamaError.NativeException("Native error code: $code", e)
        }
    }

    /**
     * Maps a [TokenGenerationResultCode] to a [LlamaError].
     * Used by `generateFlow()` when the native layer returns a non-OK result code
     * via the generation result struct rather than throwing.
     */
    fun fromResultCode(code: TokenGenerationResultCode): LlamaError =
        map(RuntimeException(code.raw.toString()))
}
