package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** HTTP transport matching llama.cpp's Hub requests and redirect policy. */
@OptIn(InternalCoroutinesApi::class)
internal class HttpTransport(
    endpoint: String,
    private val token: String?,
    private val logger: Logger?,
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 60_000,
) : Transport {
    private val endpointUrl = URL(endpoint)

    init {
        require(connectTimeoutMs > 0) { "connectTimeoutMs must be positive" }
        require(readTimeoutMs > 0) { "readTimeoutMs must be positive" }
        if (token != null && !isValidHfToken(token) && warnedInvalidToken.compareAndSet(false, true)) {
            logger?.log(LogLevel.Warn, "invalid token, authentication disabled")
        }
    }

    override suspend fun open(
        method: String,
        url: String,
        headers: Map<String, String>,
        range: Long?,
        rangeEnd: Long?,
    ): Response {
        val callerJob = coroutineContext[Job]
        return withContext(Dispatchers.IO) {
            checkRequest(method, range, rangeEnd)
            follow(method, URL(url), headers, range?.let { "bytes=$it-${rangeEnd ?: ""}" }, callerJob)
        }
    }

    /** Sends the request and follows redirects until an answer comes; the caller closes the [Response]. */
    private suspend fun follow(
        method: String,
        start: URL,
        headers: Map<String, String>,
        rangeHeader: String?,
        callerJob: Job?,
    ): Response {
        var current = start
        var hops = 0
        while (true) {
            coroutineContext.ensureActive()
            val connection = connect(current, method, headers, rangeHeader)
            val cancellation =
                callerJob?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause is CancellationException) connection.disconnect()
                }
            var handedOver = false
            try {
                val status = connection.responseCode
                logger?.log(
                    LogLevel.Debug,
                    "$method ${withoutQuery(current)} $status" + (rangeHeader?.let { " Range: $it" } ?: ""),
                )
                if (status in REDIRECTS) {
                    current = redirectTarget(current, connection, ++hops)
                    continue
                }
                handedOver = true
                return response(method, status, current, connection, callerJob, cancellation)
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw HubException.Network("HTTP $method ${withoutQuery(current)}: ${e.message}", e, true)
            } finally {
                // the response owns the connection from the moment it is handed over; every other way out lets go here
                if (!handedOver) {
                    cancellation?.dispose()
                    connection.disconnect()
                }
            }
        }
    }

    private fun checkRequest(method: String, range: Long?, rangeEnd: Long?) {
        require(method == "GET" || method == "HEAD") { "unsupported HTTP method: $method" }
        require(range == null || range >= 0) { "range must not be negative" }
        require(rangeEnd == null || (range != null && rangeEnd >= range)) { "rangeEnd must follow range" }
    }

    /** One request, not yet sent: our headers, the caller's (never the ones we own), and the token for our own host. */
    private fun connect(
        url: URL,
        method: String,
        headers: Map<String, String>,
        rangeHeader: String?,
    ): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", "llamabro-hub/0.0.0-dev (llama.cpp b12052)")
            setRequestProperty("Accept-Encoding", "identity")
            if (rangeHeader != null) setRequestProperty("Range", rangeHeader)
            for ((name, value) in headers) {
                if (name.lowercase(Locale.ROOT) !in OWNED_HEADERS) setRequestProperty(name, value)
            }
            if (token != null && isValidHfToken(token) && sameOrigin(url, endpointUrl)) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

    /** Where a redirect points; at most [MAX_REDIRECTS] of them are followed. */
    private fun redirectTarget(from: URL, connection: HttpURLConnection, hops: Int): URL {
        val location =
            connection.getHeaderField("Location")
                ?: throw HubException.Network("redirect without Location", null, false)
        if (hops > MAX_REDIRECTS) throw HubException.Network("too many redirects", null, false)
        return URL(from, location)
    }

    private fun response(
        method: String,
        status: Int,
        finalUrl: URL,
        connection: HttpURLConnection,
        callerJob: Job?,
        cancellation: DisposableHandle?,
    ): Response {
        val responseHeaders =
            connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key!!.lowercase(Locale.ROOT) to (it.value ?: emptyList()) }
        val stream =
            when {
                method == "HEAD" -> null
                status >= HTTP_ERROR_FROM -> connection.errorStream
                else -> connection.inputStream
            }
        val closed = AtomicBoolean(false)
        return Response(
            status = status,
            headers = responseHeaders,
            finalUrl = finalUrl.toString(),
            body = stream?.let { NetworkInputStream(it, callerJob) },
        ) {
            if (closed.compareAndSet(false, true)) {
                try {
                    stream?.close()
                } finally {
                    cancellation?.dispose()
                    connection.disconnect()
                }
            }
        }
    }

    private class NetworkInputStream(input: InputStream, private val job: Job?) : FilterInputStream(input) {
        override fun read(): Int = guarded { super.read() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            guarded { super.read(buffer, offset, length) }

        private inline fun <T> guarded(read: () -> T): T {
            job?.ensureActive()
            return try {
                val value = read()
                job?.ensureActive()
                value
            } catch (e: IOException) {
                job?.ensureActive()
                throw HubException.Network("HTTP body read: ${e.message}", e, true)
            }
        }
    }

    private fun sameOrigin(a: URL, b: URL): Boolean =
        a.protocol.equals(b.protocol, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b)

    private fun effectivePort(url: URL): Int = if (url.port >= 0) url.port else url.defaultPort

    private fun withoutQuery(url: URL): String = URL(url.protocol, url.host, url.port, url.path).toString()

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 10
        const val HTTP_ERROR_FROM = 400
        val OWNED_HEADERS = setOf("authorization", "range", "user-agent", "accept-encoding")
        val warnedInvalidToken = AtomicBoolean(false)
    }
}
