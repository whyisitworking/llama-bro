package com.suhel.llamabro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelRawRequestTest {
    private val stream = """{"stream":true}"""

    private suspend fun model(engine: FakeEngine = FakeEngine()) = testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))

    @Test
    fun streamEmitsPayloadsUntilDone() = runTest {
        val engine = FakeEngine()
        engine.model.onRequest = { r ->
            r.chunk("data: {\"a\":1}\n\n")
            r.chunk("data: {\"a\":2}\n\n")
            r.chunk("data: [DONE]\n\n")
            r.done()
        }
        val out = model(engine).rawRequest(Endpoint.ChatCompletions, stream).toList()
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), out)
        assertTrue(engine.model.requests.single().freed)
        assertEquals("chat", engine.model.requests.single().endpoint)
    }

    @Test
    fun nonStreamEmitsWholeBody() = runTest {
        val engine = FakeEngine()
        engine.model.onRequest = { r -> r.chunk("""{"tokens":[1,2]}"""); r.done() }
        val out = model(engine).rawRequest(Endpoint.Tokenize, """{"content":"hi"}""").toList()
        assertEquals(listOf("""{"tokens":[1,2]}"""), out)
    }

    @Test
    fun paramsSentAsJson() = runTest {
        val engine = FakeEngine()
        engine.model.onRequest = { r -> r.chunk("{}"); r.done() }
        model(engine).rawRequest(Endpoint.SlotsAction, "{}", mapOf("id_slot" to "0", "action" to "save")).toList()
        assertEquals("""{"id_slot":"0","action":"save"}""", engine.model.requests.single().paramsJson)
    }

    @Test
    fun contextExceededMapsCounts() = runTest {
        val engine = FakeEngine()
        engine.model.onRequest = { r ->
            r.chunk("""data: {"error":{"code":400,"type":"exceed_context_size_error","message":"x","n_prompt_tokens":400,"n_ctx":256}}""" + "\n\n")
            r.done()
        }
        val m = model(engine)
        val e = runCatching { m.rawRequest(Endpoint.ChatCompletions, stream).toList() }.exceptionOrNull()
        assertTrue("$e", e is LlamaBroException.ContextExceeded)
        e as LlamaBroException.ContextExceeded
        assertEquals(400, e.promptTokens)
        assertEquals(256, e.contextSize)
    }

    @Test
    fun nonStream400MapsInvalidRequest() = runTest {
        val engine = FakeEngine()
        engine.model.onRequest = { r ->
            r.chunk("""{"error":{"code":400,"type":"invalid_request_error","message":"bad field"}}""", status = 400)
            r.done()
        }
        val m = model(engine)
        val e = runCatching { m.rawRequest(Endpoint.ChatCompletions, "{}").toList() }.exceptionOrNull()
        assertTrue("$e", e is LlamaBroException.InvalidRequest)
        assertEquals("bad field", e!!.message)
    }

    @Test
    fun serverErrorKeepsStatus() = runTest {
        val engine = FakeEngine()
        engine.model.onRequest = { r ->
            r.chunk("""{"error":{"code":500,"type":"server_error","message":"boom"}}""", status = 500)
            r.done()
        }
        val e = runCatching { model(engine).rawRequest(Endpoint.ChatCompletions, "{}").toList() }.exceptionOrNull()
        assertTrue("$e", e is LlamaBroException.ServerError)
        assertEquals(500, (e as LlamaBroException.ServerError).httpStatus)
    }

    @Test
    fun cancelStopsEmissions() = runTest {
        val engine = FakeEngine()
        engine.model.onRequest = { r -> r.chunk("data: {\"a\":1}\n\n") }  // then nothing until cancel
        val m = model(engine)
        val received = mutableListOf<String>()
        val first = CompletableDeferred<Unit>()
        val job = launch {
            m.rawRequest(Endpoint.ChatCompletions, stream).collect {
                received += it
                first.complete(Unit)
            }
        }
        first.await()
        job.cancel()
        job.join()
        val r = engine.model.requests.single()
        r.chunk("data: {\"a\":2}\n\n")  // late chunk from native: must not reach the collector
        yield()
        assertEquals(listOf("""{"a":1}"""), received)
        assertEquals(1, r.cancelCount)
        assertTrue(r.terminal)
        assertTrue(r.freed)
    }

    @Test
    fun noCancelAfterNormalCompletion() = runTest {
        val engine = FakeEngine()
        engine.model.onRequest = { r -> r.chunk("data: [DONE]\n\n"); r.done() }
        model(engine).rawRequest(Endpoint.ChatCompletions, stream).toList()
        assertEquals(0, engine.model.requests.single().cancelCount)
    }

    @Test
    fun closeDuringStream() = runTest {
        val engine = FakeEngine()
        engine.model.onRequest = { r -> r.terminateOnCancel = false; r.chunk("data: {\"a\":1}\n\n") }
        val m = model(engine)
        val first = CompletableDeferred<Unit>()
        val result = async {
            runCatching { m.rawRequest(Endpoint.ChatCompletions, stream).collect { first.complete(Unit) } }.exceptionOrNull()
        }
        first.await()
        m.close()
        val e = withTimeout(5_000) { result.await() }
        assertTrue("$e", e is LlamaBroException.ModelClosed)
        assertTrue(engine.model.closed)
        assertTrue(engine.model.requests.single().freed)
        val later = runCatching { m.rawRequest(Endpoint.ChatCompletions, stream).toList() }.exceptionOrNull()
        assertTrue("$later", later is LlamaBroException.ModelClosed)
    }

    @Test
    fun loadFailureMapsModelLoadFailed() = runTest {
        val engine = object : com.suhel.llamabro.internal.Engine by FakeEngine() {
            override fun load(configJson: String, onProgress: (Float) -> Unit): com.suhel.llamabro.internal.EngineModel =
                throw com.suhel.llamabro.internal.NativeError(4, """{"error":"failed to load model: /m.gguf"}""")
        }
        val e = runCatching { LlamaBro(com.suhel.llamabro.internal.LlamaBroRuntime(engine, tempDir(), "/b", LlamaBroConfig.Default)).loadModel(ModelSource.file("/m.gguf")) }.exceptionOrNull()
        assertTrue("$e", e is LlamaBroException.ModelLoadFailed)
        assertEquals("failed to load model: /m.gguf", e!!.message)
    }
}
