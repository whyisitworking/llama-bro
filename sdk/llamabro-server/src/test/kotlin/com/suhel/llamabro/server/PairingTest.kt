package com.suhel.llamabro.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pairing the test answers by hand: each request waits for [answer]. */
class Approver : Pairing {
    class Asked(val request: PairingRequest, val answer: CompletableDeferred<Boolean>)

    val asked = Channel<Asked>(Channel.UNLIMITED)

    override suspend fun approve(request: PairingRequest): Boolean {
        val answer = CompletableDeferred<Boolean>()
        asked.send(Asked(request, answer))
        return answer.await()
    }

    suspend fun next(): Asked = withTimeout(5_000) { asked.receive() }
}

private const val POLL_MILLIS = 20L

private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

/** Pairing a laptop from its browser, approved on the phone (docs/design/server.md §6, D174). */
class PairingTest {
    private val approver = Approver()

    private fun pairing(pairing: Pairing? = approver, throttle: Throttle = Throttle.Default) =
        ServerConfig {
            port = 0
            apiKey = "K7QM4RTX9HCD2WNB"
            keyMatching = KeyMatching.Typable
            // as the demo serves: open to other origins for the API, which pairing must not be
            cors = Cors.Any
            this.pairing = pairing
            this.throttle = throttle
        }

    private suspend fun pair(port: Int, headers: Map<String, String> = emptyMap()) =
        call(port, "/pair", method = "POST", headers = headers, body = "")

    /** What the page sees once the phone has answered: it polls, as the page does. */
    private suspend fun answered(port: Int, id: String): JsonObject =
        withTimeout(5_000) {
            var answer = json(call(port, "/pair/$id").body)
            while (answer.text("state") == "pending") {
                delay(POLL_MILLIS)
                answer = json(call(port, "/pair/$id").body)
            }
            answer
        }

    @Test
    fun rootServesThePageOnlyWithPairing() {
        withServer(pairing(pairing = null)) { _, port ->
            assertEquals(401, call(port, "/").status)
            assertEquals(401, pair(port).status)
            assertEquals(401, call(port, "/pair/abc").status)
        }
        withServer(pairing()) { _, port ->
            val page = call(port, "/")
            assertEquals(200, page.status)
            assertTrue(page.headers["content-type"].orEmpty(), page.headers["content-type"]!!.startsWith("text/html"))
            assertTrue(page.body.contains("Approve on your phone"))
            assertEquals("no-store", page.headers["cache-control"])
            // the model routes still want the key
            assertEquals(401, call(port, "/props").status)
        }
    }

    @Test
    fun pairGivesFourDigitCode() =
        withServer(pairing()) { _, port ->
            val reply = pair(port, mapOf("User-Agent" to "Mozilla/5.0 (Macintosh) Chrome/131.0"))
            assertEquals(200, reply.status)
            val started = json(reply.body)
            val code = started.text("code")
            assertTrue(code, Regex("[0-9]{4}").matches(code))
            // 128 random bits in base64url, unpadded
            assertTrue(started.text("id"), Regex("[A-Za-z0-9_-]{22}").matches(started.text("id")))
            val asked = approver.next()
            assertEquals(PairingRequest(code, "127.0.0.1", "Mozilla/5.0 (Macintosh) Chrome/131.0"), asked.request)
            asked.answer.complete(false)
        }

    @Test
    fun onePendingPerAddressThreeInAll() =
        runTest {
            val asked = mutableListOf<PairingRequest>()
            val pairings =
                Pairings(
                    {
                        asked += it
                        CompletableDeferred<Boolean>().await()
                    },
                    backgroundScope,
                    "key",
                    clock = { testScheduler.currentTime },
                )
            val first = pairings.start("10.0.0.1", null) as Pairings.Start.Begun
            // a second POST from that address while it waits is the same pairing, asked once
            assertEquals(first, pairings.start("10.0.0.1", null))
            pairings.start("10.0.0.2", null) as Pairings.Start.Begun
            advanceTimeBy(30_000)
            pairings.start("10.0.0.3", null) as Pairings.Start.Begun
            val full = pairings.start("10.0.0.4", null) as Pairings.Start.Full
            // until the oldest expires
            assertEquals(90_000L, full.retryAfterMillis)
            runCurrent()
            assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"), asked.map { it.client })
            advanceTimeBy(90_001)
            pairings.start("10.0.0.4", null) as Pairings.Start.Begun
        }

    @Test
    fun anotherAddressCantPollAPairing() =
        runTest {
            val pairings = Pairings({ true }, backgroundScope, "key", clock = { testScheduler.currentTime })
            val begun = pairings.start("10.0.0.1", null) as Pairings.Start.Begun
            runCurrent()
            assertNull(pairings.poll("10.0.0.2", begun.id))
            assertEquals(Pairings.State.Approved("key"), pairings.poll("10.0.0.1", begun.id))
        }

    @Test
    fun approvedDeliversKeyOnce() =
        withServer(pairing()) { _, port ->
            val id = json(pair(port).body).text("id")
            assertEquals("pending", json(call(port, "/pair/$id").body).text("state"))
            approver.next().answer.complete(true)
            val answer = answered(port, id)
            assertEquals("approved", answer.text("state"))
            assertEquals("K7QM4RTX9HCD2WNB", answer.text("key"))
            assertEquals("http://127.0.0.1:$port", answer.text("baseUrl"))
            // once: the id is gone after it
            assertEquals(404, call(port, "/pair/$id").status)
            // and the key it gave works
            assertEquals(404, call(port, "/no-such-route", headers = mapOf("x-api-key" to answer.text("key"))).status)
        }

    @Test
    fun deniedAndExpired() =
        runTest {
            val answers = Channel<CompletableDeferred<Boolean>>(Channel.UNLIMITED)
            var cancelled = 0
            val pairings =
                Pairings(
                    { _ ->
                        val answer = CompletableDeferred<Boolean>()
                        answers.send(answer)
                        try {
                            answer.await()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            cancelled++
                            throw e
                        }
                    },
                    backgroundScope,
                    "key",
                    clock = { testScheduler.currentTime },
                )
            val denied = pairings.start("10.0.0.1", null) as Pairings.Start.Begun
            runCurrent()
            answers.receive().complete(false)
            runCurrent()
            assertEquals(Pairings.State.Denied, pairings.poll("10.0.0.1", denied.id))
            assertNull(pairings.poll("10.0.0.1", denied.id))

            val unanswered = pairings.start("10.0.0.1", null) as Pairings.Start.Begun
            runCurrent()
            answers.receive()
            advanceTimeBy(Pairings.EXPIRY_MILLIS - 1)
            assertEquals(Pairings.State.Pending, pairings.poll("10.0.0.1", unanswered.id))
            advanceTimeBy(2)
            // the app's question is cancelled with it: no answer is a deny
            assertEquals(1, cancelled)
            assertEquals(Pairings.State.Expired, pairings.poll("10.0.0.1", unanswered.id))
            assertNull(pairings.poll("10.0.0.1", unanswered.id))
        }

    @Test
    fun aDenyReachesThePage() =
        withServer(pairing()) { _, port ->
            val id = json(pair(port).body).text("id")
            approver.next().answer.complete(false)
            assertEquals("denied", answered(port, id).text("state"))
            assertEquals(404, call(port, "/pair/$id").status)
        }

    @Test
    fun pairingSharesTheThrottle() =
        withServer(pairing(throttle = Throttle(enabled = true, clock = { 0L }))) { _, port ->
            // a wrong id is a wrong guess, as a wrong key is
            repeat(3) { assertEquals(404, call(port, "/pair/nope").status) }
            repeat(2) { assertEquals(401, call(port, "/props", headers = mapOf("x-api-key" to "nope")).status) }
            listOf(call(port, "/"), pair(port), call(port, "/pair/nope"), call(port, "/props")).forEach {
                assertEquals(429, it.status)
                assertEquals("30", it.headers["retry-after"])
            }
        }

    @Test
    fun anotherSiteCantPair() =
        withServer(pairing()) { _, port ->
            // a page on another origin: the demo's CORS is open for the API, never for pairing
            assertEquals(403, pair(port, mapOf("Origin" to "http://evil.example")).status)
            // nor a public name pointed at this address (DNS rebinding)
            assertEquals(403, call(port, "/", headers = mapOf("Host" to "evil.example:$port")).status)
            assertEquals(403, pair(port, mapOf("Host" to "evil.example:$port")).status)
            // the page's own origin and the phone's LAN names are fine
            assertEquals(200, pair(port, mapOf("Origin" to "http://127.0.0.1:$port")).status)
            assertEquals(200, call(port, "/", headers = mapOf("Host" to "llama-bro.local:$port")).status)
        }

    @Test
    fun pageHasNoExternalResources() {
        val page = pairingPage()
        listOf("http:", "https:", "//", "src=", "href=", "url(", "@import", "<link", "<iframe").forEach {
            assertTrue("the page has $it", it !in page.lowercase())
        }
        listOf("Approve on your phone", "navigator.clipboard", "prefers-color-scheme", "/pair").forEach {
            assertTrue("the page lacks $it", it in page)
        }
    }

    @Test
    fun pairingNeedsAKey() {
        assertThrows(IllegalArgumentException::class.java) { ServerConfig { pairing = approver } }
        assertNull(ServerConfig.Default.pairing)
    }
}
