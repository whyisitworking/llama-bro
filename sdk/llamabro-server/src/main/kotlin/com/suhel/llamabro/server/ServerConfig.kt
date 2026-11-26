package com.suhel.llamabro.server

import com.suhel.llamabro.Endpoint
import kotlinx.io.files.Path

/**
 * How a [LlamaBroServer] listens: `ServerConfig { host = "0.0.0.0"; apiKey = "secret" }`. The defaults follow
 * upstream's llama-server: this device only and no key, because the SDK makes no policy for the app
 * ([D164](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d164)).
 */
@Suppress("LongParameterList") // one per setting; apps build it with `ServerConfig { }`, never this constructor
public class ServerConfig internal constructor(
    /** The address to bind. `127.0.0.1` is this device only; `0.0.0.0` is every network the device is on. */
    public val host: String,
    /** The port to bind; 0 picks a free one, reported by [ServerState.Serving]. */
    public val port: Int,
    /** Null lets every client in. Else a client sends it as `Authorization: Bearer` or `x-api-key`. */
    public val apiKey: String?,
    /** Which browsers may call the server. */
    public val cors: Cors,
    /** Which model routes are served. */
    public val routes: Routes,
    /** The largest request body, in bytes, before the server answers 413. */
    public val maxBodyBytes: Long,
    /**
     * Where an audio upload is kept while it is transcribed: one temporary file per request, deleted when it ends,
     * however it ends. Never the SDK's media folder. The default is a `llamabro-server` folder in the system's
     * temporary directory.
     */
    public val workDir: Path,
    /** Null does not announce the server on the network; else it does, while it serves. */
    public val advertise: Advertise?,
    /** How a presented key is compared with [apiKey]. */
    public val keyMatching: KeyMatching,
    /** Whether wrong keys are throttled, when [apiKey] is set. */
    public val throttle: Throttle,
) {
    public fun copy(configure: Builder.() -> Unit): ServerConfig = Builder(this).apply(configure).build()

    override fun toString(): String =
        "ServerConfig(host=$host, port=$port, apiKey=${apiKey?.let { "***" }}, cors=$cors, routes=$routes, " +
            "maxBodyBytes=$maxBodyBytes, workDir=$workDir, advertise=$advertise, keyMatching=$keyMatching, " +
            "throttle=$throttle)"

    /** Mutable scope of `ServerConfig { }` and [copy]. */
    public class Builder internal constructor(from: ServerConfig) {
        public var host: String = from.host
        public var port: Int = from.port
        public var apiKey: String? = from.apiKey
        public var cors: Cors = from.cors
        public var routes: Routes = from.routes
        public var maxBodyBytes: Long = from.maxBodyBytes
        public var workDir: Path = from.workDir
        public var advertise: Advertise? = from.advertise
        public var keyMatching: KeyMatching = from.keyMatching
        public var throttle: Throttle = from.throttle

        internal fun build(): ServerConfig {
            require(port in 0..MAX_PORT) { "port $port is not 0..$MAX_PORT" }
            require(maxBodyBytes > 0) { "maxBodyBytes must be positive" }
            require(apiKey.isNullOrEmpty() || keyMatching.normalise(apiKey.orEmpty()).isNotEmpty()) {
                "apiKey is only spaces and dashes, which Typable matching ignores"
            }
            return ServerConfig(
                host,
                port,
                apiKey?.takeIf { it.isNotEmpty() },
                cors,
                routes,
                maxBodyBytes,
                workDir,
                advertise,
                keyMatching,
                throttle,
            )
        }
    }

    public companion object {
        /**
         * `127.0.0.1:8080`, no key, no CORS, the default routes, 32 MiB bodies, uploads in the temporary directory,
         * keys matched exactly, wrong keys throttled once a key is set.
         */
        public val Default: ServerConfig =
            ServerConfig(
                "127.0.0.1",
                8080,
                null,
                Cors.None,
                Routes.Default,
                32L * 1024 * 1024,
                Path(System.getProperty("java.io.tmpdir").orEmpty(), "llamabro-server"),
                null,
                KeyMatching.Exact,
                Throttle.Default,
            )

        private const val MAX_PORT = 65535
    }
}

/** `ServerConfig { host = "0.0.0.0"; port = 8080; apiKey = "…" }`. */
public fun ServerConfig(configure: ServerConfig.Builder.() -> Unit): ServerConfig = ServerConfig.Default.copy(configure)

/**
 * How a key a client presents is compared with [ServerConfig.apiKey]; either way the compare is in constant time
 * ([D173](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d173)).
 */
public enum class KeyMatching {
    /** Byte for byte, as upstream. */
    Exact,

    /**
     * For a key a person types: case, spaces and dashes are ignored on both sides, so `k7qm-4rtx-9hcd-2wnb` matches
     * `K7QM4RTX9HCD2WNB`, and Crockford base32's aliases are read as it decodes them, O as 0 and I and L as 1. Only
     * normalised: every other character must still be right.
     */
    Typable,
    ;

    internal fun normalise(key: String): String =
        when (this) {
            Exact -> {
                key
            }

            Typable -> {
                key
                    .filterNot { it == ' ' || it == '-' }
                    .uppercase()
                    .map { crockfordAliases[it] ?: it }
                    .joinToString("")
            }
        }
}

/** Crockford base32 decodes these as the digits they look like. */
private val crockfordAliases = mapOf('O' to '0', 'I' to '1', 'L' to '1')

/** Which browsers may call the server. */
public enum class Cors {
    /** No CORS headers, as upstream: a page on another origin can't read the answers. */
    None,

    /** Any origin, so a browser on a laptop (`npx llama-chat`) can call the device. */
    Any,
}

/** The model routes the server answers: [Default], plus any it leaves off. */
public class Routes private constructor(internal val enabled: Set<Endpoint>) {
    /** These routes and [endpoint], for one the default leaves off. */
    public fun enable(endpoint: Endpoint): Routes = Routes(enabled + endpoint)

    override fun toString(): String = "Routes($enabled)"

    public companion object {
        /** The routes on by default; the ones that change the shared model stay off. */
        public val Default: Routes = Routes(emptySet())
    }
}
