package com.suhel.llamabro.testing

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.transcriptionSession
import com.suhel.llamabro.voice.TranscriptionEvent
import com.suhel.llamabro.voice.TranscriptionSession
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcription session (D138) on [FakeLlamaBro]: the real `Model` and `TranscriptionSession` over upstream's
 * three outcomes, a live session, one per commit, and a refusal.
 */
class FakeTranscriptionSessionTest {
    private fun fake(streams: Boolean = true, live: Boolean = true, script: FakeLlamaBro.() -> Unit = {}) =
        FakeLlamaBro(script).apply {
            supportsAudio = true
            streamsAudio = streams
            transcribesLive = live
        }

    private suspend fun FakeLlamaBro.model(task: ModelTask = ModelTask.Generate): Model =
        llamaBro.loadModel(ModelSource.file("/models/omni.gguf")) { this.task = task }

    private suspend fun TranscriptionSession.until(count: Int, last: (TranscriptionEvent) -> Boolean) =
        withTimeout(TIMEOUT) {
            val events = mutableListOf<TranscriptionEvent>()
            var seen = 0
            this@until
                .events
                .takeWhile {
                    events += it
                    if (last(it)) seen++
                    seen < count
                }.toList()
            events
        }

    /** An event as a line, since the events' constructors are the SDK's own. */
    private fun TranscriptionEvent.line(): String =
        when (this) {
            is TranscriptionEvent.Committed -> "committed $itemId"
            is TranscriptionEvent.Text -> "text $itemId $delta"
            is TranscriptionEvent.Done -> "done $itemId $transcript"
            is TranscriptionEvent.Error -> "error $type"
            is TranscriptionEvent.Other -> "other $type"
        }

    private fun JsonObject.at(vararg path: String): JsonObject = path.fold(this) { o, k -> o[k]!!.jsonObject }

    @Test
    fun aLiveSessionTranscribesEachItemAndEndsWithoutACommit() =
        runBlocking {
            val fake =
                fake {
                    transcription("Hello there.")
                    transcription("How are you?")
                }
            fake.model().transcriptionSession().use { session ->
                assertTrue(session.live)
                assertTrue(session.append(ShortArray(1600)))
                assertTrue(session.commit())
                assertTrue(session.append(ShortArray(1600)))
                assertTrue(session.finish())
                val events = session.until(2) { it is TranscriptionEvent.Done }
                assertEquals(
                    listOf(
                        "committed item_1",
                        "text item_1 Hello there.",
                        "done item_1 Hello there.",
                        "text item_2 How are you?",
                        "done item_2 How are you?",
                    ),
                    events.map { it.line() },
                )
            }
        }

    @Test
    fun aConversationModelIsSwitchedToTranscribingPerCommit() =
        runBlocking {
            val fake = fake(live = false) { transcription("Good morning.") }
            fake
                .model()
                .transcriptionSession {
                    language = "en"
                    prompt = "Llama Bro"
                }.use { session ->
                    assertFalse(session.live)
                    session.append(ShortArray(1600))
                    session.commit()
                    val events = session.until(1) { it is TranscriptionEvent.Done }
                    assertEquals("done item_1 Good morning.", events.last().line())
                }
            // the setup upstream's client sends: a transcription session with the language and the hints
            val update = Json.parseToJsonElement(fake.voiceEvents.first()).jsonObject
            assertEquals("session.update", update["type"]!!.jsonPrimitive.content)
            val session = update.at("session")
            assertEquals("transcription", session["type"]!!.jsonPrimitive.content)
            val transcription = session.at("audio", "input", "transcription")
            assertEquals("en", transcription["language"]!!.jsonPrimitive.content)
            assertEquals("Llama Bro", transcription["prompt"]!!.jsonPrimitive.content)
        }

    @Test
    fun aPerCommitSessionRefusesACommitWithNoAudioAndGoesOn() =
        runBlocking {
            val fake = fake(live = false) { transcription("Still here.") }
            fake.model().transcriptionSession().use { session ->
                assertFalse(session.hasUncommittedAudio)
                session.commit()
                session.append(ShortArray(1600))
                assertTrue(session.hasUncommittedAudio)
                session.finish()
                val events = session.until(1) { it is TranscriptionEvent.Done }
                assertEquals("input_audio_buffer_commit_empty", (events.first() as TranscriptionEvent.Error).type)
                assertEquals("done item_1 Still here.", events.last().line())
            }
        }

    @Test
    fun aModelThatCannotStreamIsRefusedWithUpstreamsMessage() =
        runBlocking {
            val model = fake(streams = false).model()
            val e =
                assertThrows(LlamaBroException.NotSupported::class.java) {
                    runBlocking { model.transcriptionSession() }
                }
            assertTrue(e.message!!, e.message!!.contains("does not support realtime transcription"))
        }

    @Test
    fun aModelThatDoesNotHearIsRefusedAtOnce() =
        runBlocking {
            val model = FakeLlamaBro().model()
            assertThrows(LlamaBroException.NotSupported::class.java) {
                runBlocking { model.transcriptionSession() }
            }
            Unit
        }

    @Test
    fun aModelLoadedForAnotherTaskIsMisuse() =
        runBlocking {
            val model = fake().model(ModelTask.Embedding())
            assertThrows(IllegalStateException::class.java) {
                runBlocking { model.transcriptionSession() }
            }
            Unit
        }

    @Test
    fun theInputRateIsTheSessionsOwn() =
        runBlocking {
            fake().model().transcriptionSession { inputSampleRate = 48000 }.use {
                assertEquals(48000, it.inputSampleRate)
            }
            fake().model().transcriptionSession().use { assertEquals(16000, it.inputSampleRate) }
        }

    @Test
    fun aClosedSessionSendsNothing() =
        runBlocking {
            val session = fake().model().transcriptionSession()
            session.close()
            assertFalse(session.append(ShortArray(10)))
            assertFalse(session.commit())
            assertFalse(session.finish())
            assertEquals(emptyList<TranscriptionEvent>(), withTimeout(TIMEOUT) { session.events.toList() })
        }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
