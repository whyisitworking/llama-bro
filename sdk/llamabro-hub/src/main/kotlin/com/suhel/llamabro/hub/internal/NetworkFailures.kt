package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.spi.Response
import com.suhel.llamabro.hub.spi.Transport
import java.io.IOException

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
