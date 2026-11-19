package com.suhel.llamabro.voice

import com.suhel.llamabro.FakeEngine
import com.suhel.llamabro.FakeModel
import com.suhel.llamabro.FakeRequest
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.testLlamaBro
import com.suhel.llamabro.voiceSession
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a voice session ends when the server sends what it never should: typed, and never hanging. */
class VoiceSessionTest {
    private fun session(script: (FakeRequest) -> Unit) =
        runBlocking {
            val engine = FakeEngine()
            engine.model.infoJson = FakeModel.HEARING_INFO
            engine.model.onRequest = script
            testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
        }

    @Test
    fun audioThatIsNotBase64EndsTheEventsTyped() {
        val model =
            session { r ->
                r.chunk("""{"type":"session.updated","session":{"id":"s1"}}""")
                r.chunk("""{"type":"response.output_audio.delta","response_id":"r1","delta":"*not base64*"}""")
            }
        val failure =
            runBlocking {
                withTimeout(5_000) {
                    val session = model.voiceSession()
                    runCatching { session.events.toList() }.exceptionOrNull()
                }
            }
        assertTrue("expected InvalidRequest, got $failure", failure is LlamaBroException.InvalidRequest)
    }

    @Test
    fun serverEndingBeforeTheSetupFailsTheOpen() {
        val model = session { r -> r.done() }
        val failure =
            runBlocking { withTimeout(5_000) { runCatching { model.voiceSession() }.exceptionOrNull() } }
        assertTrue("expected InvalidRequest, got $failure", failure is LlamaBroException.InvalidRequest)
    }
}
