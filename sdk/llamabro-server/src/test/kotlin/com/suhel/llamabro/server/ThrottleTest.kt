package com.suhel.llamabro.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wrong keys from one address are throttled (docs/design/server.md §6, D173), on a clock the test moves. */
class ThrottleTest {
    private var now = 1_000_000L
    private val clock = { now }

    private fun keyed(throttle: Throttle = Throttle(enabled = true, clock = clock)) =
        ServerConfig {
            port = 0
            apiKey = "sesame"
            this.throttle = throttle
        }

    private val wrong = mapOf("Authorization" to "Bearer nope")
    private val right = mapOf("Authorization" to "Bearer sesame")

    private suspend fun guarded(port: Int, headers: Map<String, String>) =
        call(port, "/no-such-route", headers = headers)

    private suspend fun wrongKeys(port: Int, count: Int) =
        repeat(count) { assertEquals("wrong key ${it + 1}", 401, guarded(port, wrong).status) }

    @Test
    fun fiveWrongKeysThrottleThatAddress() =
        withServer(keyed()) { server, port ->
            val seen = CompletableDeferred<ServerEvent.Rejected>()
            val collecting =
                server.events
                    .onEach { if (it is ServerEvent.Rejected && it.status == 429) seen.complete(it) }
                    .launchIn(CoroutineScope(Dispatchers.Unconfined))
            wrongKeys(port, 5)
            // the sixth is refused before its key is looked at, the right one too: no answer tells a guess apart
            listOf(wrong, right, emptyMap()).forEach {
                val reply = guarded(port, it)
                assertEquals(429, reply.status)
                assertEquals("30", reply.headers["retry-after"])
                val error =
                    Json
                        .parseToJsonElement(reply.body)
                        .jsonObject
                        .getValue("error")
                        .jsonObject
                assertEquals(429, error.getValue("code").jsonPrimitive.int)
                assertEquals("rate_limit_error", error.getValue("type").jsonPrimitive.content)
            }
            val rejected = seen.await()
            collecting.cancel()
            assertEquals("/no-such-route", rejected.path)
            assertTrue(rejected.reason, rejected.reason.isNotEmpty())
            // open paths are never throttled
            assertEquals(200, call(port, "/health").status)
            assertEquals(200, call(port, "/v1/models").status)
            // the wait counts down
            now += 12_500
            assertEquals("18", guarded(port, wrong).headers["retry-after"])
        }

    @Test
    fun wrongKeysSpreadOverMoreThanAMinuteAreNotThrottled() =
        withServer(keyed()) { _, port ->
            wrongKeys(port, 4)
            now += 61_000
            wrongKeys(port, 4)
            assertEquals(401, guarded(port, wrong).status)
        }

    @Test
    fun aMissingKeyIsNotAGuess() =
        withServer(keyed()) { _, port ->
            repeat(10) { assertEquals(401, guarded(port, emptyMap()).status) }
        }

    @Test
    fun waitDoublesUpToFiveMinutes() =
        withServer(keyed()) { _, port ->
            listOf(30, 60, 120, 240, 300, 300).forEach { wait ->
                wrongKeys(port, 5)
                assertEquals(wait.toString(), guarded(port, wrong).headers["retry-after"])
                now += wait * 1_000L - 1
                assertEquals(429, guarded(port, wrong).status)
                now += 1
            }
            assertEquals(401, guarded(port, wrong).status)
        }

    @Test
    fun rightKeyResetsTheThrottle() =
        withServer(keyed()) { _, port ->
            wrongKeys(port, 4)
            assertEquals(404, guarded(port, right).status)
            wrongKeys(port, 4)
            assertEquals(404, guarded(port, right).status)
            // and after a wait, the right key starts the doubling over
            wrongKeys(port, 5)
            assertEquals("30", guarded(port, wrong).headers["retry-after"])
            now += 30_000
            assertEquals(404, guarded(port, right).status)
            wrongKeys(port, 5)
            assertEquals("30", guarded(port, wrong).headers["retry-after"])
        }

    @Test
    fun otherAddressesAreNotThrottled() =
        runBlocking {
            // a test client has one address, so this is the throttle's own bookkeeping
            val keys = WrongKeys(clock)
            repeat(5) { keys.wrong("192.168.1.12") }
            assertEquals(30_000L, keys.waitFor("192.168.1.12"))
            assertNull(keys.waitFor("192.168.1.31"))
            keys.wrong("192.168.1.31")
            assertNull(keys.waitFor("192.168.1.31"))
        }

    @Test
    fun theThrottleForgetsIdleAddressesAndKeepsAtMostItsCap() =
        runBlocking {
            val keys = WrongKeys(clock)
            repeat(WrongKeys.MAX_TRACKED + 50) { keys.wrong("10.0.${it / 256}.${it % 256}") }
            assertEquals(WrongKeys.MAX_TRACKED, keys.tracked())
            // the oldest went first: its one wrong key is forgotten, the newest one's is kept
            val oldest = "10.0.0.0"
            val newest = "10.0.4.49"
            repeat(4) { keys.wrong(oldest) }
            assertNull(keys.waitFor(oldest))
            repeat(4) { keys.wrong(newest) }
            assertEquals(30_000L, keys.waitFor(newest))
            now += 10 * 60_000L + 1
            keys.wrong("10.9.9.9")
            assertEquals(1, keys.tracked())
        }

    @Test
    fun throttleCanBeTurnedOff() =
        withServer(keyed(Throttle.Off)) { _, port ->
            wrongKeys(port, 20)
            assertEquals(404, guarded(port, right).status)
        }

    @Test
    fun throttleIsOnByDefault() {
        assertEquals(Throttle.Default, ServerConfig.Default.throttle)
        withServer(
            ServerConfig {
                port = 0
                apiKey = "sesame"
            },
        ) { _, port ->
            wrongKeys(port, 5)
            assertEquals(429, guarded(port, wrong).status)
        }
    }
}
