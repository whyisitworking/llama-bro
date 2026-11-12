package com.suhel.llamabro.demo

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.TokenStore
import com.suhel.llamabro.demo.data.db.ChatDatabase
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadScheduler
import com.suhel.llamabro.demo.models.HfSearch
import com.suhel.llamabro.demo.models.HubGateway
import com.suhel.llamabro.demo.models.ModelDiscovery
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.RealHubGateway
import com.suhel.llamabro.demo.models.WorkDownloadScheduler
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Manual wiring (no DI framework). Collaborators arrive as constructor parameters with production defaults, so tests
 * inject fakes or fresh files; later tasks add the engines.
 */
class AppContainer(
    val app: Application,
    val llamaBro: LlamaBro = LlamaBro.getInstance(app),
    val hub: HubGateway = RealHubGateway(LlamaBroHub.getInstance(app), llamaBro),
    val scheduler: DownloadScheduler = WorkDownloadScheduler(lazy { WorkManager.getInstance(app) }),
    databaseName: String = "chats.db",
    settingsName: String = "settings",
    val clock: () -> Long = System::currentTimeMillis,
    val database: ChatDatabase = Room.databaseBuilder(app, ChatDatabase::class.java, databaseName).build(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
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
    streamer: ((Conversation, String, Array<Content.Image>, ThinkLevel?) -> Flow<ChatEvent>)? = null,
) {
    val chats: ChatRepository = ChatRepository(database.chatDao(), images, clock)
    val settings: SettingsRepository = SettingsRepository(settingsStore)
    val host: ModelHost = ModelHost(llamaBro, hub, scope)
    val models: ModelRepository = ModelRepository(hub, settings, scheduler, scope, host)

    /** Runs every chat (one handle per open chat); the chat screens read it. */
    val engine: ChatEngine =
        if (streamer != null) {
            ChatEngine(chats, host, models, settings, clock, scope, streamer = streamer)
        } else {
            ChatEngine(chats, host, models, settings, clock, scope)
        }

    /** Hugging Face search and a repo's files; the phone tests pass a fake in [discoveryOverride]. */
    val discovery: ModelDiscovery =
        discoveryOverride ?: ModelDiscovery(HfSearch(token = tokenStore::read)) { deviceFacts() }

    @Volatile private var facts: DeviceFacts? = null

    /** The memory budget and chip, read from the SDK the first time a screen asks. */
    suspend fun deviceFacts(): DeviceFacts = facts ?: DeviceFacts.read(llamaBro).also { facts = it }

    /** For tests that build a container per test: stops DataStore's scope and closes the database. */
    fun close() {
        runBlocking { host.release() }
        scope.cancel()
        // a write already under way (a stopped reply is saved even when cancelled) must finish before the database
        // closes
        runBlocking { scope.coroutineContext[Job]?.join() }
        database.close()
    }
}
