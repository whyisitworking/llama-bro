package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.model.LlamaError

/**
 * Maps a [RuntimeException] thrown by JNI into a typed [LlamaError].
 *
 * The JNI layer encodes errors as "<error_code_int>:<detail_string>" in the 
 * exception message. This function parses that encoding and returns the 
 * appropriate SDK exception.
 */
internal fun mapNativeError(e: RuntimeException): LlamaError {
    val message = e.message ?: return LlamaError.NativeException("Unknown native error", e)

    val colonIdx = message.indexOf(':')
    val code = if (colonIdx > 0) message.substring(0, colonIdx).toIntOrNull() else null
    val detail = if (colonIdx > 0) message.substring(colonIdx + 1) else message

    return when (code) {
        1 -> LlamaError.ModelNotFound(detail)
        2 -> LlamaError.ModelLoadFailed(detail, e)
        3 -> LlamaError.BackendLoadFailed(detail)
        4 -> LlamaError.Cancelled()
        10 -> LlamaError.ContextInitFailed(e)
        11 -> LlamaError.ContextOverflow()
        12 -> LlamaError.DecodeFailed(detail.toIntOrNull() ?: -1)
        else -> LlamaError.NativeException(detail, e)
    }
}
