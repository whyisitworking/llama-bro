package com.suhel.llamabro.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket

class LifecycleTest {
    @Test
    fun startBindsAndReportsThePort() =
        runBlocking {
            val server = LlamaBroServer(ServerConfig { port = 0 }, ListedModels())
            assertEquals(ServerState.Stopped, server.state.value)
            server.start()
            val state = server.state.value
            assertTrue("state was $state", state is ServerState.Serving)
            assertTrue((state as ServerState.Serving).port > 0)
            server.stop()
            assertEquals(ServerState.Stopped, server.state.value)
        }

    @Test
    fun healthAnswersOk() =
        withServer { _, port ->
            val reply = call(port, "/health")
            assertEquals(200, reply.status)
            assertEquals("""{"status":"ok"}""", reply.body)
            assertTrue(reply.headers.getValue("content-type").startsWith("application/json"))
        }

    @Test
    fun modelsListsServedModels() =
        withServer(models = ListedModels(listOf(ServedModel("alpha", 100), ServedModel("beta", 200)))) { _, port ->
            val reply = call(port, "/v1/models")
            assertEquals(200, reply.status)
            val json = Json.parseToJsonElement(reply.body).jsonObject
            assertEquals("list", json.getValue("object").jsonPrimitive.content)
            val data = json.getValue("data").jsonArray.map { it.jsonObject }
            assertEquals(listOf("alpha", "beta"), data.map { it.getValue("id").jsonPrimitive.content })
            assertEquals(listOf("100", "200"), data.map { it.getValue("created").jsonPrimitive.content })
            data.forEach { entry: JsonObject ->
                assertEquals("model", entry.getValue("object").jsonPrimitive.content)
                assertEquals("llamabro", entry.getValue("owned_by").jsonPrimitive.content)
            }
        }

    @Test
    fun stopUnbinds() =
        runBlocking {
            val server = LlamaBroServer(ServerConfig { port = 0 }, ListedModels())
            server.start()
            val port = (server.state.value as ServerState.Serving).port
            assertEquals(200, call(port, "/health").status)
            server.stop()
            try {
                call(port, "/health")
                fail("the port still answers")
            } catch (_: ConnectException) {
                // unbound
            }
        }

    @Test
    fun portInUseIsFailed() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { taken ->
                val server = LlamaBroServer(ServerConfig { port = taken.localPort }, ListedModels())
                server.start()
                assertTrue("state was ${server.state.value}", server.state.value is ServerState.Failed)
                server.stop()
            }
        }

    @Test
    fun startAfterStopServesAgain() =
        runBlocking {
            val server = LlamaBroServer(ServerConfig { port = 0 }, ListedModels())
            server.start()
            server.stop()
            server.start()
            val port = (server.state.value as ServerState.Serving).port
            assertEquals(200, call(port, "/health").status)
            server.stop()
        }

    @Test
    fun secondStartWhileServingIsANoOp() =
        runBlocking {
            val server = LlamaBroServer(ServerConfig { port = 0 }, ListedModels())
            server.start()
            val serving = server.state.value
            server.start()
            assertEquals(serving, server.state.value)
            assertEquals(200, call((serving as ServerState.Serving).port, "/health").status)
            server.stop()
        }

    @Test
    fun rebindSamePortAfterTraffic() =
        runBlocking {
            // a free port, found by binding and releasing it
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val server = LlamaBroServer(ServerConfig { this.port = port }, ListedModels())
            server.start()
            assertEquals(ServerState.Serving(port), server.state.value)
            assertEquals(200, call(port, "/health").status)
            server.stop()
            server.start()
            assertEquals(ServerState.Serving(port), server.state.value)
            assertEquals(200, call(port, "/health").status)
            server.stop()
        }
}
