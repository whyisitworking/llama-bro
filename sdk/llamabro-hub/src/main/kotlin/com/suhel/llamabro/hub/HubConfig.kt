package com.suhel.llamabro.hub

import com.suhel.llamabro.Logger
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Settings for Hub requests and the local cache. Pass them to [LlamaBroHub.create] (D70). */
public class HubConfig internal constructor(
    /** The Hub API root, including its trailing slash. */
    public val endpoint: String,
    /** Cache root; null uses the app's private no-backup directory. */
    public val cacheDir: File?,
    /** Minimum time between reported progress updates. */
    public val progressInterval: Duration,
    /** Receives diagnostic messages; null logs to logcat under `llamabro-hub` at `Warn` and above (D66). */
    public val logger: Logger?,
    internal val token: (suspend () -> String?)?,
) {
    /** Mutable scope for a Hub configuration. */
    public class Builder internal constructor(from: HubConfig) {
        /** The Hub API root, including its trailing slash. */
        public var endpoint: String = from.endpoint

        /** Cache root; null uses the app's private no-backup directory. */
        public var cacheDir: File? = from.cacheDir

        /** Minimum time between reported progress updates; at least 100 milliseconds. */
        public var progressInterval: Duration = from.progressInterval

        /** Receives diagnostic messages; null logs to logcat under `llamabro-hub` at `Warn` and above (D66). */
        public var logger: Logger? = from.logger
        private var tokenProvider: (suspend () -> String?)? = from.token

        /** Called before each plan or download; read the token from your own secure storage. */
        public fun token(provider: suspend () -> String?) {
            tokenProvider = provider
        }

        internal fun build(): HubConfig {
            require(progressInterval >= 100.milliseconds) { "progressInterval must be at least 100 ms" }
            return HubConfig(endpoint, cacheDir, progressInterval, logger, tokenProvider)
        }
    }

    // equality covers every field, the logger and token provider by reference; create() compares only the settings
    // (D106)
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is HubConfig &&
                    endpoint == other.endpoint &&
                    cacheDir == other.cacheDir &&
                    progressInterval == other.progressInterval &&
                    logger === other.logger &&
                    token === other.token
            )

    override fun hashCode(): Int {
        var result = endpoint.hashCode()
        result = 31 * result + (cacheDir?.hashCode() ?: 0)
        result = 31 * result + progressInterval.hashCode()
        result = 31 * result + System.identityHashCode(logger)
        result = 31 * result + System.identityHashCode(token)
        return result
    }

    override fun toString(): String =
        "HubConfig(endpoint=$endpoint, cacheDir=$cacheDir, progressInterval=$progressInterval, " +
            "logger=${logger?.let { "set" }}, token=${token?.let { "set" }})"

    public companion object {
        /** Uses the public Hugging Face endpoint and the app's private no-backup cache. */
        public val Default: HubConfig = HubConfig("https://huggingface.co/", null, 1.seconds, null, null)
    }
}

/** Creates Hub settings from [HubConfig.Default]. */
public fun HubConfig(configure: HubConfig.Builder.() -> Unit): HubConfig =
    HubConfig.Builder(HubConfig.Default).apply(configure).build()
