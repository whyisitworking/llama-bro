package com.suhel.llamabro

/**
 * Process-wide settings, fixed by the first [LlamaBro.getInstance] (design §5). Supply them from your
 * `Application` by implementing [LlamaBro.ConfigProvider].
 *
 * @property logLevel the lowest level logged. llama-server's own lines are filtered by it but go to stderr,
 *   not to [logger] (findings F22).
 */
public class LlamaBroConfig(
    public val logLevel: LogLevel = LogLevel.Warn,
    public val logger: Logger? = null,
)

/** Ordinals match the C API's `lb_log_level`. */
public enum class LogLevel { Debug, Info, Warn, Error }

public fun interface Logger {
    public fun log(level: LogLevel, message: String)
}
