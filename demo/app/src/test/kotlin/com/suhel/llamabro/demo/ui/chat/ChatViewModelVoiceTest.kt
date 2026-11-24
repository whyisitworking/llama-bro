package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.chat.VoiceChat
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.voice.DictationRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bar's audio: the mic, voice messages, dictation (D140) and voice mode (D100). */
class ChatViewModelVoiceTest : ChatViewModelFixture() {
    @Test fun theMicShowsOnlyForAModelThatHears() =
        runBlocking {
            settings.setHears(swift.toString(), true)
            val hearing = viewModel(recorder = microphone(1))
            eventually("the mic") { hearing.composerState.value.showsMic }
            val deaf = viewModel(model = plain, recorder = microphone(1))
            eventually("the model is known") {
                deaf.hears.value == false &&
                    deaf.handle.name.value
                        .isNotEmpty()
            }
            assertFalse(deaf.composerState.value.canRecord)
        }

    @Test fun voiceModeShowsForAModelThatTalksAndOpensInANewChat() =
        runBlocking {
            val omni = Catalog.suggested.first { it.talks }.key
            settings.setVoices(omni.toString(), listOf("female"))
            val started = mutableListOf<VoiceChat>()
            val talking = viewModel(model = omni, voiceMode = voiceModeFor(started))
            eventually("the voice-mode button") { talking.composerState.value.showsTalk }
            talking.voiceMode.open()
            eventually("voice mode opens") { talking.voiceMode.open.value != null }
            assertFalse(
                "a new chat has no earlier messages",
                talking.voiceMode.open.value!!
                    .startsFresh,
            )
            val chat = started.single()
            assertEquals(omni, chat.key)
            assertTrue("the new chat was made", chat.chatId != 0L)
            assertEquals(chat.chatId, talking.handle.chatId.value)
            talking.voiceMode.close()
            assertNull(talking.voiceMode.open.value)

            val quiet = viewModel(voiceMode = voiceModeFor(started))
            eventually("the model is known") {
                quiet.handle.name.value
                    .isNotEmpty()
            }
            assertFalse(quiet.composerState.value.canTalk)
        }

    @Test fun dictationWritesTheWordsAfterWhatWasTypedAndTheHintGoes() =
        runBlocking {
            fake.supportsAudio = true
            fake.streamsAudio = true
            fake.transcription("call the bank tomorrow.")
            settings.setHears(swift.toString(), true)
            val vm = viewModel(recorder = microphone(1), dictating = 10)
            eventually("the mic and its hint") { vm.composerState.value.showsMic && vm.composerState.value.micHint }
            vm.onText("Remind me to")
            vm.dictation!!.start()
            eventually("listening") { vm.composerState.value.dictation != null }
            // the mic leaves while dictating: neither a voice message nor voice mode can start
            assertFalse(vm.composerState.value.showsMic)
            eventually("the voice was heard") {
                vm.dictation!!
                    .shown.value
                    ?.dictation
                    ?.millis == 500L + QUIET_BLOCKS * 50
            }
            // the words are scripted for the live session: finished while it still opens, the dictation drops it and
            // transcribes the whole clip instead (D140), which this fake has no transcript for
            eventually("the live session") {
                vm.dictation!!
                    .shown.value
                    ?.dictation
                    ?.route == DictationRoute.Live
            }
            vm.dictation!!.finish()
            eventually("the words in the draft") { vm.composer.value.text == "Remind me to call the bank tomorrow." }
            eventually("the bar is back") { vm.composerState.value.dictation == null }
            eventually("the hint is gone") { !vm.composerState.value.micHint }
        }

    @Test fun discardingADictationLeavesTheDraftAsItWas() =
        runBlocking {
            fake.supportsAudio = true
            fake.streamsAudio = true
            settings.setHears(swift.toString(), true)
            val vm = viewModel(recorder = microphone(1), dictating = 400)
            eventually("the mic") { vm.composerState.value.canRecord }
            vm.onText("Hello")
            vm.dictation!!.start()
            eventually("listening") {
                vm.composerState.value.dictation
                    ?.preparing == false
            }
            vm.dictation!!.discard()
            eventually("the bar is back") { vm.composerState.value.dictation == null }
            assertEquals("Hello", vm.composer.value.text)
        }

    @Test fun aRecordingIsSentAsAVoiceMessageAndLeavesTheBar() =
        runBlocking {
            fake.supportsAudio = true
            settings.setHears(swift.toString(), true)
            reply("I hear you")
            val vm = viewModel(recorder = microphone(10))
            eventually("the mic") { vm.composerState.value.showsMic }
            val voice = vm.voice!!
            voice.start()
            // the recorder's take is the bar's source: the bar's own state is drawn from it each frame
            eventually("the recording stopped and waits") { voice.take.value?.live == false }
            assertEquals("0:01", ChatViewModel.recordingState(voice.take.value!!).elapsed)
            voice.send()
            eventually("the voice message") { vm.rows.value.any { it is UserItem && it.voice != null } }
            eventually("the take is let go") { voice.take.value == null }
            val sent = (vm.rows.value.first { it is UserItem } as UserItem).voice!!
            assertEquals("0:01", sent.length)
            assertTrue(sent.file.exists())
            eventually("the reply") { vm.rows.value.any { it is ReplyItem && it.text == "I hear you" } }
        }

    @Test fun discardingARecordingDeletesItAndARefusedMicSaysWhereToAllowIt() =
        runBlocking {
            settings.setHears(swift.toString(), true)
            val vm = viewModel(recorder = microphone(2))
            eventually("the mic") { vm.composerState.value.showsMic }
            vm.voice!!.refused()
            eventually("the line") { vm.limit.value?.action == LimitAction.OpenAppSettings }
            vm.voice!!.start()
            eventually("the line goes once recording starts") { vm.limit.value == null }
            eventually("the recording stopped") {
                vm.voice!!
                    .take.value
                    ?.live == false
            }
            vm.voice!!.discard()
            eventually("the take is let go") { vm.voice!!.take.value == null }
            val folder = audio.file("x").parentFile!!
            eventually("the recording is deleted") { folder.listFiles().orEmpty().isEmpty() }
        }
}
