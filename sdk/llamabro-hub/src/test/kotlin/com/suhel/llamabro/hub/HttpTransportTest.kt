package com.suhel.llamabro.hub

import com.suhel.llamabro.LogLevel
import com.suhel.llamabro.Logger
import com.suhel.llamabro.hub.internal.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.URL
import java.security.MessageDigest

/** HTTP behavior against independent API and CDN listeners, including faults and token scope. */
internal class HttpTransportTest {
    @Before fun reset() = hub.reset()

    @Test fun redirectFollowedToCdn() =
        runBlocking {
            val transport = HttpTransport(hub.apiUrl, null, null)
            transport.open("GET", resolve("test/flat")).use { response ->
                assertEquals(200, response.status)
                assertEquals(hub.cdnPort, URL(response.finalUrl).port)
                assertEquals(listedOid("test/flat"), sha256(response.body!!.readBytes()))
            }
        }

    @Test fun relativeRedirectResolved() =
        runBlocking {
            val transport = HttpTransport(hub.apiUrl, null, null)
            transport.open("GET", resolve("test/relative")).use { response ->
                assertEquals(200, response.status)
                assertEquals(hub.cdnPort, URL(response.finalUrl).port)
                assertEquals(listedOid("test/relative"), sha256(response.body!!.readBytes()))
            }
            assertTrue(
                hub.log().any {
                    val row = it.jsonObject
                    row.getValue("host").jsonPrimitive.content == "api" &&
                        row
                            .getValue("path")
                            .jsonPrimitive.content
                            .startsWith("/cdn-proxy/")
                },
            )
        }

    @Test fun tokenNeverCrossesHosts() =
        runBlocking {
            val token = VALID_TOKEN
            val transport = HttpTransport(hub.apiUrl, token, null)
            transport.open("GET", resolve("test/gated")).use { response ->
                assertEquals(200, response.status)
                assertEquals(listedOid("test/gated", token), sha256(response.body!!.readBytes()))
            }
            val rows = hub.log().map { it.jsonObject }
            assertTrue(rows.any { it.getValue("host").jsonPrimitive.content == "cdn" })
            assertTrue(
                rows
                    .filter { it.getValue("host").jsonPrimitive.content == "api" }
                    .all { it.getValue("auth").jsonPrimitive.boolean },
            )
            assertTrue(
                rows
                    .filter { it.getValue("host").jsonPrimitive.content == "cdn" }
                    .all { !it.getValue("auth").jsonPrimitive.boolean },
            )
            assertTrue(rows.all { it.getValue("status").jsonPrimitive.content != "400" })
        }

    @Test fun malformedTokenNotSent() =
        runBlocking {
            val warnings = mutableListOf<String>()
            val logger = Logger { level, message -> if (level == LogLevel.Warn) warnings += message }
            val transport = HttpTransport(hub.apiUrl, "abc", logger)
            repeat(2) {
                transport.open("GET", resolve("test/flat")).use { assertEquals(200, it.status) }
            }
            assertTrue(
                hub.log().none {
                    it.jsonObject
                        .getValue("auth")
                        .jsonPrimitive.boolean
                },
            )
            assertEquals(listOf("invalid token, authentication disabled"), warnings)
        }

    @Test fun tokenNeverLogged() =
        runBlocking {
            val lines = mutableListOf<String>()
            val transport = HttpTransport(hub.apiUrl, VALID_TOKEN, Logger { _, message -> lines += message })
            transport.open("GET", resolve("test/gated")).use { assertEquals(200, it.status) }
            assertFalse(lines.any { VALID_TOKEN in it })
            assertTrue(lines.isNotEmpty())
        }

    @Test fun rangeHonoured() =
        runBlocking {
            val transport = HttpTransport(hub.apiUrl, null, null)
            val full = transport.open("GET", resolve("test/flat")).use { it.body!!.readBytes() }
            transport.open("GET", resolve("test/flat"), range = 10).use { response ->
                assertEquals(206, response.status)
                assertEquals(full.drop(10), response.body!!.readBytes().toList())
            }
            assertTrue(
                hub.log().any {
                    it.jsonObject
                        .getValue("range")
                        .jsonPrimitive.content == "bytes=10-"
                },
            )
        }

    @Test fun cancelClosesConnection() =
        runBlocking {
            hub.control("[{\"kind\":\"slow\",\"rate_mib_s\":1}]")
            val transport = HttpTransport(hub.apiUrl, null, null)
            val reader =
                async(Dispatchers.IO) {
                    transport.open("GET", resolve("test/big")).use { response ->
                        val buffer = ByteArray(1 shl 20)
                        while (response.body!!.read(buffer) >= 0) { /* cancellation closes this connection */ }
                    }
                }
            delay(1_000)
            withTimeout(2_000) { reader.cancelAndJoin() }
            assertTrue(reader.isCancelled)
        }

    @Test fun stallTimesOutRetryable() =
        runBlocking {
            hub.control("[{\"kind\":\"stall\",\"path\":\"model-Q4_K_M.gguf\",\"seconds\":5}]")
            val transport = HttpTransport(hub.apiUrl, null, null, readTimeoutMs = 1_000)
            try {
                withContext(Dispatchers.IO) {
                    transport.open("GET", resolve("test/flat")).use { it.body!!.read() }
                }
                fail("the stalled body should time out")
            } catch (e: HubException.Network) {
                assertTrue(e.retryable)
            }
        }

    private fun resolve(repo: String): String = "${hub.apiUrl}$repo/resolve/${"a".repeat(40)}/model-Q4_K_M.gguf"

    private fun listedOid(repo: String, token: String? = null): String {
        val connection = URL("${hub.apiUrl}api/models/$repo/tree/${"a".repeat(40)}?recursive=true").openConnection()
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        val listing = connection.getInputStream().bufferedReader().use { it.readText() }
        return Json
            .parseToJsonElement(listing)
            .let { it as JsonArray }[0]
            .jsonObject
            .getValue("lfs")
            .jsonObject
            .getValue("oid")
            .jsonPrimitive.content
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val VALID_TOKEN = "hf_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        lateinit var hub: FakeHub

        @JvmStatic @BeforeClass
        fun startHub() {
            hub = FakeHub()
        }

        @JvmStatic @AfterClass
        fun stopHub() {
            hub.close()
        }
    }
}
