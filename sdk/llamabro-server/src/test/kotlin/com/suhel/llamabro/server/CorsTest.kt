package com.suhel.llamabro.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorsTest {
    private val origin = mapOf("Origin" to "http://laptop.local:3000")

    @Test
    fun noneSendsNoCorsHeaders() =
        withServer { _, port ->
            val reply = call(port, "/health", headers = origin)
            assertEquals(200, reply.status)
            assertNull(reply.headers["access-control-allow-origin"])
        }

    @Test
    fun anyAnswersPreflight() =
        withServer(
            ServerConfig {
                port = 0
                cors = Cors.Any
                apiKey = "sesame"
            },
        ) { _, port ->
            val preflight =
                origin +
                    mapOf(
                        "Access-Control-Request-Method" to "POST",
                        "Access-Control-Request-Headers" to "authorization,content-type,x-api-key",
                    )
            // no key: a browser's preflight never carries one
            val reply = call(port, "/v1/chat/completions", method = "OPTIONS", headers = preflight)
            assertEquals(200, reply.status)
            assertEquals("*", reply.headers["access-control-allow-origin"])
            val allowed = reply.headers.getValue("access-control-allow-headers").lowercase()
            assertTrue(allowed, "authorization" in allowed && "x-api-key" in allowed && "content-type" in allowed)
            assertTrue(reply.headers.getValue("access-control-allow-methods").contains("POST"))
            assertEquals("*", call(port, "/health", headers = origin).headers["access-control-allow-origin"])
        }
}
