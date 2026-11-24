package com.suhel.llamabro.hub.spi

import com.suhel.llamabro.InternalLlamaBroApi
import java.io.Closeable
import java.io.InputStream

/**
 * A single HTTP request. Redirects are resolved by the implementation. `HttpTransport` in production; public only for
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
