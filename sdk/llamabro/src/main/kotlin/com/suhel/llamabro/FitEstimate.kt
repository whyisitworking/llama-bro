package com.suhel.llamabro

/** A projection from GGUF metadata before loading model weights. */
public class FitEstimate internal constructor(
    /** Whether the selected model fits the current memory budget; null when memory is unavailable. */
    public val fits: Boolean?,
    /** Context that would be chosen now, or null when the model cannot fit. */
    public val contextTokens: Int?,
    /** Smallest context considered by the native fit. */
    public val minContextTokens: Int,
    /** Projected memory at [minContextTokens]. */
    public val bytesAtMinimum: Long,
    /** Projected memory at the configured ceiling, when measured. */
    public val bytesAtCeiling: Long?,
    /** Memory available to the model after SDK reserves, when known. */
    public val budgetBytes: Long?,
    /** Device kind whose memory was projected. */
    public val device: DeviceKind,
    /** ggml device name, or null when the kind has no named device. */
    public val deviceName: String?,
    /** How the estimate was obtained. */
    public val method: Method,
) {
    /** Source of the estimate. */
    public enum class Method {
        /** llama.cpp's memory projection using GGUF headers. */
        Projection,

        /** Metadata-only estimate when projection is unavailable. */
        MetadataEstimate,
    }
}
