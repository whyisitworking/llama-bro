package com.suhel.llamabro.demo.voice

import com.suhel.llamabro.demo.chat.HostState
import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.letAWrongActionLand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Voice mode (D100): tap or hold to talk, the session, its focus and its replies. */
class VoiceConversationTest : VoiceConversationFixture() {
    @Test fun aSpokenTurnIsSavedWithItsCaptionAndTheSpokenReply() =
        runBlocking {
            fake.transcript("Tell me a story.")
            fake.voiceReply {
                text("Once upon ")
                audio(ShortArray(2_400) { 1_000 })
                text("a time.")
                audio(ShortArray(2_400) { -1_000 })
            }
            val (chatId, voice) = conversation()
            voice.open()
            eventually("the session opened") { voice.ui.value.voice == "female" }
            say(voice)
            eventually("the reply is saved") { rows(chatId).size == 2 }
            eventually("the caption is saved") { rows(chatId)[0].text.isNotEmpty() }
            eventually("back to ready") { voice.ui.value.phase == VoiceStage.Ready }

            val (user, reply) = rows(chatId)
            assertEquals(MessageKind.User, user.kind)
            assertEquals("Tell me a story.", user.text)
            assertTrue(user.voice!!.file.length() > 44)
            assertEquals(MessageKind.Assistant, reply.kind)
            assertEquals("Once upon a time.", reply.text)
            assertEquals(MessageState.Done, reply.state)
            assertEquals(200L, reply.voice?.millis) // 4,800 samples at 24 kHz
            assertEquals(4_800, speaker.played.get())
            assertEquals("Tell me a story.", voice.ui.value.userWords)
            assertEquals("Once upon a time.", voice.ui.value.caption)

            val setup = fake.realtimeEvents.first()
            assertTrue(setup, setup.contains(""""voice":"female","language":"en""""))
            assertTrue(setup, setup.contains(VoiceConversation.INSTRUCTION.substringBefore("'")))
            assertTrue(setup, setup.contains(""""max_output_tokens":1024"""))
            assertEquals(listOf("input_audio_buffer.commit", "response.create"), sentTypes().takeLast(2))
            voice.close()
        }

    @Test fun withoutCaptionsTheModelLoadsWithOneSlotAndTheTurnHasNoText() =
        runBlocking {
            settings.voice.setCaptionYou(false)
            fake.voiceReply { text("Hi.") }
            val (chatId, voice) = conversation()
            say(voice)
            eventually("the reply is saved") { rows(chatId).size == 2 }
            assertEquals("", rows(chatId)[0].text)
            assertNull(rows(chatId)[1].voice)
            assertEquals(1, (host.state.value as HostState.Ready).config.slots)
            voice.close()
        }

    @Test fun aTapIsNotATurn() =
        runBlocking {
            val (chatId, voice) = conversation()
            voice.open()
            eventually("open") { voice.ui.value.voice != null }
            // one 50 ms block at most, under a fifth of a second however late the release is handled
            mic.allow(1)
            voice.talkStart()
            voice.talkEnd()
            // Ready after the microphone closed is the tap's end; a turn would have gone to Thinking with a row
            eventually("the tap is over") { mic.closedOnce && voice.ui.value.phase == VoiceStage.Ready }
            assertTrue(rows(chatId).isEmpty())
            assertTrue("input_audio_buffer.commit" !in sentTypes())
            assertEquals(VoiceStage.Ready, voice.ui.value.phase)
            voice.close()
        }

    @Test fun aReleaseWhileTheModelLoadsEndsTheTurnRatherThanLeavingTheMicrophoneOn() =
        runBlocking {
            fake.loadMillis = 400
            val (chatId, voice) = conversation()
            mic.allow(1)
            voice.talkStart()
            voice.talkEnd()
            eventually("the model is held and the turn is over") {
                engine.replying.value && mic.closedOnce && voice.ui.value.phase == VoiceStage.Ready
            }
            assertTrue(rows(chatId).isEmpty())
            voice.close()
        }

    @Test fun stopEndsThePlaybackAndPressingTalkInterrupts() =
        runBlocking {
            fake.transcript("One.")
            fake.voiceReply { audio(ShortArray(4_800)) }
            speaker.holdDrain = CompletableDeferred()
            val (chatId, voice) = conversation()
            say(voice)
            eventually("speaking") { voice.ui.value.phase == VoiceStage.Speaking }
            voice.stop()
            eventually("ready") { voice.ui.value.phase == VoiceStage.Ready }
            assertTrue(speaker.stops.get() > 0)
            eventually("the reply is kept") { rows(chatId).size == 2 }
            voice.close()
        }

    @Test fun stopDropsTheSpeechStillQueued() =
        runBlocking {
            fake.transcript("One.")
            fake.voiceReply { repeat(5) { audio(ShortArray(4_800)) } }
            speaker.blockWrites = CountDownLatch(1)
            val (_, voice) = conversation()
            say(voice)
            eventually("speaking") { voice.ui.value.phase == VoiceStage.Speaking && speaker.played.get() > 0 }
            voice.stop()
            eventually("ready") { voice.ui.value.phase == VoiceStage.Ready }
            assertEquals("speech played on after Stop", 0, speaker.writesAfterStop.get())
            voice.close()
        }

    @Test fun pausingLetsTheModelGoAndTheNextTalkOpensAgain() =
        runBlocking {
            fake.transcript("First.")
            fake.voiceReply { text("A.") }
            fake.transcript("Second.")
            fake.voiceReply { text("B.") }
            val (chatId, voice) = conversation()
            say(voice)
            eventually("the first reply") { rows(chatId).size == 2 }
            voice.pause()
            eventually("the model is free to go") { !engine.replying.value }
            say(voice)
            eventually("the second reply") { rows(chatId).size == 4 }
            assertEquals(2, sentTypes().count { it == "session.update" })
            assertEquals(listOf("First.", "A.", "Second.", "B."), rows(chatId).map { it.text })
            voice.close()
        }

    @Test fun theChosenVoiceIsUsedWhenTheModelHasIt() =
        runBlocking {
            settings.voice.setVoice("male")
            language = Locale.FRENCH
            val (_, voice) = conversation()
            voice.open()
            eventually("open") { voice.ui.value.voice == "male" }
            assertTrue(fake.realtimeEvents.first().contains(""""voice":"male","language":"fr""""))
            voice.close()
        }

    @Test fun aVoiceTheModelLacksFallsBackToItsFirst() =
        runBlocking {
            settings.voice.setVoice("robot")
            val (_, voice) = conversation()
            voice.open()
            eventually("open") { voice.ui.value.voice == "female" }
            voice.close()
        }

    @Test fun aRefusedMicrophoneSaysSo() =
        runBlocking {
            micFails = true
            val (_, voice) = conversation()
            voice.talkStart()
            eventually("the line") { voice.limit.value != null }
            assertEquals(Limits.microphone(), voice.limit.value)
            assertEquals(VoiceStage.Ready, voice.ui.value.phase)
            voice.close()
        }

    @Test fun theBlockBeingReadAtTheReleaseIsPartOfTheTurn() =
        runBlocking {
            settings.voice.setCaptionYou(false)
            fake.voiceReply { text("Hi.") }
            val (chatId, voice) = conversation()
            val gate = CountDownLatch(1)
            mic.reads.set(0)
            mic.allow(10)
            mic.gate = gate
            val released = CountDownLatch(1)
            voice.stoppingListening = { released.countDown() }
            voice.talkStart()
            // ten blocks heard, and the eleventh being read when the button comes up
            assertTrue(mic.atGate.await(5, TimeUnit.SECONDS))
            voice.talkEnd()
            // the read returns only once the release has told the reader to stop: a reader cancelled there would drop
            // the block it was reading
            assertTrue(released.await(5, TimeUnit.SECONDS))
            gate.countDown()
            eventually("the turn is saved") { rows(chatId).isNotEmpty() }
            val clip = rows(chatId)[0].voice!!
            assertEquals(11, mic.reads.get())
            assertEquals(550L, clip.millis) // 11 blocks of 50 ms
            assertEquals(44L + 11 * 800 * 2, clip.file.length())
            assertEquals(11, sentTypes().count { it == "input_audio_buffer.append" })
            voice.close()
        }

    @Test fun tapToTalkOpensNoMicrophoneUntilAPressAndComingBackOpensNone() =
        runBlocking {
            val (_, voice) = conversation()
            voice.open()
            eventually("open") { voice.ui.value.voice != null }
            voice.resume()
            letAWrongActionLand()
            assertEquals(0, mic.opens.get())
            assertEquals(VoiceStage.Ready, voice.ui.value.phase)
            voice.close()
        }

    @Test fun theSessionHoldsOneFocusFromItsOpeningToItsEnd() =
        runBlocking {
            settings.voice.setInterruptByTalking(true)
            val focus = CountingFocus()
            val (_, voice) = conversation(focus)
            voice.open()
            eventually("open") { voice.ui.value.voice != null }
            assertEquals(1, focus.takes.get())
            assertEquals(true, focus.asked)
            voice.pause()
            eventually("released") { focus.releases.get() == 1 }
            voice.pause()
            voice.open()
            eventually("taken again") { focus.takes.get() == 2 }
            voice.close()
            eventually("released again") { focus.releases.get() == 2 }
            letAWrongActionLand()
            assertEquals(2, focus.releases.get())
        }

    @Test fun stoppingAReplyTheServerHasFinishedSendsNoCancel() =
        runBlocking {
            settings.voice.setCaptionYou(false)
            fake.voiceReply { audio(ShortArray(4_800)) }
            speaker.holdDrain = CompletableDeferred()
            val (chatId, voice) = conversation()
            say(voice)
            eventually("saved while it still plays") {
                rows(chatId).size == 2 && voice.ui.value.phase == VoiceStage.Speaking
            }
            voice.stop()
            eventually("ready") { voice.ui.value.phase == VoiceStage.Ready }
            assertTrue("response.cancel" !in sentTypes())
            assertTrue(speaker.stops.get() > 0)
            voice.close()
        }

    @Test fun theSpokenLanguageIsThePhonesWhenUpstreamNamesIt() {
        assertEquals("fr", VoiceConversation.spokenLanguage(Locale.FRANCE))
        assertEquals("ja", VoiceConversation.spokenLanguage(Locale.JAPAN))
        assertEquals("en", VoiceConversation.spokenLanguage(Locale.forLanguageTag("hi-IN")))
    }
}
