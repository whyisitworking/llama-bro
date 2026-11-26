package com.suhel.llamabro.demo.journey

import com.suhel.llamabro.demo.chat.ChatLibrary
import com.suhel.llamabro.demo.chat.sdkStream
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.demo.models.DownloadScheduler
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.testing.FakeDiscovery
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.rag.Hit
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything a journey stands on besides the app: a fake engine, the SDK's fake hub (D70, D75) whose downloads move
 * only when the journey says so, and search over it. The app itself (screens, view models, database, settings) is the
 * real one.
 */
class World(private val realDownloads: Boolean = false) : Closeable {
    val engine = FakeLlamaBro()
    val hub = TestHub()
    val downloads = ScriptedDownloads(hub)
    val discovery = FakeDiscovery(hub)

    /**
     * While true, a reply waits before it starts (the model "reads the prompt"): the turn is under way, no text yet.
     */
    val holdReplies = MutableStateFlow(false)

    /** The person's library (docs/design/rag.md): empty, so the **+** is the photo picker, until a journey fills it. */
    val library = JourneyLibrary()

    init {
        // the catalog's models are online: each repo lists its file
        for (entry in Catalog.suggested) stock(entry)
    }

    /** Puts [entry]'s repo on the fake Hugging Face, its one file at the catalog's size. */
    fun stock(entry: CatalogEntry) {
        val quant = entry.key.toString().substringAfter(':')
        hub.publish(entry.key)
        discovery.repoFiles[entry.key.repo] =
            listOf(RepoFile("${entry.realName.replace(' ', '-')}-$quant.gguf", quant, entry.modelBytes))
    }

    /**
     * The fake hub and the scripted worker, or (for the journeys about WorkManager itself) the real hub and worker over
     * the fake server.
     */
    fun config(asksForNotifications: Boolean = false): TestConfig =
        TestConfig.fresh(
            llamaBro = engine.llamaBro,
            hub = if (realDownloads) null else hub.hub,
            scheduler = if (realDownloads) null else downloads,
            discovery = discovery,
            asksForNotifications = asksForNotifications,
            chatLibrary = library,
            streamer = { convo, text, images, options ->
                sdkStream(convo, text, images, options).onStart { holdReplies.first { !it } }
            },
        )

    override fun close() = hub.close()
}

/** A library over passages a journey chooses: no document until [documents] is set. */
class JourneyLibrary : ChatLibrary {
    override val hasDocuments = MutableStateFlow(false)

    /** What every search finds. */
    @Volatile var hits: List<Hit> = emptyList()

    /** Each document's text, for a source's surroundings. */
    val texts: MutableMap<String, String> = ConcurrentHashMap()

    override suspend fun search(query: String): List<Hit> = hits

    override suspend fun documentText(documentId: String): String? = texts[documentId]
}

/**
 * The download worker, scripted over the fake hub: `get` starts a download that stands still until [advance] moves it
 * on, [finish] completes it, and a cancel keeps what had arrived (the pause the library offers) until the model is
 * deleted. The hub's own state shows each step, as it shows the SDK worker's.
 */
class ScriptedDownloads(private val hub: TestHub) : DownloadScheduler {
    val enqueued: MutableList<HfModel> = CopyOnWriteArrayList()
    val cancelled: MutableList<HfModel> = CopyOnWriteArrayList()

    /** Told when a model is on the phone, for one that no `get` started (the repository watches the ones it did). */
    var onFinished: () -> Unit = {}

    /** Models the fake Hugging Face only gives to someone with a token ([hasToken] says whether the app has one). */
    val gated: MutableSet<HfModel> = ConcurrentHashMap.newKeySet()
    var hasToken: () -> Boolean = { false }

    /** Each model's work: true while queued or running, false once it ended, as WorkManager reports it. */
    private val work = MutableStateFlow<Map<HfModel, Boolean>>(emptyMap())

    override fun enqueue(model: HfModel) {
        enqueued += model
        work.update { it + (model to true) }
        if (model in gated && !hasToken()) {
            hub.refuse(model, TestHub.Refusal.Unauthorized)
            work.update { it + (model to false) } // the worker fails for good
        } else {
            hub.downloading(model)
        }
    }

    override suspend fun cancel(model: HfModel) {
        cancelled += model
        hub.stop(model)
        work.update { if (model in it) it + (model to false) else it }
    }

    override suspend fun isScheduled(model: HfModel): Boolean = work.value[model] == true

    override fun ends(model: HfModel, seenRunning: Boolean): Flow<Unit> =
        flow {
            var live = seenRunning
            work.collect {
                val now = it[model]
                if (now == true) {
                    live = true
                } else if (live && now == false) {
                    live = false
                    emit(Unit)
                }
            }
        }

    /** Some bytes arrive: the download is under way past 0%. */
    fun advance(model: HfModel) = hub.advance(model)

    /** The model arrives: it is on the phone, complete, and the repository is told. */
    fun finish(model: HfModel) {
        hub.install(model)
        work.update { it + (model to false) }
        onFinished()
    }
}
