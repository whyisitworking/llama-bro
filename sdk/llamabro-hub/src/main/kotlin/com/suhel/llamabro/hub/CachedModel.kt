package com.suhel.llamabro.hub

/** One quantized model present in the local Hub cache. */
public class CachedModel internal constructor(
    /** Repository and quantization tag for this cached model. */
    public val model: HfModel,
    /** Total bytes of its model GGUF parts. */
    public val sizeBytes: Long,
)
