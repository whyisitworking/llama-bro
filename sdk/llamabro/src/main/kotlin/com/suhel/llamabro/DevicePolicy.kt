package com.suhel.llamabro

/**
 * The kind of chip a model runs on (D32). Apps choose by kind; the SDK maps it to the backend and device this phone
 * has (`hexagon`/`HTP0`, `opencl`/`GPUOpenCL`) and names them in [LoadReport].
 */
public enum class DeviceKind {
    /** The neural processor (`llamabro-hexagon` on Snapdragon). */
    Npu,

    /** The GPU (`llamabro-opencl` on Adreno). */
    Gpu,

    /** The CPU, always available. */
    Cpu,
}

/** Where [LlamaBro.loadModel] puts a model ([ModelConfig.devicePolicy]). The choice is always explicit (F32). */
public sealed interface DevicePolicy {
    /**
     * The fastest kind that loads: NPU, then GPU, then CPU (D24, D31). A device that fails to load is skipped, with
     * its reason in [LoadReport.attempts].
     */
    public data object Auto : DevicePolicy

    /** [kind] if it loads, else [Auto]'s order. */
    public data class Prefer(public val kind: DeviceKind) : DevicePolicy

    /** [kind] or [LlamaBroException.DeviceUnavailable]. */
    public data class Only(public val kind: DeviceKind) : DevicePolicy
}

/** How a model was placed ([Model.loadReport]): the device it runs on, and why others weren't used. */
public data class LoadReport internal constructor(
    /** The kind it runs on. */
    public val device: DeviceKind,
    /** ggml's device name, e.g. `HTP0`, `GPUOpenCL`, `CPU`. */
    public val deviceName: String,
    /** The backend that runs it, as in [LlamaBro.backends]: `hexagon`, `opencl`, `cpu`. */
    public val backend: String,
    /** Each device tried before this one, or skipped, with the reason; empty when the first choice loaded. */
    public val attempts: List<Attempt>,
    /** The context the model was loaded with, in tokens. */
    public val contextSize: Int,
    /** Why that size: set by the app, the training context fits, or fitted to the memory available (D33). */
    public val contextReason: String,
    /**
     * What decodes: [Speculative.Mtp] when the MTP head drafts tokens, [Speculative.Ngram] when the app set it, else
     * [Speculative.Off] (P2 T8).
     */
    public val speculative: Speculative,
    /** Why: set by the app, measured faster or not on this chip, or the model has no MTP head. */
    public val speculativeReason: String,
    /** Where the SDK placed the image and audio encoder ([ModelConfig.encoderDevice]); null without an `mmproj`. */
    public val encoderDevice: DeviceKind? = null,
    /** ggml's device name for the encoder, e.g. `HTP0`, `GPUOpenCL`, `CPU`; null when there's no `mmproj`. */
    public val encoderDeviceName: String? = null,
    /**
     * Set when the memory check said the model may not fit and it loaded anyway at the smallest context
     * ([MemoryCheck.Warn]): how much it needed and how much was available. Null when it fit, or there was no check.
     */
    public val memoryWarning: String? = null,
) {
    /** One device that wasn't used. [deviceName] is null when no device of [kind] was available at all. */
    public data class Attempt internal constructor(
        public val kind: DeviceKind,
        public val deviceName: String?,
        public val reason: String,
    )
}
