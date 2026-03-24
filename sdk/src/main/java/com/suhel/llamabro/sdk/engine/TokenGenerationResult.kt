package com.suhel.llamabro.sdk.engine

data class TokenGenerationResult(
    val token: String?,
    val resultCode: TokenGenerationResultCode,
    val isComplete: Boolean,
)

enum class TokenGenerationResultCode(val raw: Int) {
    OK(0),
    MODEL_NOT_FOUND(1),
    MODEL_LOAD_FAILED(2),
    BACKEND_LOAD_FAILED(3),
    CANCELLED(4),
    CONTEXT_INIT_FAILED(10),
    CONTEXT_OVERFLOW(11),
    DECODE_FAILED(12),
    UNKNOWN(99);

    companion object {
        private val reverseMap = entries.associateBy { it.raw }
        internal fun parse(raw: Int): TokenGenerationResultCode = reverseMap.getOrDefault(raw, UNKNOWN)
    }
}
