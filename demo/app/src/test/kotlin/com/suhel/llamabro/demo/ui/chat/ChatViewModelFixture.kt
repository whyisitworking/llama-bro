package com.suhel.llamabro.demo.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import com.suhel.llamabro.demo.audio.SampleSource
import com.suhel.llamabro.demo.audio.VoiceRecorder
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.chat.VoiceChat
import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.ModelSenses
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.demo.ui.shutDown
import com.suhel.llamabro.demo.voice.Dictation
import com.suhel.llamabro.demo.voice.VoiceConversation
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** Quiet reads of 50 ms before a steady voice starts. */
internal const val QUIET_BLOCKS = 4

/**
 * The chat screen's view model over a real engine, the fake SDK and the fake hub: what each of its tests sets up, and
 * the view models it builds ([viewModel]), shut down after each test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ChatViewModelFixture {
    @get:Rule val folder = TemporaryFolder()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    protected val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

    /** The catalog's model that can neither think nor see. */
    protected val plain: HfModel = Catalog.suggested.first { !it.thinks && !it.sees }.key

    protected val fake = FakeLlamaBro()
    protected val testHub = TestHub()
    protected val hub get() = testHub.hub
    protected val dao = FakeChatDao()
    protected val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    protected val audio by lazy { AudioStore(File(folder.root, "audio")) }
    protected val images by lazy { ImageStore(File(folder.root, "images")) }
    protected val chats by lazy {
        ChatRepository(
            dao,
            images,
            System::currentTimeMillis,
            audio,
        )
    }
    protected val host by lazy { ModelHost(fake.llamaBro, { hub.installedModel(it) }, scope) }
    protected val models by lazy { ModelRepository(hub, settings, FakeScheduler(), scope, host) }

    /** What the engine reads from a model's header before it loads (D155); set it before the first [viewModel]. */
    protected var readSenses: suspend (HfModel) -> ModelSenses? = { null }
    protected val engine by lazy {
        ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, readSenses = { readSenses(it) })
    }
    protected val viewModels = mutableListOf<ChatViewModel>()

    /** The draft pictures the view models built by [viewModel] deleted (from the draft folder, as the app does). */
    protected val discarded: MutableList<String> = Collections.synchronizedList(mutableListOf())

    protected val main = UnconfinedTestDispatcher()

    @Before fun setUpMain() = Dispatchers.setMain(main)

    @After fun stop() {
        shutDown(main, viewModels)
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    protected suspend fun viewModel(
        model: HfModel = swift,
        styles: Flow<MessageStyle> = flowOf(MessageStyle.Open),
        saved: SavedStateHandle = SavedStateHandle(),
        chatId: Long = 0,
        recorder: VoiceRecorder? = null,
        voiceMode: ((VoiceChat) -> VoiceConversation)? = null,
        dictating: Int? = null,
        draftPictureExists: (String) -> Boolean = { true },
    ): ChatViewModel {
        testHub.install(model)
        models.changed()
        settings.setLastModel(model.toString())
        return ChatViewModel(
            engine.open(chatId),
            styles,
            saveImage = { "pic.jpg" },
            discardImages = {
                images.deleteDrafts(it)
                discarded += it
            },
            draftThumbnail = { null },
            savedState = saved,
            background = scope,
            draftPictureExists = draftPictureExists,
            audio =
                ChatAudio(
                    recorder = recorder,
                    voiceModeStart = voiceMode,
                    dictationStart =
                        dictating?.let { blocks ->
                            { model, language ->
                                Dictation(
                                    model,
                                    steadyVoice(blocks),
                                    File(folder.root, "dictation.wav"),
                                    scope,
                                    language,
                                )
                            }
                        },
                    micHint = settings.settings.map { !it.voice.seenMicHint },
                    onMicHintSeen = { settings.voice.setSeenMicHint() },
                ),
        ).also(viewModels::add)
    }

    /** Remembers that [model] heard on its last load (D76); the rest as its catalog entry says. */
    protected suspend fun rememberHearing(model: HfModel) {
        val catalog = Catalog.byRepo(model.repo)
        settings.setSenses(model.toString(), ModelSenses(catalog?.thinks == true, catalog?.sees == true, hears = true))
    }

    /** A source that hears a steady voice for [blocks] reads of 50 ms, after a moment of quiet, then stops. */
    protected fun steadyVoice(blocks: Int): () -> SampleSource =
        {
            object : SampleSource {
                var reads = 0
                override val rate = 16_000

                override fun read(buffer: ShortArray): Int {
                    if (reads++ >= blocks + QUIET_BLOCKS) return -1
                    buffer.fill(if (reads > QUIET_BLOCKS) 4_000 else 0)
                    return buffer.size
                }

                override fun close() = Unit
            }
        }

    /** Voice mode that never loads a model: enough to see it open and close (D100). */
    protected fun voiceModeFor(started: MutableList<VoiceChat>): (VoiceChat) -> VoiceConversation =
        { chat ->
            started += chat
            VoiceConversation(
                chat.chatId,
                chat.key,
                chat.name,
                { _, _ -> null },
                settings,
                chats,
                audio,
                microphone = { error("no microphone in this test") },
                speech = { error("no speaker in this test") },
                scope = scope,
                dispatcher = Dispatchers.Default,
            )
        }

    /** What a process restored after its death hands the view model: the values saved, nothing live. */
    protected fun restored(saved: SavedStateHandle) =
        SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    protected fun reply(text: String) =
        fake.reply {
            content(text)
            timings(promptTokens = 10, predictedTokens = 20)
        }

    /** A microphone that hears a steady voice for [blocks] reads of 50 ms, then stops, as one taken by a call does. */
    protected fun microphone(blocks: Int) =
        VoiceRecorder(
            audio,
            {
                object : SampleSource {
                    var reads = 0
                    override val rate = 16_000

                    override fun read(buffer: ShortArray): Int {
                        if (reads++ >= blocks) return -1
                        buffer.fill(4_000)
                        return buffer.size
                    }

                    override fun close() = Unit
                }
            },
            scope,
        )
}
