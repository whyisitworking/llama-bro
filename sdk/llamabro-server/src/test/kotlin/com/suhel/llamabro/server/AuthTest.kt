package com.suhel.llamabro.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    private val keyed =
        ServerConfig {
            port = 0
            apiKey = "sesame"
        }

    // a route the server doesn't know answers 404 when allowed in, 401 when the key stops it first
    private suspend fun guarded(port: Int, headers: Map<String, String> = emptyMap()) =
        call(port, "/props", headers = headers).status

    @Test
    fun noKeyConfiguredLetsEverythingIn() = withServer { _, port -> assertEquals(404, guarded(port)) }

    @Test
    fun bearerKeyAccepted() =
        withServer(keyed) { _, port ->
            assertEquals(404, guarded(port, mapOf("Authorization" to "Bearer sesame")))
        }

    @Test
    fun xApiKeyAccepted() =
        withServer(keyed) { _, port -> assertEquals(404, guarded(port, mapOf("x-api-key" to "sesame"))) }

    @Test
    fun eitherHeaderButNotMixed() =
        withServer(keyed) { _, port ->
            val wrongBearer = mapOf("Authorization" to "Bearer nope", "x-api-key" to "sesame")
            val wrongXApiKey = mapOf("Authorization" to "Bearer sesame", "x-api-key" to "nope")
            assertEquals(401, guarded(port, wrongBearer))
            assertEquals(401, guarded(port, wrongXApiKey))
            assertEquals(404, guarded(port, mapOf("Authorization" to "Bearer sesame", "x-api-key" to "sesame")))
        }

    @Test
    fun wrongKeyIs401WithErrorBody() =
        withServer(keyed) { _, port ->
            listOf(emptyMap(), mapOf("Authorization" to "Bearer nope"), mapOf("Authorization" to "sesame")).forEach {
                val reply = call(port, "/props", headers = it)
                assertEquals(401, reply.status)
                val error =
                    Json
                        .parseToJsonElement(reply.body)
                        .jsonObject
                        .getValue("error")
                        .jsonObject
                assertEquals(401, error.getValue("code").jsonPrimitive.int)
                assertEquals("authentication_error", error.getValue("type").jsonPrimitive.content)
                assertTrue(
                    error
                        .getValue("message")
                        .jsonPrimitive.content
                        .isNotEmpty(),
                )
            }
        }

    @Test
    fun healthAndModelsNeedNoKey() =
        withServer(keyed) { _, port ->
            assertEquals(200, call(port, "/health").status)
            assertEquals(200, call(port, "/v1/models").status)
        }

    @Test
    fun rejectionEmitsRejected() =
        withServer(keyed) { server, port ->
            val seen = CompletableDeferred<ServerEvent.Rejected>()
            val collecting =
                server.events
                    .onEach { if (it is ServerEvent.Rejected) seen.complete(it) }
                    .launchIn(CoroutineScope(Dispatchers.Unconfined))
            // the collector is subscribed before the request is sent: Unconfined runs it to its first suspension
            call(port, "/props")
            val rejected = seen.await()
            collecting.cancel()
            assertEquals("/props", rejected.path)
            assertEquals(401, rejected.status)
            assertTrue(rejected.client.isNotEmpty())
            assertTrue(rejected.reason.isNotEmpty())
        }
}
