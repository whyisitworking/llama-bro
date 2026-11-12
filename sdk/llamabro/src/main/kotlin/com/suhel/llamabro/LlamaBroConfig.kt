package com.suhel.llamabro

/**
 * Process-wide settings, fixed by the first [LlamaBro.getInstance] (design §5). Supply them from your
 * `Application` by implementing [LlamaBro.ConfigProvider]: `LlamaBroConfig { logLevel = LogLevel.Info }`.
 */
public class LlamaBroConfig internal constructor(
    /**
     * The lowest level logged. llama-server's own lines are filtered by it but go to stderr, not to [logger]
     * (findings F22).
     */
    public val logLevel: LogLevel,
    /** Receives the SDK's log lines; null = none. */
    public val logger: Logger?,
) {
    public fun copy(configure: Builder.() -> Unit): LlamaBroConfig = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean =
        other is LlamaBroConfig && other.logLevel == logLevel && other.logger == logger

    override fun hashCode(): Int = 31 * logLevel.hashCode() + (logger?.hashCode() ?: 0)

    override fun toString(): String = "LlamaBroConfig(logLevel=$logLevel, logger=$logger)"

    /** Mutable scope of `LlamaBroConfig { }` and [copy]. */
    public class Builder internal constructor(from: LlamaBroConfig) {
        public var logLevel: LogLevel = from.logLevel
        public var logger: Logger? = from.logger

        internal fun build(): LlamaBroConfig = LlamaBroConfig(logLevel, logger)
    }

    public companion object {
        /** Warnings and errors, to the default sink. */
        @JvmField
        public val Default: LlamaBroConfig = LlamaBroConfig(LogLevel.Warn, null)
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
