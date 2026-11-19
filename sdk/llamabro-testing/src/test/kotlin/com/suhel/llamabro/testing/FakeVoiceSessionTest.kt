package com.suhel.llamabro.testing

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.voice.VoiceEvent
import com.suhel.llamabro.voice.VoiceSession
import com.suhel.llamabro.voiceSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice session (D95) on [FakeLlamaBro]: the real `Model` and `VoiceSession` over a scripted conversation. */
class FakeVoiceSessionTest {
    private fun fake(script: FakeLlamaBro.() -> Unit = {}) = FakeLlamaBro(script).apply { supportsAudio = true }

    private suspend fun FakeLlamaBro.model(task: ModelTask = ModelTask.Generate, slots: Int = 1): Model =
        llamaBro.loadModel(ModelSource.file("/models/omni.gguf")) {
            this.task = task
            parallelSlots = slots
        }

    /** Events until [count] of them satisfy [last], e.g. both a reply's end and a caption. */
    private suspend fun VoiceSession.until(count: Int, last: (VoiceEvent) -> Boolean): List<VoiceEvent> =
        withTimeout(TIMEOUT) {
            val events = mutableListOf<VoiceEvent>()
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

    /** The events of one reply, from its start to its end. */
    private suspend fun VoiceSession.reply(): List<VoiceEvent> =
        withTimeout(TIMEOUT) {
            val events = mutableListOf<VoiceEvent>()
            this@reply
                .events
                .takeWhile {
                    events += it
                    it !is VoiceEvent.ReplyDone
                }.toList()
            events
        }

    @Test
    fun aSpokenTurnIsAnsweredInTextAndSpeech() =
        runBlocking {
            val speech = shortArrayOf(100, -100, 200)
            val fake =
                fake {
                    voiceReply {
                        text("Once ")
                        audio(speech)
                        text("upon a time.")
                    }
                }
            val model = fake.model()
            val session =
                model.voiceSession {
                    voice = "female"
                    language = "en"
                    instructions = "Answer the question."
                    maxOutputTokens = 256
                }
            assertEquals(16000, session.inputSampleRate)
            assertEquals(24000, session.outputSampleRate)
            assertEquals("female", session.voice)
            assertEquals("en", session.language)

            assertTrue(session.append(ShortArray(1600)))
            assertTrue(session.commit())
            val events = session.reply()
            assertEquals("item_1", (events[0] as VoiceEvent.TurnCommitted).itemId)
            val replyId = (events[1] as VoiceEvent.ReplyStarted).replyId
            assertEquals("Once ", (events[2] as VoiceEvent.ReplyText).delta)
            assertArrayEquals(speech, (events[3] as VoiceEvent.ReplyAudio).pcm)
            assertEquals("upon a time.", (events[4] as VoiceEvent.ReplyText).delta)
            assertTrue(events.drop(2).all { it.replyIdOrNull() == replyId })
            assertEquals(6, events.size)
            val done = events.last() as VoiceEvent.ReplyDone
            assertEquals("Once upon a time.", done.text)
            assertFalse(done.cancelled)

            val sent = fake.voiceEvents.map { it.substringAfter("\"type\":\"").substringBefore('"') }
            assertEquals(
                listOf("session.update", "input_audio_buffer.append", "input_audio_buffer.commit", "response.create"),
                sent,
            )
            assertTrue(fake.voiceEvents[0].contains(""""voice":"female","language":"en""""))
            assertTrue(fake.voiceEvents[0].contains(""""max_output_tokens":256"""))
            session.close()
            model.closeAndJoin()
        }

    @Test
    fun aCommitWithNoAudioAsksForNoReply() =
        runBlocking {
            val fake = fake()
            val model = fake.model()
            val session = model.voiceSession()
            assertTrue(session.commit())
            val error = withTimeout(TIMEOUT) { session.events.first() } as VoiceEvent.Error
            assertEquals("input_audio_buffer_commit_empty", error.type)
            assertFalse(fake.voiceEvents.any { it.contains("response.create") })
            session.close()
            model.closeAndJoin()
        }

    @Test
    fun turnsCanGoInBeforeOneReplyAndTextToo() =
        runBlocking {
            val fake =
                fake {
                    voiceReply { text("One.") }
                    voiceReply { text("Two.") }
                }
            val model = fake.model()
            val session = model.voiceSession()
            session.append(ShortArray(10))
            session.commit(respond = false)
            session.append(ShortArray(10), offset = 2, length = 5)
            session.commit(respond = false)
            session.respond()
            assertEquals("One.", (session.reply().last() as VoiceEvent.ReplyDone).text)
            session.sendText("And now?")
            val second = session.reply()
            assertTrue(second.first() is VoiceEvent.Other)
            assertEquals("Two.", (second.last() as VoiceEvent.ReplyDone).text)
            assertTrue(fake.voiceEvents.any { it.contains(""""input_text","text":"And now?"""") })
            session.close()
            model.closeAndJoin()
        }

    @Test
    fun rawEventsGoOutAndUnknownOnesComeBackAsOther() =
        runBlocking {
            val fake = fake()
            val model = fake.model()
            val session = model.voiceSession { extra = """{"tool_choice":"none"}""" }
            assertTrue(fake.voiceEvents[0].contains(""""tool_choice":"none""""))
            assertTrue(session.sendRaw("""{"type":"input_audio_buffer.clear"}"""))
            val error = withTimeout(TIMEOUT) { session.events.first() } as VoiceEvent.Error
            assertEquals("unknown event type: input_audio_buffer.clear", error.message)
            session.close()
            model.closeAndJoin()
        }

    @Test
    fun closingTheSessionEndsItsEventsAndItsSends() =
        runBlocking {
            val model = fake().model()
            val session = model.voiceSession()
            session.close()
            session.close()
            assertEquals(emptyList<VoiceEvent>(), withTimeout(TIMEOUT) { session.events.toList() })
            assertFalse(session.append(ShortArray(4)))
            assertFalse(session.commit())
            assertFalse(session.cancelReply())
            model.closeAndJoin()
        }

    @Test
    fun closingTheModelFailsTheSessionWithModelClosed() =
        runBlocking {
            val model = fake().model()
            val session = model.voiceSession()
            model.close()
            assertThrows(LlamaBroException.ModelClosed::class.java) {
                runBlocking { withTimeout(TIMEOUT) { session.events.toList() } }
            }
            assertFalse(session.respond())
            model.closeAndJoin()
        }

    @Test
    fun aModelThatCantHoldTheConversationFailsAtOpen() =
        runBlocking {
            val deaf = FakeLlamaBro().model()
            assertThrows(LlamaBroException.NotSupported::class.java) { runBlocking { deaf.voiceSession() } }
            deaf.closeAndJoin()

            val embedder = fake().model(ModelTask.Embedding())
            assertThrows(LlamaBroException.InvalidRequest::class.java) { runBlocking { embedder.voiceSession() } }
            embedder.closeAndJoin()
        }

    @Test
    fun aReplyWithNothingScriptedIsAnErrorEvent() =
        runBlocking {
            val model = fake().model()
            val session = model.voiceSession()
            session.respond()
            assertEquals("server_error", (withTimeout(TIMEOUT) { session.events.first() } as VoiceEvent.Error).type)
            session.close()
            model.closeAndJoin()
        }

    @Test
    fun aModelThatSpeaksListsItsVoices() =
        runBlocking {
            val speaking = fake().apply { voices = listOf("female", "male") }.model()
            assertEquals(listOf("female", "male"), speaking.info.voices)
            assertTrue(speaking.info.supportsSpeech)
            speaking.closeAndJoin()
            val quiet = fake().model()
            assertEquals(emptyList<String>(), quiet.info.voices)
            assertFalse(quiet.info.supportsSpeech)
            quiet.closeAndJoin()
        }

    private fun VoiceEvent.replyIdOrNull(): String? =
        when (this) {
            is VoiceEvent.ReplyText -> replyId
            is VoiceEvent.ReplyAudio -> replyId
            is VoiceEvent.ReplyDone -> replyId
            else -> null
        }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
