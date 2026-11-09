package com.suhel.llamabro.hub.internal

import java.io.Closeable
import java.io.InputStream

/** A single HTTP request. Redirects are resolved by the implementation. */
internal interface Transport {
    /** [range] is the first byte wanted; [rangeEnd] optionally bounds the inclusive last byte. */
    suspend fun open(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        range: Long? = null,
        rangeEnd: Long? = null,
    ): Response
}

/** The caller owns the response and must close it after consuming [body]. */
internal class Response(
    val status: Int,
    val headers: Map<String, List<String>>,
    val finalUrl: String,
    val body: InputStream?,
    private val onClose: () -> Unit,
) : Closeable {
    override fun close() = onClose()
}
