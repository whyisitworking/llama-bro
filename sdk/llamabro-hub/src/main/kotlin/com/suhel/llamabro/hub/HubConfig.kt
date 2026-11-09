package com.suhel.llamabro.hub

import com.suhel.llamabro.Logger
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Settings for Hub requests and the local cache. Supply them through [LlamaBroHub.ConfigProvider]. */
public class HubConfig internal constructor(
    /** The Hub API root, including its trailing slash. */
    public val endpoint: String,
    /** Cache root; null uses the app's private no-backup directory. */
    public val cacheDir: File?,
    /** Minimum time between reported progress updates. */
    public val progressInterval: Duration,
    /** Receives diagnostic messages; null uses no custom logger. */
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
        /** Receives diagnostic messages; null uses no custom logger. */
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

    public companion object {
        /** Uses the public Hugging Face endpoint and the app's private no-backup cache. */
        @JvmField
        public val Default: HubConfig = HubConfig("https://huggingface.co/", null, 1.seconds, null, null)
    }
}

/** Creates Hub settings from [HubConfig.Default]. */
public fun HubConfig(configure: HubConfig.Builder.() -> Unit): HubConfig =
    HubConfig.Builder(HubConfig.Default).apply(configure).build()
