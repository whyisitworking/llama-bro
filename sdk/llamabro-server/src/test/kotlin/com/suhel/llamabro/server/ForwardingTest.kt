package com.suhel.llamabro.server

import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model routes, forwarded to `Model.exchange`: status, content type and bytes as llama-server wrote them. */
class ForwardingTest {
    private val chat = "/v1/chat/completions"
    private val streamBody = """{"model":"alpha","stream":true,"messages":[]}"""

    private fun served(script: FakeLlamaBro.() -> Unit) = FakeServed(FakeLlamaBro(script))

    private fun errorOf(reply: Reply) =
        Json
            .parseToJsonElement(reply.body)
            .jsonObject
            .getValue("error")
            .jsonObject

    @Test
    fun streamPassesBytesThrough() {
        val first = "event: message_start\ndata: {\"type\":\"message_start\"}\n\n"
        val second = "data: {\"choices\":[]}\n\n"
        val models = served { raw(200, first, second, holdOpen = true) }
        withServer(models = models) { _, port ->
            val stream = Streaming(port, chat, streamBody)
            assertEquals(200, stream.status)
            assertTrue(stream.contentType.orEmpty(), stream.contentType.orEmpty().startsWith("text/event-stream"))
            // the request is still open: these lines arrive because each chunk was flushed, not at the end
            val lines = List(5) { stream.line() }
            assertEquals((first + second).split("\n").take(5), lines)
            stream.hangUp()
        }
    }

    @Test
    fun errorStatusAndBodyPassThrough() {
        val error = """{"error":{"code":400,"message":"bad","type":"invalid_request_error"}}"""
        val models = served { raw(400, error) }
        withServer(models = models) { _, port ->
            val reply = call(port, chat, "POST", body = streamBody)
            assertEquals(400, reply.status)
            assertEquals(error, reply.body)
            assertTrue(reply.headers.getValue("content-type").startsWith("application/json"))
        }
    }

    @Test
    fun unknownModelIs404ModelNotFound() {
        val models = served { }
        withServer(models = models) { _, port ->
            val reply = call(port, chat, "POST", body = """{"model":"gamma","messages":[]}""")
            assertEquals(404, reply.status)
            val error = errorOf(reply)
            assertEquals(404, error.getValue("code").jsonPrimitive.int)
            assertEquals("not_found_error", error.getValue("type").jsonPrimitive.content)
            assertTrue(
                error
                    .getValue("message")
                    .jsonPrimitive.content
                    .contains("gamma"),
            )
            assertEquals(listOf<String?>("gamma"), models.asked)
            assertEquals(0, models.acquired.get())
        }
    }

    @Test
    fun bodyWithoutModelAsksForTheDefault() {
        val models = served { raw(200, "{}") }
        withServer(models = models) { _, port ->
            assertEquals(200, call(port, chat, "POST", body = """{"messages":[]}""").status)
            assertEquals(listOf<String?>(null), models.asked)
        }
    }

    @Test
    fun disconnectCancelsTheRequest() {
        val models = served { raw(200, "data: {\"n\":1}\n\n", holdOpen = true) }
        withServer(models = models) { _, port ->
            val stream = Streaming(port, chat, streamBody)
            assertEquals("data: {\"n\":1}", stream.line())
            assertEquals(0, models.fake.cancelledRequests)
            stream.hangUp()
            eventually { models.fake.cancelledRequests == 1 }
        }
    }

    @Test
    fun modelClosedMidStreamEndsCleanly() {
        val models = served { raw(200, "data: {\"n\":1}\n\n", holdOpen = true) }
        withServer(models = models) { _, port ->
            val stream = Streaming(port, chat, streamBody)
            assertEquals(200, stream.status)
            assertEquals("data: {\"n\":1}", stream.line())
            models.model.close()
            // what was sent, then the chunked stream's proper end: rest() throws on a socket cut short
            assertEquals("\n", stream.rest())
            eventually { models.released.get() == 1 }
        }
    }

    @Test
    fun modelClosedBeforeTheHeadIs503() {
        val models = served { }
        models.model.close()
        withServer(models = models) { _, port ->
            val reply = call(port, chat, "POST", body = streamBody)
            assertEquals(503, reply.status)
            assertEquals("unavailable_error", errorOf(reply).getValue("type").jsonPrimitive.content)
            assertEquals(1, models.released.get())
        }
    }

    @Test
    fun noHeadIsAnEmpty200() {
        // upstream always writes a chunk; a request that ends with none still gets an answer
        val models = served { raw(200) }
        withServer(models = models) { _, port ->
            val reply = call(port, "/tokenize", "POST", body = """{"content":"hi"}""")
            assertEquals(200, reply.status)
            assertEquals("", reply.body)
            assertTrue(reply.headers.getValue("content-type").startsWith("application/json"))
        }
    }

    @Test
    fun leaseReleasedAfterEveryRequest() {
        val models =
            served {
                raw(200, "{}")
                raw(500, """{"error":{"code":500,"message":"boom","type":"server_error"}}""")
                raw(200, "data: {}\n\n", holdOpen = true)
            }
        withServer(models = models) { _, port ->
            assertEquals(200, call(port, chat, "POST", body = """{"messages":[]}""").status)
            assertEquals(1, models.released.get())
            assertEquals(500, call(port, chat, "POST", body = """{"messages":[]}""").status)
            assertEquals(2, models.released.get())
            val stream = Streaming(port, chat, streamBody)
            assertEquals("data: {}", stream.line())
            stream.hangUp()
            eventually { models.released.get() == 3 }
            assertEquals(3, models.acquired.get())
        }
    }

    @Test
    fun offRoutesAre404UntilEnabled() {
        val models = served { }
        withServer(models = models) { _, port ->
            listOf("/props", "/lora-adapters", "/slots/0?action=save").forEach { path ->
                val reply = call(port, path, "POST", body = "{}")
                assertEquals(path, 404, reply.status)
                assertEquals("not_found_error", errorOf(reply).getValue("type").jsonPrimitive.content)
            }
            assertEquals(0, models.acquired.get())
        }
    }

    @Test
    fun unknownPathIs404InOpenAiShape() =
        withServer(models = served { }) { _, port ->
            val reply = call(port, "/v2/nothing", "POST", body = "{}")
            assertEquals(404, reply.status)
            assertEquals("not_found_error", errorOf(reply).getValue("type").jsonPrimitive.content)
        }

    @Test
    fun enabledRouteForwards() {
        val models = served { raw(200, """{"ok":true}""") }
        val config =
            ServerConfig {
                port = 0
                routes = Routes.Default.enable(Endpoint.PropsSet)
            }
        withServer(config, models) { _, port ->
            val reply = call(port, "/props", "POST", body = """{"n_ctx":512}""")
            assertEquals(200, reply.status)
            assertEquals("""{"ok":true}""", reply.body)
            val request = models.fake.rawRequests.single()
            assertEquals("props_set", request.route)
            assertEquals("""{"n_ctx":512}""", request.body)
            // the one enabled: the others stay off
            assertEquals(404, call(port, "/lora-adapters", "POST", body = "{}").status)
        }
    }

    @Test
    fun getRoutesForward() {
        val models = served { raw(200, """{"total_slots":1}""") }
        withServer(models = models) { _, port ->
            val reply = call(port, "/props")
            assertEquals(200, reply.status)
            assertEquals("""{"total_slots":1}""", reply.body)
            assertEquals(
                "props",
                models.fake.rawRequests
                    .single()
                    .route,
            )
            assertEquals(listOf<String?>(null), models.asked)
        }
    }

    @Test
    fun paramsForSlotsAction() {
        val models = served { raw(200, """{"id_slot":0}""") }
        val config =
            ServerConfig {
                port = 0
                routes = Routes.Default.enable(Endpoint.SlotsAction)
            }
        withServer(config, models) { _, port ->
            val reply = call(port, "/slots/0?action=save", "POST", body = """{"filename":"a.bin"}""")
            assertEquals(200, reply.status)
            val request = models.fake.rawRequests.single()
            assertEquals("slots_action", request.route)
            assertEquals(mapOf("id_slot" to "0", "action" to "save"), request.params)
        }
    }

    @Test
    fun requestEndedCarriesTokenCounts() {
        val final =
            """data: {"choices":[],"timings":{"prompt_n":12,"predicted_n":34}}""" + "\n\n"
        val models =
            served {
                raw(200, "data: {\"choices\":[]}\n\n", final, "data: [DONE]\n\n")
                raw(200, """{"usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}""")
                raw(200, """{"tokens":[1,2]}""")
            }
        withServer(models = models) { server, port ->
            val ended = mutableListOf<ServerEvent.RequestEnded>()
            val started = mutableListOf<ServerEvent.RequestStarted>()
            val third = CompletableDeferred<Unit>()
            val collecting =
                server.events
                    .onEach {
                        if (it is ServerEvent.RequestStarted) started += it
                        if (it is ServerEvent.RequestEnded) ended += it
                        if (ended.size == 3) third.complete(Unit)
                    }.launchIn(CoroutineScope(Dispatchers.Unconfined))
            call(port, chat, "POST", body = streamBody)
            call(port, chat, "POST", body = """{"messages":[]}""")
            call(port, "/tokenize", "POST", body = """{"content":"hi"}""")
            withTimeout(1_000) { third.await() }
            collecting.cancel()
            assertEquals(listOf(12 to 34, 5 to 7), ended.take(2).map { it.promptTokens to it.predictedTokens })
            assertNull(ended[2].promptTokens)
            assertNull(ended[2].predictedTokens)
            assertEquals(listOf(200, 200, 200), ended.map { it.status })
            assertEquals(started.map { it.id }, ended.map { it.id })
            assertEquals(listOf(chat, chat, "/tokenize"), started.map { it.path })
            assertEquals("POST", started.first().method)
        }
    }
}
