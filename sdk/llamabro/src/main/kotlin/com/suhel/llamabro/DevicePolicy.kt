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
public class LoadReport internal constructor(
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
) {
    /** One device that wasn't used. [deviceName] is null when no device of [kind] was available at all. */
    public class Attempt internal constructor(
        public val kind: DeviceKind,
        public val deviceName: String?,
        public val reason: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Attempt && kind == other.kind && deviceName == other.deviceName && reason == other.reason

        override fun hashCode(): Int = listOf(kind, deviceName, reason).hashCode()

        override fun toString(): String = "Attempt(kind=$kind, deviceName=$deviceName, reason=$reason)"
    }

    override fun equals(other: Any?): Boolean =
        other is LoadReport && device == other.device && deviceName == other.deviceName && backend == other.backend &&
            attempts == other.attempts && contextSize == other.contextSize && contextReason == other.contextReason &&
            speculative == other.speculative && speculativeReason == other.speculativeReason

    override fun hashCode(): Int =
        listOf(
            device,
            deviceName,
            backend,
            attempts,
            contextSize,
            contextReason,
            speculative,
            speculativeReason,
        ).hashCode()

    override fun toString(): String =
        "LoadReport(device=$device, deviceName=$deviceName, backend=$backend, attempts=$attempts, " +
            "contextSize=$contextSize, contextReason=$contextReason, speculative=$speculative, " +
            "speculativeReason=$speculativeReason)"
}
