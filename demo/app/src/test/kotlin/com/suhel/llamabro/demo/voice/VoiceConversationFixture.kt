package com.suhel.llamabro.demo.voice

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.audio.SessionFocus
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.data.VoiceTurns
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.Locale

/** Voice mode's conversation (D100) on FakeLlamaBro's voice session, with a scripted microphone and speaker. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class VoiceConversationFixture {
    @get:Rule val folder = TemporaryFolder()

    @get:Rule val timeout: Timeout = Timeout.seconds(60)
    protected val opened = mutableListOf<VoiceConversation>()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    protected val omni = HfModel.parse("ggml-org/LFM3-Omni-2B-GGUF:Q4_K_M")

    protected val fake =
        FakeLlamaBro().apply {
            supportsAudio = true
            voices = listOf("female", "male")
        }
    protected val testHub = TestHub()
    protected val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }

    // no folder yet, as on a phone where nothing was recorded before voice mode
    protected val audio by lazy { AudioStore(File(folder.root, "audio")) }
    protected val chats by lazy {
        ChatRepository(FakeChatDao(), ImageStore(File(folder.root, "images")), System::currentTimeMillis, audio)
    }
    protected val host by lazy { ModelHost(fake.llamaBro, { testHub.hub.installedModel(it) }, scope) }
    protected val models by lazy { ModelRepository(testHub.hub, settings, FakeScheduler(), scope, host) }
    protected val engine by lazy { ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope) }

    protected val mic = ScriptedMic()
    protected val speaker = ScriptedSpeaker()
    protected var micFails = false
    protected var language: Locale = Locale.ENGLISH

    @After fun stop() {
        opened.forEach { it.close() }
        runBlocking { withTimeoutOrNull(5_000) { host.release() } }
        scope.cancel()
        testHub.close()
    }

    protected suspend fun conversation(
        focus: SessionFocus = SessionFocus.None,
        dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    ): Pair<Long, VoiceConversation> {
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
                dispatcher = dispatcher,
                locale = { language },
                focus = focus,
            )
        opened += voice
        return chatId to voice
    }

    protected suspend fun rows(chatId: Long): List<StoredMessage> = chats.messages(chatId).first()

    /** Holds the talk button for half a second of speech: ten 50 ms blocks, well over a tap. */
    protected suspend fun say(voice: VoiceConversation) {
        mic.reads.set(0)
        mic.allow(10)
        voice.talkStart()
        eventually("the microphone is heard") { mic.reads.get() >= 10 }
        voice.talkEnd()
    }

    protected fun sentTypes() = fake.realtimeEvents.map { it.substringAfter("\"type\":\"").substringBefore('"') }

    /** Hands-free voice mode (D147), captions off so that each turn is one commit and one row. */
    protected suspend fun handsFree() {
        settings.voice.setCaptionYou(false)
        settings.voice.setVoiceTurns(VoiceTurns.HandsFree)
    }

    protected fun commits() = sentTypes().count { it == "input_audio_buffer.commit" }

    protected fun appends() = sentTypes().count { it == "input_audio_buffer.append" }

    /** A quiet room, then speech, then the pause that ends it: one hands-free turn. */
    protected fun scriptATurn() {
        mic.script(QUIET, 6)
        mic.script(SPEECH, 10)
        mic.script(QUIET, 20)
    }

    companion object {
        /** A block's length in milliseconds. */
        const val BLOCK = 50L

        /** As voice mode keeps before a turn starts: 700 ms. */
        const val BEFORE_BLOCKS = 14

        /** About -50 dBFS: a quiet room. */
        const val QUIET: Short = 100

        /** What the speaker plays loud, about -5 dBFS, and its echo through the microphone, about -15 dBFS. */
        const val LOUD_PLAYBACK = 0.9f
        const val ECHO: Short = 5_800

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
