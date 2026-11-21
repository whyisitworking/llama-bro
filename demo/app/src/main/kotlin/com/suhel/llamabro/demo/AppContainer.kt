package com.suhel.llamabro.demo

import android.annotation.SuppressLint
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.Microphone
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.chat.ModelMemory
import com.suhel.llamabro.demo.chat.SampleSource
import com.suhel.llamabro.demo.chat.SamplingStore
import com.suhel.llamabro.demo.chat.SpeechOutput
import com.suhel.llamabro.demo.chat.Streamer
import com.suhel.llamabro.demo.chat.TrackSpeech
import com.suhel.llamabro.demo.chat.recommendedCard
import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.TokenStore
import com.suhel.llamabro.demo.data.db.ChatDatabase
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadScheduler
import com.suhel.llamabro.demo.models.HubRepository
import com.suhel.llamabro.demo.models.ModelDiscovery
import com.suhel.llamabro.demo.models.ModelNotifications
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.WorkDownloadScheduler
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.hub.LlamaBroHub
import com.suhel.llamabro.hub.work.LlamaBroWorkerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Manual wiring (no DI framework). Collaborators arrive as constructor parameters with production defaults, so tests
 * inject fakes or fresh files; later tasks add the engines.
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
     * Voice mode's microphone (D100): the voice call's source, whose echo cancellation keeps the reply out of it;
     * opened only after the chat screen has the permission. The phone tests give scripted samples.
     */
    @SuppressLint("MissingPermission")
    val voiceMicrophone: () -> SampleSource = {
        Microphone(app, android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    },
    /** Where voice mode speaks a reply, at its sample rate (D100); the phone tests play nothing. */
    val speech: (rate: Int) -> SpeechOutput = { TrackSpeech(app, it) },
) {
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
            try {
                chats.recoverInterrupted()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w("AppContainer", "could not recover interrupted replies: ${e.javaClass.simpleName}")
            }
        }

    /** Starts the recovery of interrupted replies at launch; the first turn waits for it either way. */
    fun recoverInterrupted() {
        recovery.start()
    }

    /** Runs every chat (one handle per open chat); the chat screens read it. */
    val engine: ChatEngine =
        if (streamer != null) {
            ChatEngine(
                chats,
                host,
                models,
                settings,
                clock,
                scope,
                streamer = streamer,
                beforeTurn = recovery::await,
                card = { sampling.card(it) },
            )
        } else {
            ChatEngine(chats, host, models, settings, clock, scope, beforeTurn = recovery::await) { sampling.card(it) }
        }

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

    /** For tests that build a container per test: stops DataStore's scope and closes the database. */
    fun close() {
        scope.cancel()
        // a write already under way (a stopped reply is saved even when cancelled) must finish before the database
        // closes; the turns end first, so the model's leases are released and closing it doesn't wait for a reply
        runBlocking { scope.coroutineContext[Job]?.join() }
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
