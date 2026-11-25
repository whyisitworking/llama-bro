package com.suhel.llamabro.server

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Two models and no inference: these tests are about the server's own routes. */
class ListedModels(private val listed: List<ServedModel> = listOf(ServedModel("alpha", 1_700_000_000))) :
    ServedModels {
    override suspend fun list(): List<ServedModel> = listed

    override suspend fun acquire(id: String?): ServedLease = throw ModelUnavailable(id)
}

class Reply(val status: Int, val body: String, val headers: Map<String, String>)

/** A plain HTTP call, so the tests see what a client sees and nothing of Ktor's client. */
suspend fun call(
    port: Int,
    path: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
): Reply =
    withContext(Dispatchers.IO) {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            body?.let { send(connection, it) }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val received =
                connection.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key.lowercase() }
                    .mapValues { it.value.first() }
            Reply(status, body, received)
        } finally {
            connection.disconnect()
        }
    }

private fun send(connection: HttpURLConnection, body: String) {
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    connection.outputStream.use { it.write(body.toByteArray()) }
}

/**
 * A POST whose answer is read as it arrives, line by line, for a stream the server holds open: the test reads what it
 * needs and then [hangUp]s, as a client that goes away does.
 */
class Streaming(port: Int, path: String, body: String) {
    private val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
    val status: Int
    val contentType: String?
    private val reader: BufferedReader

    init {
        connection.requestMethod = "POST"
        send(connection, body)
        status = connection.responseCode
        contentType = connection.contentType
        reader = (if (status >= 400) connection.errorStream else connection.inputStream).bufferedReader()
    }

    /** The next line, or null at a clean end; a stream cut short throws, as `Premature EOF`. */
    suspend fun line(): String? = withContext(Dispatchers.IO) { reader.readLine() }

    /** Everything to the end; throws when the stream is cut instead of ended. */
    suspend fun rest(): String = withContext(Dispatchers.IO) { reader.readText() }

    fun hangUp() = connection.disconnect()
}

/** Waits for [condition], at most [millis]: for what a server does after a client's hang-up, which has no signal. */
suspend fun eventually(millis: Long = 1_000, condition: () -> Boolean) =
    withTimeout(millis) { while (!condition()) delay(POLL_MILLIS) }

private const val POLL_MILLIS = 5L

/**
 * Serves one [FakeLlamaBro] model as `alpha`, any other id is [ModelUnavailable]; it records the ids asked for and
 * counts the leases, so a test sees each one released.
 */
class FakeServed(val fake: FakeLlamaBro = FakeLlamaBro()) : ServedModels {
    val model: Model = runBlocking { fake.llamaBro.loadModel(ModelSource.file("/models/alpha.gguf")) }
    val asked: MutableList<String?> = Collections.synchronizedList(mutableListOf<String?>())
    val acquired = AtomicInteger()
    val released = AtomicInteger()

    override suspend fun list(): List<ServedModel> = listOf(ServedModel("alpha", 1_700_000_000))

    override suspend fun acquire(id: String?): ServedLease {
        asked += id
        if (id != null && id != "alpha") throw ModelUnavailable(id)
        acquired.incrementAndGet()
        return object : ServedLease {
            override val model: Model = this@FakeServed.model

            override fun release() {
                released.incrementAndGet()
            }
        }
    }
}

/** Starts a server on a free port, runs [block] with it, and stops it however [block] ends. */
fun withServer(
    config: ServerConfig = ServerConfig { port = 0 },
    models: ServedModels = ListedModels(),
    block: suspend (server: LlamaBroServer, port: Int) -> Unit,
) = runBlocking {
    val server = LlamaBroServer(config, models)
    server.start()
    try {
        val serving = server.state.value as ServerState.Serving
        block(server, serving.port)
    } finally {
        server.stop()
    }
}
