package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.hub.HubException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * A single HTTP request. Redirects are resolved by the implementation. [HttpTransport] in production; public only for
 * `llamabro-hub-testing`'s scripted Hub
 * ([D75](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d75), as the core's engine seam is for
 * `llamabro-testing`, [D27](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d27)). Apps never
 * implement it.
 */
@InternalLlamaBroApi
public interface Transport {
    /** [range] is the first byte wanted; [rangeEnd] optionally bounds the inclusive last byte. */
    public suspend fun open(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        range: Long? = null,
        rangeEnd: Long? = null,
    ): Response
}

/** The caller owns the response and must close it after consuming [body]. */
@InternalLlamaBroApi
public class Response(
    /** The HTTP status. */
    public val status: Int,
    /** The headers, names in lower case. */
    public val headers: Map<String, List<String>>,
    /** The URL that answered, after redirects. */
    public val finalUrl: String,
    /** The body; null for a `HEAD`. */
    public val body: InputStream?,
    private val onClose: () -> Unit,
) : Closeable {
    override fun close(): Unit = onClose()
}

/**
 * [inner] with an [IOException] from `open` reported as [HttpTransport] reports one, a retryable
 * [HubException.Network], so a scripted transport fails as the network does.
 */
internal class NetworkFailures(private val inner: Transport) : Transport {
    override suspend fun open(
        method: String,
        url: String,
        headers: Map<String, String>,
        range: Long?,
        rangeEnd: Long?,
    ): Response =
        try {
            inner.open(method, url, headers, range, rangeEnd)
        } catch (e: IOException) {
            throw HubException.Network("HTTP $method $url: ${e.message}", e, true)
        }
}
