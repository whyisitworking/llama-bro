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
import org.junit.Assert.assertThrows
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
        call(port, "/no-such-route", headers = headers).status

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
                val reply = call(port, "/no-such-route", headers = it)
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
    fun typableMatchingIgnoresCaseSpacesAndDashes() =
        withServer(
            ServerConfig {
                port = 0
                apiKey = "K7QM4RTX9HCD2WNB"
                keyMatching = KeyMatching.Typable
            },
        ) { _, port ->
            listOf("K7QM4RTX9HCD2WNB", "K7QM-4RTX-9HCD-2WNB", "k7qm-4rtx-9hcd-2wnb", "k7qm 4rtx 9hcd 2wnb").forEach {
                assertEquals(it, 404, guarded(port, mapOf("Authorization" to "Bearer $it")))
            }
            assertEquals(404, guarded(port, mapOf("x-api-key" to "k7Qm-4rTx-9hCd-2wNb")))
            // normalising is all it does: one wrong character is still a wrong key
            assertEquals(401, guarded(port, mapOf("Authorization" to "Bearer K7QM-4RTX-9HCD-2WNC")))
            assertEquals(401, guarded(port, mapOf("Authorization" to "Bearer K7QM-4RTX-9HCD")))
        }

    @Test
    fun typableAcceptsCrockfordAliases() =
        withServer(
            ServerConfig {
                port = 0
                apiKey = "K7QM4RTX9HCD2WNB0001"
                keyMatching = KeyMatching.Typable
            },
        ) { _, port ->
            // Crockford's decode aliases: O reads as 0, I and L as 1, in either case
            listOf("K7QM-4RTX-9HCD-2WNB-O0OI", "k7qm-4rtx-9hcd-2wnb-o00l", "K7QM4RTX9HCD2WNBOOOL").forEach {
                assertEquals(it, 404, guarded(port, mapOf("Authorization" to "Bearer $it")))
            }
            assertEquals(401, guarded(port, mapOf("Authorization" to "Bearer K7QM4RTX9HCD2WNB000U")))
        }

    @Test
    fun typableMatchingNormalisesTheConfiguredKeyToo() =
        withServer(
            ServerConfig {
                port = 0
                apiKey = "k7qm-4rtx-9hcd-2wnb"
                keyMatching = KeyMatching.Typable
            },
        ) { _, port -> assertEquals(404, guarded(port, mapOf("Authorization" to "Bearer K7QM4RTX9HCD2WNB"))) }

    @Test
    fun exactMatchingIsTheDefault() {
        assertEquals(KeyMatching.Exact, ServerConfig.Default.keyMatching)
        withServer(keyed) { _, port ->
            assertEquals(401, guarded(port, mapOf("Authorization" to "Bearer SESAME")))
            assertEquals(401, guarded(port, mapOf("Authorization" to "Bearer ses-ame")))
            assertEquals(404, guarded(port, mapOf("Authorization" to "Bearer sesame")))
        }
    }

    @Test
    fun aTypableKeyOfOnlyDashesIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerConfig {
                apiKey = "- -"
                keyMatching = KeyMatching.Typable
            }
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
            call(port, "/no-such-route")
            val rejected = seen.await()
            collecting.cancel()
            assertEquals("/no-such-route", rejected.path)
            assertEquals(401, rejected.status)
            assertTrue(rejected.client.isNotEmpty())
            assertTrue(rejected.reason.isNotEmpty())
        }
}
