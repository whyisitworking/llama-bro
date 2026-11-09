package com.suhel.llamabro.hub.internal

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.HubException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
            require(method == "GET" || method == "HEAD") { "unsupported HTTP method: $method" }
            require(range == null || range >= 0) { "range must not be negative" }
            require(rangeEnd == null || (range != null && rangeEnd >= range)) { "rangeEnd must follow range" }
            val rangeHeader = range?.let { "bytes=$it-${rangeEnd ?: ""}" }
            var current = URL(url)
            var hops = 0
            while (true) {
                coroutineContext.ensureActive()
                val connection = (current.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("User-Agent", "llamabro-hub/0.0.0-dev (llama.cpp b12052)")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (rangeHeader != null) setRequestProperty("Range", rangeHeader)
                    for ((name, value) in headers) {
                        if (!name.equals("Authorization", ignoreCase = true) &&
                            !name.equals("Range", ignoreCase = true) &&
                            !name.equals("User-Agent", ignoreCase = true) &&
                            !name.equals("Accept-Encoding", ignoreCase = true)
                        ) setRequestProperty(name, value)
                    }
                    if (token != null && isValidHfToken(token) && sameOrigin(current, endpointUrl)) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                val cancellation = callerJob?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause is CancellationException) connection.disconnect()
                }
                try {
                    val status = connection.responseCode
                    logger?.log(LogLevel.Debug, "$method ${withoutQuery(current)} $status" +
                        (rangeHeader?.let { " Range: $it" } ?: ""))
                    if (status in REDIRECTS) {
                        val location = connection.getHeaderField("Location")
                            ?: throw HubException.Network("redirect without Location", null, false)
                        if (++hops > 10) throw HubException.Network("too many redirects", null, false)
                        current = URL(current, location)
                        cancellation?.dispose()
                        connection.disconnect()
                        continue
                    }
                    val responseHeaders = connection.headerFields.entries
                        .filter { it.key != null }
                        .associate { it.key!!.lowercase(Locale.ROOT) to (it.value ?: emptyList()) }
                    val stream = if (method == "HEAD") null else
                        (if (status >= 400) connection.errorStream else connection.inputStream)
                    val closed = AtomicBoolean(false)
                    return@withContext Response(
                        status = status,
                        headers = responseHeaders,
                        finalUrl = current.toString(),
                        body = stream?.let { NetworkInputStream(it, callerJob) },
                    ) {
                        if (closed.compareAndSet(false, true)) {
                            try { stream?.close() } finally {
                                cancellation?.dispose()
                                connection.disconnect()
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    cancellation?.dispose()
                    connection.disconnect()
                    throw e
                } catch (e: IOException) {
                    cancellation?.dispose()
                    connection.disconnect()
                    coroutineContext.ensureActive()
                    throw HubException.Network("HTTP $method ${withoutQuery(current)}: ${e.message}", e, true)
                } catch (e: Exception) {
                    cancellation?.dispose()
                    connection.disconnect()
                    throw e
                }
            }
            error("unreachable")
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

    private fun withoutQuery(url: URL): String =
        URL(url.protocol, url.host, url.port, url.path).toString()

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val warnedInvalidToken = AtomicBoolean(false)
    }
}
