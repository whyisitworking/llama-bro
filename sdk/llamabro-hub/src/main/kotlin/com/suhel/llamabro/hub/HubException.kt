package com.suhel.llamabro.hub

/** A Hub operation failure with retry information for background workers. */
public sealed class HubException(message: String, cause: Throwable?) : Exception(message, cause) {
    /** Whether trying this operation again later can help. */
    public abstract val retryable: Boolean

    /** A connection, timeout, or server failure. */
    public class Network internal constructor(
        message: String,
        cause: Throwable?,
        override val retryable: Boolean,
    ) : HubException(message, cause)

    /** The repository needs access the current token does not provide. */
    public class Unauthorized internal constructor(
        /** HTTP status received from the Hub. */
        public val httpStatus: Int,
        message: String,
    ) : HubException(message, null) {
        override val retryable: Boolean get() = false
    }

    /** The requested repository or model file was not found. */
    public class NotFound internal constructor(
        message: String,
        /** Available model names, if the repository was found. */
        public val available: List<String>,
    ) : HubException(message, null) {
        override val retryable: Boolean get() = false
    }

    /** The Hub asked the client to retry after a delay. */
    public class RateLimited internal constructor(
        /** Retry delay supplied by the Hub, if any. */
        public val retryAfterSeconds: Long?,
    ) : HubException(
        "rate limited by the Hub" + (retryAfterSeconds?.let { ", retry after $it s" } ?: ""),
        null,
    ) {
        override val retryable: Boolean get() = true
    }

    /** The cache volume cannot reserve enough space. */
    public class InsufficientStorage internal constructor(
        /** Space required for the missing content. */
        public val neededBytes: Long,
        /** Space available on the cache volume. */
        public val availableBytes: Long,
    ) : HubException(
        "needs ${neededBytes / 1_048_576} MiB, ${availableBytes / 1_048_576} MiB available",
        null,
    ) {
        override val retryable: Boolean get() = false
    }

    /** The content did not match the digest published by the Hub. */
    public class IntegrityMismatch internal constructor(
        /** Repository-relative file path. */
        public val path: String,
        /** Published digest. */
        public val expected: String,
        /** Digest of the downloaded content. */
        public val actual: String,
    ) : HubException("$path: digest $actual, expected $expected", null) {
        override val retryable: Boolean get() = true
    }
}
