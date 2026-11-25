package com.suhel.llamabro.demo

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.StatFs
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.demo.audio.MediaVoicePlayer
import com.suhel.llamabro.demo.audio.Microphone
import com.suhel.llamabro.demo.audio.SampleSource
import com.suhel.llamabro.demo.audio.SessionFocus
import com.suhel.llamabro.demo.audio.SpeechOutput
import com.suhel.llamabro.demo.audio.TrackSpeech
import com.suhel.llamabro.demo.audio.VoiceFocus
import com.suhel.llamabro.demo.audio.VoiceRecorder
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.chat.ModelMemory
import com.suhel.llamabro.demo.chat.SamplingStore
import com.suhel.llamabro.demo.chat.Streamer
import com.suhel.llamabro.demo.chat.VoiceChat
import com.suhel.llamabro.demo.chat.recommendedCard
import com.suhel.llamabro.demo.chat.sdkStream
import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.TokenStore
import com.suhel.llamabro.demo.data.db.ChatDatabase
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadScheduler
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.HubRepository
import com.suhel.llamabro.demo.models.ModelDiscovery
import com.suhel.llamabro.demo.models.ModelNotifications
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.models.WorkDownloadScheduler
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.demo.server.ServerKey
import com.suhel.llamabro.demo.server.ServerMode
import com.suhel.llamabro.demo.server.ServerSettings
import com.suhel.llamabro.demo.ui.Screens
import com.suhel.llamabro.demo.ui.chat.ChatAudio
import com.suhel.llamabro.demo.ui.chat.ChatSamplingViewModel
import com.suhel.llamabro.demo.ui.chat.ChatViewModel
import com.suhel.llamabro.demo.ui.chat.MessageStyle
import com.suhel.llamabro.demo.ui.chat.SwitcherViewModel
import com.suhel.llamabro.demo.ui.chat.messageStyle
import com.suhel.llamabro.demo.ui.models.ModelEntry
import com.suhel.llamabro.demo.ui.models.ModelSheetViewModel
import com.suhel.llamabro.demo.ui.models.ModelsViewModel
import com.suhel.llamabro.demo.ui.settings.SettingsViewModel
import com.suhel.llamabro.demo.ui.shell.ShellViewModel
import com.suhel.llamabro.demo.voice.Dictation
import com.suhel.llamabro.demo.voice.VoiceConversation
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.LlamaBroHub
import com.suhel.llamabro.hub.work.LlamaBroWorkerFactory
import com.suhel.llamabro.server.Advertise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.ZoneId

/**
 * Manual wiring (no DI framework). Collaborators arrive as constructor parameters with production defaults, so tests
 * inject fakes or fresh files; the repositories, the host and the chat engine are built here, once.
 */
class AppContainer(
    val app: DemoApp,
    val llamaBro: LlamaBro = LlamaBro.create(app, app.llamaBroConfig),
    /** The SDK's hub; tests pass `FakeLlamaBroHub`'s (D70, D75). */
    val hub: LlamaBroHub = LlamaBroHub.create(app, app.hubConfig),
    val scheduler: DownloadScheduler = WorkDownloadScheduler(lazy { WorkManager.getInstance(app) }),
    databaseName: String = "chats.db",
    settingsName: String = "settings",
    val clock: () -> Long = System::currentTimeMillis,
    val database: ChatDatabase = Room.databaseBuilder(app, ChatDatabase::class.java, databaseName).build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures),
    settingsStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { app.preferencesDataStoreFile(settingsName) },
    val tokenStore: TokenStore = TokenStore(app),
    discoveryOverride: ModelDiscovery? = null,
    /**
     * Whether Get asks for the notification permission first; the phone tests turn it off, since a test can't answer
     * the system's dialog.
     */
    val asksForNotifications: Boolean = true,
    val images: ImageStore = ImageStore(File(app.filesDir, "images")),
    /** How a turn reaches the model; the phone tests script timing here, the app leaves it. */
    streamer: Streamer? = null,
    /** Voice messages' recordings (D76). */
    val audio: AudioStore = AudioStore(File(app.filesDir, "audio")),
    /**
     * Where a voice message's samples come from: the microphone, opened only after the chat screen has the permission
     * (D76); the phone tests give scripted samples.
     */
    @SuppressLint("MissingPermission") val microphone: () -> SampleSource = { Microphone(app) },
    /**
     * Voice mode's microphone (D100): the voice call's source, whose echo cancellation keeps the reply out of it, under
     * the session's focus (D147); opened only after the chat screen has the permission. The phone tests give scripted
     * samples.
     */
    @SuppressLint("MissingPermission")
    val voiceMicrophone: (SessionFocus) -> SampleSource = {
        Microphone(app, android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, it)
    },
    /** Where read-aloud speaks, at its sample rate (D141); the phone tests play nothing. */
    val speech: (rate: Int) -> SpeechOutput = { TrackSpeech(app, it) },
    /** Where voice mode speaks a reply, at its sample rate, under the session's focus (D100, D147). */
    val voiceSpeech: (
        rate: Int,
        focus: SessionFocus,
    ) -> SpeechOutput = { rate, focus -> TrackSpeech(app, rate, focus) },
    /** Voice mode's one claim on the phone's audio for a session (D147); the phone tests take none. */
    val voiceFocus: () -> SessionFocus = { VoiceFocus(app) },
) : Screens {
    /** The app's own scope: work that outlives a screen or a view model runs here, never in an ad-hoc scope. */
    val appScope: CoroutineScope get() = scope

    val chats: ChatRepository = ChatRepository(database.chatDao(), images, clock, audio)
    val settings: SettingsRepository = SettingsRepository(settingsStore)
    val host: ModelHost = ModelHost(llamaBro, { hub.installedModel(it) }, scope)
    val models: ModelRepository =
        ModelRepository(hub, settings, scheduler, scope, host, chats.lastUsed(), recordsDao = database.hubCacheDao())

    /**
     * A reply left `Streaming` by a dead process becomes `Stopped` (Review focus 2). Once per process, before the
     * engine's first turn: run any later and it would mark a reply this process has just started as stopped.
     */
    private val recovery =
        scope.async(start = CoroutineStart.LAZY) {
            step("recover interrupted replies") { chats.recoverInterrupted() }
            step("sweep orphan files") { chats.sweepOrphans() }
        }

    private suspend fun step(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w("AppContainer", "could not $what: ${e.javaClass.simpleName}")
        }
    }

    /** Starts the recovery of interrupted replies at launch; the first turn waits for it either way. */
    fun recoverInterrupted() {
        recovery.start()
    }

    /**
     * Server mode (docs/design/server.md §3): the held model served to the Wi-Fi behind a key kept with the settings,
     * announced as "Llama Bro".
     */
    val server: ServerMode =
        ServerMode(
            host,
            settings,
            ServerKey(ServerSettings(settingsStore)),
            scope,
            advertise = Advertise("Llama Bro", app),
            clock = clock,
        )

    /** Runs every chat (one handle per open chat); the chat screens read it. */
    val engine: ChatEngine =
        ChatEngine(
            chats,
            host,
            models,
            settings,
            clock,
            scope,
            streamer = streamer ?: ::sdkStream,
            beforeTurn = recovery::await,
            card = { sampling.card(it) },
            served = { server.served.value },
        )

    /** The loaded model as Settings › Memory and the model sheet show it, with Unload (D59). */
    val memory: ModelMemory by lazy { ModelMemory(host, engine, models.library) }

    /**
     * Each model's and each chat's sampling values (D60), with the hub's for models the catalog lacks (D79), looked up
     * in the background on the app's scope.
     */
    val sampling: SamplingStore by lazy { SamplingStore(settings, chats, { hub.recommendedCard(it) }, scope) }

    /** The SDK's search, a repo's files and a model's fit; the phone tests pass a fake in [discoveryOverride]. */
    val discovery: ModelDiscovery =
        discoveryOverride ?: ModelDiscovery(hub)

    /** The Hub's facts kept in Room (docs/design/models-page.md §1): screens read it and refresh on open. */
    val hubCache: HubRepository =
        HubRepository(
            discovery,
            database.hubCacheDao(),
            scope,
            { writes -> database.withTransaction { writes() } },
            clock,
        )

    /**
     * Creates the SDK's download worker on this container's hub (D75), so a download runs with the app's token and
     * its progress is the hub's [ModelRepository] reads; the notifications speak in the app's words.
     */
    val workers: WorkerFactory by lazy { LlamaBroWorkerFactory(hub, ModelNotifications()) }

    @Volatile private var facts: DeviceFacts? = null

    /**
     * The memory budget (half the phone's RAM, D101) and chips, read from the SDK the first time a screen asks; the
     * chip fits use is the one chosen in Settings › Memory when this phone has it (D72).
     */
    suspend fun deviceFacts(): DeviceFacts =
        (facts ?: DeviceFacts.read(llamaBro).also { facts = it }).preferring(
            settings.settings
                .first()
                .chip.kind,
        )

    /**
     * Reads the phone's facts once, in the background, as the app starts: they are local, but the first read asks
     * the SDK for its devices (~800 ms on the phone), and the Models page and a model's card wait for them to draw
     * their fit.
     */
    fun readDeviceFacts() {
        scope.launch(Dispatchers.Default) { deviceFacts() }
    }

    // ---- the screens' view models (see [Screens]) -----------------------------------------------------------------

    override fun shellViewModel(saved: SavedStateHandle) =
        ShellViewModel(chats, settings, models, savedState = saved, deleteChat = { engine.delete(it) })

    override fun settingsViewModel() = SettingsViewModel(settings, tokenStore, models, memory, sampling, ::deviceFacts)

    override fun modelsViewModel(initialQuery: String, initialIntro: Boolean?, saved: SavedStateHandle) =
        ModelsViewModel(
            hub = hubCache,
            models = models,
            facts = ::deviceFacts,
            freeBytes = { StatFs(app.filesDir.path).availableBytes },
            firstRunSeen = settings.settings.map { it.seenFirstRun },
            markFirstRunSeen = settings::markFirstRunSeen,
            startChat = { key ->
                // as first run's Get did: new chats use the model, and this one is made on it
                settings.setLastModel(key.toString())
                chats.createChat(key.toString())
            },
            initialQuery = initialQuery,
            initialIntro = initialIntro,
            clock = clock,
            zone = ZoneId.systemDefault(),
            io = Dispatchers.IO,
            savedState = saved,
            searchFilters = settings.settings.map { it.searchFilters }.distinctUntilChanged(),
            saveSearchFilters = settings::updateSearchFilters,
        )

    override fun modelSheetViewModel(row: ModelEntry, onExactFit: (FitView) -> Unit, startTier: SizeTier?) =
        ModelSheetViewModel(
            row,
            hubCache,
            models,
            ::deviceFacts,
            onExactFit = onExactFit,
            startTier = startTier,
            asksForNotifications = asksForNotifications,
        )

    override fun switcherViewModel(handle: ChatEngine.ChatHandle) =
        SwitcherViewModel(
            library = models.library,
            // the estimates follow the chip chosen in Settings › Memory (D72), which the other pane can change
            // meanwhile
            facts =
                settings.settings
                    .map { it.chip }
                    .distinctUntilChanged()
                    .map { deviceFacts() },
            inMemory = memory.inMemory,
            replying = memory.replying,
            chatId = handle.chatId,
            chatSampling = chats::sampling,
            pinDefault = settings::pinDefault,
            unloadModel = { memory.unload() },
            background = scope,
        )

    override fun chatSamplingViewModel(chatId: Long, key: HfModel) =
        ChatSamplingViewModel(
            layers = sampling.chat(key, chatId),
            setKnob = { knob, value -> sampling.setChat(chatId, knob, value) },
            resetAll = { sampling.resetChat(chatId) },
            background = scope,
        )

    override fun chatViewModel(
        chatId: Long,
        startModel: HfModel?,
        styles: Flow<MessageStyle>?,
        saved: SavedStateHandle,
    ) = ChatViewModel(
        engine.open(chatId, startModel),
        styles ?: settings.settings.map { it.layout.messageStyle() },
        saveImage = { images.save(it, app.contentResolver) },
        draftThumbnail = { draftThumbnail(images.draftFile(it)) },
        discardImages = { images.deleteDrafts(it) },
        draftPictureExists = { images.draftFile(it).exists() },
        clock = clock,
        titles = { id ->
            if (id == 0L) emptyFlow() else chats.chats().map { all -> all.firstOrNull { it.id == id }?.title }
        },
        savedState = saved,
        background = scope,
        audio = chatAudio(),
    )

    /**
     * The chat's audio features: the recorder, the player, voice mode, dictation and read-aloud (D76, D100, D140,
     * D141).
     */
    private fun chatAudio() =
        ChatAudio(
            recorder = VoiceRecorder(audio, microphone, scope),
            player = MediaVoicePlayer(scope),
            voiceModeStart = ::voiceConversation,
            dictationStart = ::dictation,
            dictationLanguage = {
                settings.settings
                    .first()
                    .voice.dictationLanguage
            },
            micHint = settings.settings.map { !it.voice.seenMicHint },
            onMicHintSeen = { settings.voice.setSeenMicHint() },
            speech = speech,
            chosenVoice = {
                settings.settings
                    .first()
                    .voice.name
            },
        )

    private fun voiceConversation(chat: VoiceChat): VoiceConversation {
        val focus = voiceFocus()
        return VoiceConversation(
            chat.chatId,
            chat.key,
            chat.name,
            { slots, limit -> engine.holdModel(chat.key, chat.name, slots, limit) },
            settings,
            chats,
            audio,
            { voiceMicrophone(focus) },
            { voiceSpeech(it, focus) },
            scope,
            Dispatchers.Main.immediate,
            clock = clock,
            focus = focus,
        )
    }

    /** A dictation over [model] (D140): the microphone into a new recording in app storage, in [language]. */
    private fun dictation(model: Model, language: String?) =
        Dictation(model, microphone, audio.file(audio.newName()), scope, language)

    /** For tests that build a container per test: stops DataStore's scope and closes the database. */
    fun close() {
        scope.cancel()
        // a write already under way (a stopped reply is saved even when cancelled) must finish before the database
        // closes; the turns end first, so the model's leases are released and closing it doesn't wait for a reply
        runBlocking { scope.coroutineContext[Job]?.join() }
        // the server's lease too, or the release below would wait for it
        runBlocking { server.stop() }
        runBlocking { host.release() }
        database.close()
    }
}

/**
 * The app scope's last resort: background work that fails with nothing to catch it (a full disk, a database error) is
 * logged and ends only itself. Unhandled, it would reach the thread's handler and close the app.
 */
private val logFailures =
    CoroutineExceptionHandler { _, e -> Log.e("AppContainer", "background work failed", e) }

/** A draft picture, downscaled once more for its 64 dp preview (the saved one is at most 1024 px). */
private fun draftThumbnail(file: File): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inSampleSize = 2 } // half of 1024 px is sharp for 64 dp
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}
