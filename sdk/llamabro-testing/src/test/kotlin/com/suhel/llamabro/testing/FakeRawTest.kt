package com.suhel.llamabro.testing

import com.suhel.llamabro.Endpoint
import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.RawPart
import com.suhel.llamabro.exchange
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** `FakeLlamaBro.raw`: a scripted status and chunks for `Model.exchange`, on any endpoint. */
@OptIn(InternalLlamaBroApi::class)
class FakeRawTest {
    private fun body(vararg parts: RawPart) =
        parts.filterIsInstance<RawPart.Body>().joinToString("") {
            it.bytes.decodeToString()
        }

    @Test
    fun rawAnswersTheNextRequest() =
        runBlocking {
            val fake = FakeLlamaBro { raw(418, "{\"error\":", "\"teapot\"}") }
            val model = fake.llamaBro.loadModel(ModelSource.file("/m.gguf"))
            val parts = model.exchange(Endpoint.Props, ByteArray(0)).toList()
            assertEquals(RawPart.Head(418, "application/json"), parts[0])
            assertEquals("{\"error\":\"teapot\"}", body(*parts.toTypedArray()))
            // only the next request: the one after is answered as the fake always does
            val after = model.exchange(Endpoint.Slots, ByteArray(0)).toList()
            assertEquals(RawPart.Head(200, "application/json"), after[0])
        }

    @Test
    fun holdOpenStreamsUntilCancelled() =
        runBlocking {
            val fake = FakeLlamaBro { raw(200, "data: 1\n\n", holdOpen = true) }
            val model = fake.llamaBro.loadModel(ModelSource.file("/m.gguf"))
            val parts = model.exchange(Endpoint.ChatCompletions, """{"stream":true}""".toByteArray()).take(2).toList()
            assertEquals(RawPart.Head(200, "text/event-stream"), parts[0])
            assertEquals("data: 1\n\n", body(*parts.toTypedArray()))
            assertEquals(1, fake.cancelledRequests)
        }

    @Test
    fun cancelledRequestsCounts() =
        runBlocking {
            val fake =
                FakeLlamaBro {
                    raw(200, "a", holdOpen = true)
                    raw(200, "b")
                    raw(200, "c", holdOpen = true)
                }
            val model = fake.llamaBro.loadModel(ModelSource.file("/m.gguf"))
            model.exchange(Endpoint.Props, ByteArray(0)).take(2).toList()
            model.exchange(Endpoint.Props, ByteArray(0)).toList() // ends by itself: not a cancel
            assertEquals(1, fake.cancelledRequests)
            model.exchange(Endpoint.Props, ByteArray(0)).take(2).toList()
            assertEquals(2, fake.cancelledRequests)
        }

    @Test
    fun rawRequestsRecordsRouteParamsAndBody() =
        runBlocking {
            val fake = FakeLlamaBro { raw(200, "{}") }
            val model = fake.llamaBro.loadModel(ModelSource.file("/m.gguf"))
            val params = mapOf("id_slot" to "0", "action" to "save")
            model.exchange(Endpoint.SlotsAction, "{\"filename\":\"a\"}".toByteArray(), params).toList()
            assertEquals(
                listOf(FakeLlamaBro.RawRequest("slots_action", params, "{\"filename\":\"a\"}")),
                fake.rawRequests,
            )
        }
}
