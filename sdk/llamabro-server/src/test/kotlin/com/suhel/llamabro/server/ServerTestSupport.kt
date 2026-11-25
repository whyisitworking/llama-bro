package com.suhel.llamabro.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Two models and no inference: these tests are about the server's own routes. */
class ListedModels(private val listed: List<ServedModel> = listOf(ServedModel("alpha", 1_700_000_000))) :
    ServedModels {
    override suspend fun list(): List<ServedModel> = listed

    override suspend fun acquire(id: String?): ServedLease = throw ModelUnavailable(id)
}

class Reply(val status: Int, val body: String, val headers: Map<String, String>)

/** A plain HTTP call, so the tests see what a client sees and nothing of Ktor's client. */
suspend fun call(port: Int, path: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Reply =
    withContext(Dispatchers.IO) {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
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
