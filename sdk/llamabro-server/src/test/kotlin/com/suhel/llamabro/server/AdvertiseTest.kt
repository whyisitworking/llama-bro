package com.suhel.llamabro.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

private class Entry(val name: String, val type: String, val port: Int, val txt: Map<String, String>) {
    var live = true
}

private class FakeRegistry(private val failing: Boolean = false) : ServiceRegistry {
    val entries = mutableListOf<Entry>()

    override fun register(name: String, type: String, port: Int, txt: Map<String, String>): Registration {
        check(!failing) { "the network refuses" }
        val entry = Entry(name, type, port, txt).also { entries += it }
        return Registration { entry.live = false }
    }

    val live get() = entries.filter { it.live }
}

private fun advertised(registry: FakeRegistry) = Advertise("Llama Bro") { registry }

class AdvertiseTest {
    @Test
    fun registersBothServicesWhileServing() =
        runBlocking {
            val registry = FakeRegistry()
            val server =
                LlamaBroServer(
                    ServerConfig {
                        port = 0
                        advertise = advertised(registry)
                    },
                    ListedModels(),
                )
            assertTrue(registry.entries.isEmpty())
            server.start()
            val port = (server.state.value as ServerState.Serving).port
            assertEquals(listOf("_http._tcp", "_llamabro._tcp"), registry.live.map { it.type })
            registry.live.forEach {
                assertEquals("Llama Bro", it.name)
                assertEquals(port, it.port)
            }
            server.stop()
        }

    @Test
    fun unregistersOnStop() =
        runBlocking {
            val registry = FakeRegistry()
            val server =
                LlamaBroServer(
                    ServerConfig {
                        port = 0
                        advertise = advertised(registry)
                    },
                    ListedModels(),
                )
            server.start()
            server.stop()
            assertEquals(2, registry.entries.size)
            assertTrue(registry.live.isEmpty())
        }

    @Test
    fun txtSaysWhetherAKeyIsNeeded() =
        runBlocking {
            val open = FakeRegistry()
            LlamaBroServer(
                ServerConfig {
                    port = 0
                    advertise = advertised(open)
                },
                ListedModels(),
            ).apply { start() }
                .stop()
            val keyed = FakeRegistry()
            LlamaBroServer(
                ServerConfig {
                    port = 0
                    apiKey = "k"
                    advertise = advertised(keyed)
                },
                ListedModels(),
            ).apply { start() }
                .stop()
            assertEquals(setOf(mapOf("path" to "/v1", "auth" to "none")), open.entries.map { it.txt }.toSet())
            assertEquals(setOf(mapOf("path" to "/v1", "auth" to "key")), keyed.entries.map { it.txt }.toSet())
        }

    @Test
    fun noAdvertiseRegistersNothing() =
        runBlocking {
            // nothing to observe but that it serves and stops without reaching for a registry
            val server = LlamaBroServer(ServerConfig { port = 0 }, ListedModels())
            server.start()
            assertTrue(server.state.value is ServerState.Serving)
            server.stop()
        }

    @Test
    fun aFailedBindRegistersNothing() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { taken ->
                val registry = FakeRegistry()
                val config =
                    ServerConfig {
                        port = taken.localPort
                        advertise = advertised(registry)
                    }
                val server = LlamaBroServer(config, ListedModels())
                server.start()
                assertTrue(server.state.value is ServerState.Failed)
                assertTrue(registry.entries.isEmpty())
            }
        }

    @Test
    fun aRegistrationFailureDoesNotFailTheServer() =
        runBlocking {
            val server =
                LlamaBroServer(
                    ServerConfig {
                        port = 0
                        advertise = advertised(FakeRegistry(failing = true))
                    },
                    ListedModels(),
                )
            server.start()
            assertTrue("state was ${server.state.value}", server.state.value is ServerState.Serving)
            server.stop()
        }

    @Test
    fun restartRegistersAgain() =
        runBlocking {
            val registry = FakeRegistry()
            val server =
                LlamaBroServer(
                    ServerConfig {
                        port = 0
                        advertise = advertised(registry)
                    },
                    ListedModels(),
                )
            server.start()
            server.stop()
            server.start()
            assertEquals(2, registry.live.size)
            server.stop()
            assertTrue(registry.live.isEmpty())
        }
}
