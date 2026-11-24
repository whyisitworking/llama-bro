package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatRequest
import com.suhel.llamabro.chat.Message
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D103: a successful reply the SDK can't read fails the call with a `LlamaBroException`, not a parser's exception. */
class UnreadableReplyTest {
    private suspend fun model(engine: FakeEngine, task: ModelTask = ModelTask.Generate) =
        testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf")) { this.task = task }

    private fun answer(engine: FakeEngine, vararg chunks: String) {
        engine.model.onRequest = { r ->
            chunks.forEach { r.chunk(it) }
            r.done()
        }
    }

    private suspend fun assertUnreadable(call: suspend () -> Unit) {
        val e =
            try {
                call()
                null
            } catch (e: LlamaBroException.ServerError) {
                e
            }
        requireNotNull(e) { "expected ServerError" }
        assertEquals(200, e.httpStatus)
        assertTrue(e.message, e.message!!.contains("can't read"))
    }

    @Test
    fun anEmptyEmbeddingsBodyIsAServerError() =
        runTest {
            val engine = FakeEngine()
            answer(engine)
            val m = model(engine, ModelTask.Embedding())
            assertUnreadable { m.embed("hello") }
        }

    @Test
    fun aRerankReplyWithoutResultsIsAServerError() =
        runTest {
            val engine = FakeEngine()
            answer(engine, """{"model":"m"}""")
            val m = model(engine, ModelTask.Rerank)
            assertUnreadable { m.rerank("q", listOf("a")) }
        }

    @Test
    fun aMalformedChatChunkFailsTheFlowWithAServerError() =
        runTest {
            val engine = FakeEngine()
            answer(engine, "data: {\"choices\":[\n\n", "data: [DONE]\n\n")
            val m = model(engine)
            assertUnreadable { m.chat(ChatRequest { messages = listOf(Message.user("hi")) }).toList() }
        }

    @Test
    fun speechThatIsNotWavIsAServerError() =
        runTest {
            val engine = FakeEngine()
            answer(engine, "x".repeat(64))
            val m = model(engine)
            assertUnreadable { m.speak("Hello.").toList() }
        }

    @Test
    fun speakOnAModelThatDoesNotSpeakIsNotSupported() =
        runTest {
            val engine = FakeEngine()
            engine.model.onRequest = { r ->
                r.chunk(
                    """{"error":{"code":501,"message":"this server does not support audio generation",""" +
                        """"type":"not_supported_error"}}""",
                    status = 501,
                )
                r.done()
            }
            val m = model(engine)
            val e = runCatching { m.speak("Hello.").toList() }.exceptionOrNull()
            assertTrue("$e", e is LlamaBroException.NotSupported)
        }

    @Test
    fun speechEndingBeforeAnyAudioIsAServerError() =
        runTest {
            val engine = FakeEngine()
            answer(engine)
            val m = model(engine)
            assertUnreadable { m.speak("Hello.").toList() }
        }
}
