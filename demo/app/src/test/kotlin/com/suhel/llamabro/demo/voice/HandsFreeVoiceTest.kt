package com.suhel.llamabro.demo.voice

import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.letAWrongActionLand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Hands-free voice mode and talking over a reply (D147). */
class HandsFreeVoiceTest : VoiceConversationFixture() {
    @Test fun handsFreeATurnEndsAtThePauseAfterSpeechAndItListensAgain() =
        runBlocking {
            handsFree()
            fake.voiceReply { text("Hi.") }
            val (chatId, voice) = conversation()
            mic.script(QUIET, 6)
            mic.script(SPEECH, 10)
            mic.script(QUIET, 60)
            voice.open()
            eventually("the turn is sent and answered") { rows(chatId).size == 2 }
            assertEquals(1, commits())
            // the quiet before the speech, the speech and the pause that ended it, and the block read as it ended: not
            // the quiet the microphone read on ahead of the steps, which runs to 76 blocks
            val clip = rows(chatId)[0].voice!!
            assertTrue("${clip.millis}", clip.millis in 34 * BLOCK..36 * BLOCK)
            eventually("listening again") { mic.opens.get() == 2 && voice.ui.value.phase == VoiceStage.Listening }
            assertTrue(voice.ui.value.sendsOnPause)
            voice.close()
        }

    @Test fun handsFreeNoiseAloneStartsNoTurn() =
        runBlocking {
            handsFree()
            fake.voiceReply { text("Hi.") }
            val (chatId, voice) = conversation()
            // three seconds of a room wavering by 2 dB, then speech over it and the room again
            repeat(30) {
                mic.script(NOISE, 1)
                mic.script(LOUDER_NOISE, 1)
            }
            mic.script(OVER_NOISE, 10)
            mic.script(NOISE, 20)
            voice.open()
            eventually("the turn is sent") { rows(chatId).size == 2 }
            assertEquals(1, commits())
            // the turn began with the speech: what was kept before it, the speech, the room after; none of the wait
            val clip = rows(chatId)[0].voice!!
            assertTrue("${clip.millis}", clip.millis <= (BEFORE_BLOCKS + 5 + 20) * BLOCK)
            voice.close()
        }

    @Test fun handsFreeATapSendsTheTurnAtOnce() =
        runBlocking {
            handsFree()
            fake.voiceReply { text("Hi.") }
            val (chatId, voice) = conversation()
            mic.script(QUIET, 6)
            mic.script(SPEECH, 10)
            mic.script(QUIET, 3)
            voice.open()
            // the session hears the turn once its speech stands out from its own floor (the breath after it): every
            // block read, and no pause long enough to end it
            eventually("the turn started") { appends() == 19 }
            voice.talkEnd()
            eventually("sent and answered") { rows(chatId).size == 2 }
            assertEquals(1, commits())
            voice.close()
        }

    @Test fun handsFreeATapBeforeAnySpeechStopsListeningUntilTheNextTap() =
        runBlocking {
            handsFree()
            val (chatId, voice) = conversation()
            voice.open()
            eventually("listening by itself") { mic.opens.get() == 1 && voice.ui.value.phase == VoiceStage.Listening }
            voice.talkEnd()
            eventually("stopped") { mic.closedOnce && voice.ui.value.phase == VoiceStage.Ready }
            assertTrue(rows(chatId).isEmpty())
            assertEquals(0, commits())
            voice.talkStart()
            eventually("listening again") { mic.opens.get() == 2 && voice.ui.value.phase == VoiceStage.Listening }
            voice.close()
        }

    @Test fun withInterruptOffTalkingOverAReplyNeverStopsIt() =
        runBlocking {
            settings.voice.setCaptionYou(false)
            fake.voiceReply { audio(ShortArray(4_800)) }
            speaker.holdDrain = CompletableDeferred()
            val (chatId, voice) = conversation()
            say(voice)
            eventually("speaking") { voice.ui.value.phase == VoiceStage.Speaking }
            mic.script(QUIET, 12)
            mic.script(OVER_NOISE, 20)
            // the reply all written and its drain begun: an ear under it would have opened at the first write, and read
            // the script at once, while the reply still plays
            assertTrue(speaker.draining.await(5, TimeUnit.SECONDS))
            letAWrongActionLand()
            assertEquals(10, mic.reads.get())
            speaker.holdDrain?.complete(Unit)
            eventually("the reply is over") { voice.ui.value.phase == VoiceStage.Ready && rows(chatId).size == 2 }
            assertEquals(1, mic.opens.get())
            assertEquals(10, mic.reads.get())
            assertTrue("response.cancel" !in sentTypes())
            assertEquals(0, speaker.stops.get())
            voice.close()
        }

    @Test fun withInterruptOnTalkingOverAReplyStopsItAndIsTheNextTurn() =
        runBlocking {
            settings.voice.setCaptionYou(false)
            settings.voice.setInterruptByTalking(true)
            fake.voiceReply { audio(ShortArray(4_800)) }
            fake.voiceReply { text("Sure.") }
            speaker.holdDrain = CompletableDeferred()
            val (chatId, voice) = conversation()
            say(voice)
            eventually("speaking") { voice.ui.value.phase == VoiceStage.Speaking }
            // the echo cancellation's warm-up, then speech well over the room, then a pause
            mic.script(QUIET, 12)
            mic.script(OVER_NOISE, 10)
            mic.script(QUIET, 20)
            eventually("the second turn is answered") { rows(chatId).size == 4 }
            // the fake sends a reply whole, so it was only playing out: stopped here, with nothing for the server to cancel
            assertTrue("response.cancel" !in sentTypes())
            assertTrue(speaker.stops.get() > 0)
            assertEquals(2, commits())
            assertEquals(listOf("", "", "", "Sure."), rows(chatId).map { it.text })
            voice.close()
        }

    @Test fun handsFreeLeavingWhileListeningClosesTheMicrophoneOnceAndComingBackListensAgain() =
        runBlocking {
            handsFree()
            val (chatId, voice) = conversation()
            voice.open()
            eventually("listening") { mic.opens.get() == 1 && voice.ui.value.phase == VoiceStage.Listening }
            voice.pause()
            eventually("paused") { !engine.replying.value && mic.closes.get() == 1 }
            assertEquals(VoiceStage.Ready, voice.ui.value.phase)
            assertEquals(1, mic.opens.get())
            voice.resume()
            eventually("listening again") { mic.opens.get() == 2 && voice.ui.value.phase == VoiceStage.Listening }
            voice.close()
            eventually("closed") { !engine.replying.value && mic.closes.get() == 2 }
            letAWrongActionLand()
            assertEquals(2, mic.opens.get())
            assertEquals(0, commits())
            assertTrue(rows(chatId).isEmpty())
        }

    @Test fun handsFreeClosingMidTurnClosesTheMicrophoneOnceAndSendsNothing() =
        runBlocking {
            handsFree()
            val (chatId, voice) = conversation()
            mic.script(QUIET, 6)
            mic.script(SPEECH, 10)
            mic.script(QUIET, 3)
            voice.open()
            eventually("the turn is heard") { appends() == 19 }
            voice.close()
            eventually("closed") { !engine.replying.value && mic.closes.get() == 1 }
            letAWrongActionLand()
            assertEquals(1, mic.opens.get())
            assertEquals(0, commits())
            assertTrue(rows(chatId).isEmpty())
        }

    @Test fun handsFreeWithInterruptOnTheEarUnderAReplyListensOnOnTheSameMicrophone() =
        runBlocking {
            handsFree()
            settings.voice.setInterruptByTalking(true)
            // three pieces: the reply goes on playing after the ear opens under it
            fake.voiceReply { repeat(3) { audio(ShortArray(4_800)) } }
            speaker.holdDrain = CompletableDeferred()
            val (_, voice) = conversation()
            scriptATurn()
            voice.open()
            eventually("the ear under the reply") {
                voice.ui.value.phase == VoiceStage.Speaking && mic.opens.get() == 2
            }
            assertTrue(speaker.draining.await(5, TimeUnit.SECONDS))
            speaker.holdDrain?.complete(Unit)
            eventually("listening on") {
                voice.ui.value.phase == VoiceStage.Listening && voice.ui.value.sendsOnPause
            }
            assertEquals(2, mic.opens.get())
            assertEquals(1, mic.closes.get())
            voice.close()
        }

    @Test fun tapToTalkWithInterruptOnClosesTheEarUnderAReplyWhenItEnds() =
        runBlocking {
            settings.voice.setCaptionYou(false)
            settings.voice.setInterruptByTalking(true)
            fake.voiceReply { audio(ShortArray(4_800)) }
            speaker.holdDrain = CompletableDeferred()
            val (_, voice) = conversation()
            say(voice)
            eventually("the ear under the reply") {
                voice.ui.value.phase == VoiceStage.Speaking && mic.opens.get() == 2
            }
            speaker.holdDrain?.complete(Unit)
            eventually("ready, the ear closed") { voice.ui.value.phase == VoiceStage.Ready && mic.closes.get() == 2 }
            assertEquals(1, commits())
            voice.close()
        }

    @Test fun stoppingAReplyMidWriteDoesNotCutTheTurnThatInterruptedIt() =
        runBlocking {
            settings.voice.setCaptionYou(false)
            settings.voice.setInterruptByTalking(true)
            fake.voiceReply { repeat(5) { audio(ShortArray(4_800)) } }
            fake.voiceReply { text("Sure.") }
            speaker.blockWrites = CountDownLatch(1)
            val (chatId, voice) = conversation()
            say(voice)
            eventually("the ear under the reply") {
                voice.ui.value.phase == VoiceStage.Speaking && mic.opens.get() == 2
            }
            mic.script(QUIET, 12)
            mic.script(OVER_NOISE, 10)
            mic.script(QUIET, 20)
            eventually("the second turn is answered") { rows(chatId).size == 4 }
            // the speech that interrupted and its onset, and the pause that ended it, all on the one microphone
            val clip = rows(chatId)[2].voice!!
            assertTrue("${clip.millis}", clip.millis >= (10 + 18) * BLOCK)
            assertEquals(2, mic.opens.get())
            assertEquals(2, commits())
            voice.close()
        }

    @Test fun echoReadWhileTheReplyPlayedIsStillEchoWhenTheActorHearsItInAPause() =
        runBlocking {
            settings.voice.setCaptionYou(false)
            settings.voice.setInterruptByTalking(true)
            fake.voiceReply { audio(ShortArray(4_800)) }
            speaker.holdDrain = CompletableDeferred()
            speaker.level = LOUD_PLAYBACK
            val actor = Executors.newSingleThreadExecutor()
            try {
                val (_, voice) = conversation(dispatcher = actor.asCoroutineDispatcher())
                say(voice)
                eventually("the ear under the reply") {
                    voice.ui.value.phase == VoiceStage.Speaking && mic.opens.get() == 2
                }
                // the warm-up: the room, then the reply's echo 10 dB under what plays
                mic.script(QUIET, 2)
                mic.script(ECHO, 12)
                eventually("the warm-up read") { mic.reads.get() == 10 + 14 }
                letAWrongActionLand()
                // the actor busy while more echo is read under the loud reply, and heard only once the reply pauses
                val busy = CountDownLatch(1)
                actor.execute { busy.await(5, TimeUnit.SECONDS) }
                mic.script(ECHO, 30)
                eventually("the echo read") { mic.reads.get() == 10 + 44 }
                speaker.level = 0f
                busy.countDown()
                letAWrongActionLand()
                assertEquals(0, speaker.stops.get())
                assertEquals(VoiceStage.Speaking, voice.ui.value.phase)
                voice.close()
            } finally {
                actor.shutdown()
            }
        }

    @Test fun handsFreeStopListensAgain() =
        runBlocking {
            handsFree()
            fake.voiceReply { audio(ShortArray(4_800)) }
            speaker.holdDrain = CompletableDeferred()
            val (_, voice) = conversation()
            scriptATurn()
            voice.open()
            eventually("speaking") { voice.ui.value.phase == VoiceStage.Speaking }
            voice.stop()
            eventually("listening again") { voice.ui.value.phase == VoiceStage.Listening && mic.opens.get() == 2 }
            voice.close()
        }

    @Test fun handsFreeAPressHoldsTheTurnThroughAPauseAndTheReleaseSendsAQuietShortOne() =
        runBlocking {
            handsFree()
            fake.voiceReply { text("Hi.") }
            val (chatId, voice) = conversation()
            voice.open()
            eventually("listening by itself") { mic.opens.get() == 1 && voice.ui.value.phase == VoiceStage.Listening }
            voice.talkEnd()
            eventually("waiting for a tap") { mic.closes.get() == 1 && voice.ui.value.phase == VoiceStage.Ready }
            voice.talkStart()
            eventually("held") {
                mic.opens.get() == 2 && voice.ui.value.phase == VoiceStage.Listening && !voice.ui.value.sendsOnPause
            }
            // a "yes" too quiet and short to start a turn by itself, then a pause longer than the one that ends a turn
            mic.script(QUIET, 2)
            mic.script(QUIET_YES, 4)
            mic.script(QUIET, 30)
            eventually("all heard") { appends() == 36 }
            assertEquals(0, commits())
            voice.talkEnd()
            eventually("sent and answered") { rows(chatId).size == 2 }
            assertEquals(1, commits())
            voice.close()
        }

    @Test fun handsFreeThreeTurnsOfNoiseSendNothingAndStopListening() =
        runBlocking {
            handsFree()
            val (chatId, voice) = conversation()
            mic.script(QUIET, 20)
            // bursts long enough to start a turn, too short to be one
            repeat(3) {
                mic.script(SPEECH, 5)
                mic.script(QUIET, 25)
            }
            voice.open()
            eventually("given up") { mic.closes.get() == 1 && voice.ui.value.phase == VoiceStage.Ready }
            assertEquals(0, appends())
            assertEquals(0, commits())
            assertTrue(rows(chatId).isEmpty())
            voice.close()
        }

    @Test fun handsFreeTwoMinutesWithNoTurnStopListening() =
        runBlocking {
            handsFree()
            val (_, voice) = conversation()
            mic.script(QUIET, HandsFreeEar.IDLE_MS / HandsFreeEar.BLOCK_MS)
            voice.open()
            eventually("given up") { mic.closes.get() == 1 && voice.ui.value.phase == VoiceStage.Ready }
            assertEquals(0, commits())
            voice.close()
        }

    @Test fun handsFreeAfterAnErrorItListensAgain() =
        runBlocking {
            handsFree()
            // no reply scripted: the session answers the turn with an error
            val (_, voice) = conversation()
            scriptATurn()
            voice.open()
            eventually("the error said, and listening again") {
                voice.limit.value != null && voice.ui.value.phase == VoiceStage.Listening && mic.opens.get() == 2
            }
            voice.close()
        }
}
