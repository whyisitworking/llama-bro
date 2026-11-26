package com.suhel.llamabro.server

import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/** Bodies over `maxBodyBytes` are 413 before the server holds them whole, and a body that isn't JSON is 400. */
class BodyLimitTest {
    private val chat = "/v1/chat/completions"
    private val limit = 1_024L
    private val config =
        ServerConfig {
            port = 0
            maxBodyBytes = limit
        }

    // valid JSON, so only its size can refuse it
    private fun bodyOf(bytes: Int): String {
        val prefix = """{"messages":[],"pad":""""
        return prefix + "x".repeat(bytes - prefix.length - 2) + "\"}"
    }

    private fun typeOf(reply: Reply) =
        Json
            .parseToJsonElement(reply.body)
            .jsonObject
            .getValue("error")
            .jsonObject
            .getValue("type")
            .jsonPrimitive.content

    /** A POST with no Content-Length, sent chunked, so only the read itself can stop it. */
    private suspend fun chunked(port: Int, body: String): Int =
        withContext(Dispatchers.IO) {
            val connection = URL("http://127.0.0.1:$port$chat").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setChunkedStreamingMode(256)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                connection.responseCode
            } finally {
                connection.disconnect()
            }
        }

    @Test
    fun atTheLimitIsForwarded() {
        val models = FakeServed(FakeLlamaBro { raw(200, "{}") })
        withServer(config, models) { _, port ->
            assertEquals(200, call(port, chat, "POST", body = bodyOf(limit.toInt())).status)
            assertEquals(1, models.acquired.get())
        }
    }

    @Test
    fun overLimitIs413() {
        val models = FakeServed()
        withServer(config, models) { server, port ->
            val rejected = Collections.synchronizedList(mutableListOf<ServerEvent.Rejected>())
            val collecting =
                server.events
                    .onEach { if (it is ServerEvent.Rejected) rejected += it }
                    .launchIn(CoroutineScope(Dispatchers.Unconfined))
            val reply = call(port, chat, "POST", body = bodyOf(limit.toInt() + 1))
            assertEquals(413, reply.status)
            assertEquals("invalid_request_error", typeOf(reply))
            assertEquals(413, chunked(port, bodyOf(4 * limit.toInt())))
            eventually { rejected.size == 2 }
            collecting.cancel()
            assertTrue(rejected.all { it.status == 413 && it.path == chat })
            assertEquals(0, models.acquired.get())
        }
    }

    @Test
    fun chunkedUnderTheLimitIsForwarded() {
        val models = FakeServed(FakeLlamaBro { raw(200, "{}") })
        withServer(config, models) { _, port ->
            assertEquals(200, chunked(port, bodyOf(limit.toInt())))
            assertEquals(
                bodyOf(limit.toInt()),
                models.fake.rawRequests
                    .single()
                    .body,
            )
        }
    }

    @Test
    fun nestingTooDeepToParseIs400() {
        val models = FakeServed()
        withServer(models = models) { _, port ->
            val depth = 100_000
            val reply = call(port, chat, "POST", body = "[".repeat(depth) + "]".repeat(depth))
            assertEquals(400, reply.status)
            assertEquals("invalid_request_error", typeOf(reply))
            assertEquals(0, models.acquired.get())
        }
    }

    @Test
    fun invalidJsonIs400() {
        val models = FakeServed()
        withServer(config, models) { server, port ->
            val rejected = Collections.synchronizedList(mutableListOf<ServerEvent.Rejected>())
            val collecting =
                server.events
                    .onEach { if (it is ServerEvent.Rejected) rejected += it }
                    .launchIn(CoroutineScope(Dispatchers.Unconfined))
            val reply = call(port, chat, "POST", body = """{"messages":[""")
            assertEquals(400, reply.status)
            assertEquals("invalid_request_error", typeOf(reply))
            eventually { rejected.size == 1 }
            collecting.cancel()
            assertEquals(400, rejected.single().status)
            // a refused request never asks for a model
            assertEquals(0, models.acquired.get())
            assertEquals(emptyList<String?>(), models.asked)
        }
    }
}
