package com.suhel.llamabro

/**
 * Process-wide settings, fixed by the first [LlamaBro.create]
 * ([D70](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d70)): `LlamaBroConfig { logLevel =
 * LogLevel.Info }`. A later `create` with the same settings returns the same object, keeping the first logger, and
 * different settings throw ([D106](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d106)).
 */
public class LlamaBroConfig internal constructor(
    /**
     * The lowest level of llama.cpp and ggml lines, and of the SDK's own, that reach [logger] or logcat.
     * llama-server's own lines are filtered by it but go to stderr, not to [logger] (findings
     * [F22](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f22)).
     */
    public val logLevel: LogLevel,
    /**
     * Receives the SDK's log lines, from the first native call. Null logs to logcat under `llamabro`
     * ([D66](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d66)); there is no off switch, so
     * pass a logger that drops lines for silence.
     */
    public val logger: Logger?,
    /**
     * Whether the NPU backend (`llamabro-hexagon`) polls for the NPU's results instead of sleeping until they arrive
     * (upstream's `GGML_HEXAGON_OPPOLL`). Off by default, as upstream
     * ([D90](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d90)). On, the waiting CPU core
     * never idles, so chat runs 4-7% faster and speech about 30% faster, but the chip runs hotter while busy (findings
     * [F156](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f156)). Each empty poll
     * reads the queue without blocking (patch 0007), so the DSP library no longer floods logcat
     * ([F162](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f162)). Read once, when
     * the backends start.
     */
    public val npuPolling: Boolean,
) {
    public fun copy(configure: Builder.() -> Unit): LlamaBroConfig = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean =
        other is LlamaBroConfig && other.logLevel == logLevel && other.logger == logger &&
            other.npuPolling == npuPolling

    override fun hashCode(): Int = (31 * logLevel.hashCode() + (logger?.hashCode() ?: 0)) * 31 + npuPolling.hashCode()

    override fun toString(): String = "LlamaBroConfig(logLevel=$logLevel, logger=$logger, npuPolling=$npuPolling)"

    /** Mutable scope of `LlamaBroConfig { }` and [copy]. */
    public class Builder internal constructor(from: LlamaBroConfig) {
        public var logLevel: LogLevel = from.logLevel
        public var logger: Logger? = from.logger
        public var npuPolling: Boolean = from.npuPolling

        internal fun build(): LlamaBroConfig = LlamaBroConfig(logLevel, logger, npuPolling)
    }

    public companion object {
        /** Warnings and errors, to the default sink; the NPU doesn't poll. */
        public val Default: LlamaBroConfig = LlamaBroConfig(LogLevel.Warn, null, npuPolling = false)
    }
}

/** `LlamaBroConfig { logLevel = LogLevel.Info; logger = Logger { level, message -> … } }`. */
public fun LlamaBroConfig(configure: LlamaBroConfig.Builder.() -> Unit): LlamaBroConfig =
    LlamaBroConfig.Default.copy(configure)

/** Ordinals match the C API's `lb_log_level`. */
public enum class LogLevel { Debug, Info, Warn, Error }

/** Where log lines go, e.g. `Logger { level, message -> Log.i("llama", message) }`. */
public fun interface Logger {
    public fun log(level: LogLevel, message: String)
}
