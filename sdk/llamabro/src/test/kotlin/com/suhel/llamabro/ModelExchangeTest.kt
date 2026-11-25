package com.suhel.llamabro

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Model.exchange`: llama-server's status and body bytes as the handler wrote them, errors included. */
class ModelExchangeTest {
    private val engine = FakeEngine()
    private val model = runBlocking { testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) }

    private fun collect(endpoint: Endpoint, body: String): List<RawPart> =
        runBlocking { model.exchange(endpoint, body.toByteArray()).toList() }

    private fun List<RawPart>.bodyText(): String =
        filterIsInstance<RawPart.Body>().joinToString("") { it.bytes.decodeToString() }

    @Test
    fun headThenBodyBytesUnchanged() {
        val first = "event: message_start\ndata: {\"type\":\"message_start\"}\n\n"
        val last = "data: [DONE]\n\n"
        engine.model.onRequest = {
            it.chunk(first)
            it.chunk(last)
            it.done()
        }
        val parts = collect(Endpoint.ChatCompletions, """{"stream":true}""")
        assertEquals(RawPart.Head(200, "text/event-stream"), parts[0])
        assertEquals(listOf(first, last), parts.drop(1).map { (it as RawPart.Body).bytes.decodeToString() })
    }

    @Test
    fun errorStatusIsAHeadNotAnException() {
        val error = """{"error":{"type":"invalid_request_error","code":400}}"""
        engine.model.onRequest = {
            it.chunk(error, status = 400)
            it.done()
        }
        val parts = collect(Endpoint.ChatCompletions, """{"stream":true}""")
        assertEquals(RawPart.Head(400, "application/json"), parts[0])
        assertEquals(error, parts.bodyText())
    }

    @Test
    fun wholeBodyIsJson() {
        engine.model.onRequest = {
            it.chunk("""{"a":""")
            it.chunk("1}")
            it.done()
        }
        val parts = collect(Endpoint.Props, "")
        assertEquals(RawPart.Head(200, "application/json"), parts[0])
        assertEquals("""{"a":1}""", parts.bodyText())
    }

    @Test
    fun metricsIsTextPlain() {
        engine.model.onRequest = {
            it.chunk("llamacpp:prompt_tokens_total 3\n")
            it.done()
        }
        val parts = collect(Endpoint.Metrics, "")
        assertEquals(RawPart.Head(200, "text/plain; version=0.0.4"), parts[0])
    }

    @Test
    fun anEmptyChunkStillGivesTheHeadOnce() {
        engine.model.onRequest = {
            it.chunk("")
            it.chunk("x")
            it.done()
        }
        val parts = collect(Endpoint.Props, "")
        assertEquals(1, parts.count { it is RawPart.Head })
        assertEquals("x", parts.bodyText())
        assertEquals(2, parts.size)
    }

    @Test
    fun cancelCancelsTheNativeRequest() =
        runBlocking {
            engine.model.onRequest = { it.chunk("data: {}\n\n") }
            model.exchange(Endpoint.ChatCompletions, """{"stream":true}""".toByteArray()).take(2).toList()
            val request = engine.model.requests.single()
            assertEquals(1, request.cancelCount)
            assertTrue(request.terminal)
        }

    @Test
    fun closedModelThrowsModelClosed() {
        model.close()
        val failure = runCatching { collect(Endpoint.Props, "") }.exceptionOrNull()
        assertTrue("expected ModelClosed, got $failure", failure is LlamaBroException.ModelClosed)
    }
}
