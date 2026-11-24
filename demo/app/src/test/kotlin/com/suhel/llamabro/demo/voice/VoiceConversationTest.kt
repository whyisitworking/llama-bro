package com.suhel.llamabro.demo.voice

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.audio.SampleSource
import com.suhel.llamabro.demo.audio.SessionFocus
import com.suhel.llamabro.demo.audio.SpeechOutput
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.chat.HostState
import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.data.VoiceTurns
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.letAWrongActionLand
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Voice mode's conversation (D100) on FakeLlamaBro's voice session, with a scripted microphone and speaker. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceConversationTest {
    @get:Rule val folder = TemporaryFolder()

    @get:Rule val timeout: Timeout = Timeout.seconds(60)
    private val opened = mutableListOf<VoiceConversation>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val omni = HfModel.parse("ggml-org/LFM3-Omni-2B-GGUF:Q4_K_M")

    private val fake =
        FakeLlamaBro().apply {
            supportsAudio = true
            voices = listOf("female", "male")
        }
    private val testHub = TestHub()
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }

    // no folder yet, as on a phone where nothing was recorded before voice mode
    private val audio by lazy { AudioStore(File(folder.root, "audio")) }
    private val chats by lazy {
        ChatRepository(FakeChatDao(), ImageStore(File(folder.root, "images")), System::currentTimeMillis, audio)
    }
    private val host by lazy { ModelHost(fake.llamaBro, { testHub.hub.installedModel(it) }, scope) }
    private val models by lazy { ModelRepository(testHub.hub, settings, FakeScheduler(), scope, host) }
    private val engine by lazy { ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope) }

    private val mic = ScriptedMic()
    private val speaker = ScriptedSpeaker()
    private var micFails = false
    private var language: Locale = Locale.ENGLISH

    @After fun stop() {
        opened.forEach { it.close() }
        runBlocking { withTimeoutOrNull(5_000) { host.release() } }
        scope.cancel()
        testHub.close()
    }

    private suspend fun conversation(focus: SessionFocus = SessionFocus.None): Pair<Long, VoiceConversation> {
        testHub.install(omni)
        models.changed()
        val chatId = chats.createChat(omni.toString())
        val voice =
            VoiceConversation(
                chatId,
                omni,
                "LFM3 Omni 2B",
                { slots, limit -> engine.holdModel(omni, "LFM3 Omni 2B", slots, limit) },
                settings,
                chats,
                audio,
                microphone = {
                    check(!micFails) { "the microphone could not be opened" }
                    mic.also { it.reopen() }
                },
                speech = { speaker },
                scope = scope,
                dispatcher = Dispatchers.Default.limitedParallelism(1),
                locale = { language },
                focus = focus,
            )
        opened += voice
        return chatId to voice
    }

    private suspend fun rows(chatId: Long): List<StoredMessage> = chats.messages(chatId).first()

    /** Holds the talk button for half a second of speech: ten 50 ms blocks, well over a tap. */
    private suspend fun say(voice: VoiceConversation) {
        mic.reads.set(0)
        mic.allow(10)
        voice.talkStart()
        eventually("the microphone is heard") { mic.reads.get() >= 10 }
        voice.talkEnd()
    }

    private fun sentTypes() = fake.realtimeEvents.map { it.substringAfter("\"type\":\"").substringBefore('"') }

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

    /** Hands-free voice mode (D147), captions off so that each turn is one commit and one row. */
    private suspend fun handsFree() {
        settings.voice.setCaptionYou(false)
        settings.voice.setVoiceTurns(VoiceTurns.HandsFree)
    }

    private fun commits() = sentTypes().count { it == "input_audio_buffer.commit" }

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

    private fun appends() = sentTypes().count { it == "input_audio_buffer.append" }

    /** A quiet room, then speech, then the pause that ends it: one hands-free turn. */
    private fun scriptATurn() {
        mic.script(QUIET, 6)
        mic.script(SPEECH, 10)
        mic.script(QUIET, 20)
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

    @Test fun theSpokenLanguageIsThePhonesWhenUpstreamNamesIt() {
        assertEquals("fr", VoiceConversation.spokenLanguage(Locale.FRANCE))
        assertEquals("ja", VoiceConversation.spokenLanguage(Locale.JAPAN))
        assertEquals("en", VoiceConversation.spokenLanguage(Locale.forLanguageTag("hi-IN")))
    }

    /**
     * A microphone giving a quiet tone in 50 ms blocks until it is closed, but only as many as the test [allow]s: how
     * long a press lasts is then the test's choice, not the speed of the machine running it.
     */
    private class ScriptedMic : SampleSource {
        override val rate = 16_000
        val reads = AtomicInteger()
        private val allowed = AtomicInteger()

        /** Blocks of a level each, read once the allowed ones are: a room, a voice, a pause, in order. */
        private val script = ConcurrentLinkedQueue<Short>()

        fun allow(blocks: Int) {
            allowed.set(blocks)
        }

        fun script(level: Short, blocks: Int) {
            repeat(blocks) { script.add(level) }
        }

        @Volatile private var closed = false

        /** How many times voice mode has opened it. */
        val opens = AtomicInteger()

        fun reopen() {
            closed = false
            opens.incrementAndGet()
        }

        /** With a gate, the first read past the allowed ones waits at it, then gives a block: one read under way. */
        @Volatile var gate: CountDownLatch? = null
        val atGate = CountDownLatch(1)

        override fun read(buffer: ShortArray): Int {
            if (closed) return -1
            if (allowed.getAndUpdate { maxOf(it - 1, 0) } == 0) {
                script.poll()?.let { level ->
                    reads.incrementAndGet()
                    buffer.fill(level)
                    return buffer.size
                }
                gate?.let {
                    gate = null
                    atGate.countDown()
                    it.await(5, TimeUnit.SECONDS)
                    reads.incrementAndGet()
                    buffer.fill(500)
                    return buffer.size
                }
                // an empty read, as AudioRecord gives, so the reader still notices it was told to stop
                Thread.sleep(5)
                return 0
            }
            reads.incrementAndGet()
            buffer.fill(500)
            return buffer.size
        }

        @Volatile var closedOnce = false

        /** How many times it has been closed: once for each open, however listening ended. */
        val closes = AtomicInteger()

        override fun close() {
            closed = true
            closedOnce = true
            closes.incrementAndGet()
        }
    }

    /**
     * A speaker that counts what it plays; [holdDrain] keeps a reply "playing" until completed, and [blockWrites] makes
     * each write after the first wait, as a full AudioTrack buffer does, until [stop] releases it.
     */
    private class ScriptedSpeaker : SpeechOutput {
        val played = AtomicInteger()
        val stops = AtomicInteger()
        private val writes = AtomicInteger()

        /** The player has written the whole reply and waits for it to play out. */
        val draining = CountDownLatch(1)

        /** Writes that came after a stop: speech that went on playing although the reply was stopped. */
        val writesAfterStop = AtomicInteger()

        @Volatile var holdDrain: CompletableDeferred<Unit>? = null

        @Volatile var blockWrites: CountDownLatch? = null

        override fun write(pcm: ShortArray) {
            if (stops.get() > 0) writesAfterStop.incrementAndGet()
            played.addAndGet(pcm.size)
            if (writes.incrementAndGet() > 1) blockWrites?.await(5, TimeUnit.SECONDS)
        }

        override val level: Float = 0.5f

        override suspend fun drain() {
            draining.countDown()
            holdDrain?.await()
        }

        override fun stop() {
            stops.incrementAndGet()
            holdDrain?.complete(Unit)
            blockWrites?.countDown()
        }

        override fun close() = Unit
    }

    /** A focus that counts what voice mode asks of it, given back only when taken, as the phone's is. */
    private class CountingFocus : SessionFocus {
        val takes = AtomicInteger()
        val releases = AtomicInteger()

        @Volatile var asked: Boolean? = null

        @Volatile private var held = false
        override val lost = false
        override val communication = false

        override fun take(communication: Boolean) {
            if (held) return
            held = true
            asked = communication
            takes.incrementAndGet()
        }

        override fun release() {
            if (!held) return
            held = false
            releases.incrementAndGet()
        }
    }

    private companion object {
        /** A block's length in milliseconds. */
        const val BLOCK = 50L

        /** As voice mode keeps before a turn starts: 700 ms. */
        const val BEFORE_BLOCKS = 14

        /** About -50 dBFS: a quiet room. */
        const val QUIET: Short = 100

        /** About -30 dBFS: a quiet word. */
        const val QUIET_YES: Short = 1_000

        /** About -12 dBFS: speech close to the phone. */
        const val SPEECH: Short = 8_000

        /** About -24 and -22 dBFS: a fan, a street. */
        const val NOISE: Short = 2_000
        const val LOUDER_NOISE: Short = 2_600

        /** About -4 dBFS: speech over the noise. */
        const val OVER_NOISE: Short = 20_000
    }
}
