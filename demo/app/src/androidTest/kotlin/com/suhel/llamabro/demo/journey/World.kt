package com.suhel.llamabro.demo.journey

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.chat.sdkStream
import com.suhel.llamabro.demo.models.CachedEntry
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.CatalogEntry
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadScheduler
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FakeHubGateway
import com.suhel.llamabro.demo.models.HubEstimate
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.testing.FakeDiscovery
import com.suhel.llamabro.demo.testing.TestConfig
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything a journey stands on besides the app: a fake engine, a hub whose downloads move only when the journey says
 * so, and a fake Hugging Face. The app itself (screens, view models, database, settings) is the real one.
 */
class World(private val realDownloads: Boolean = false) {
    val engine = FakeLlamaBro()
    val hub = FakeHubGateway()
    val downloads = ScriptedDownloads(hub)
    val discovery = FakeDiscovery()

    /** While true, a reply waits before it starts (the model "reads the prompt"): the turn is under way, no text yet. */
    val holdReplies = MutableStateFlow(false)

    init {
        // the catalog's models are online: each repo lists its file, and the SDK's estimate says it fits
        for (entry in Catalog.suggested) stock(entry)
    }

    /** Puts [entry]'s repo on the fake Hugging Face, and says its estimate fits this phone. */
    fun stock(entry: CatalogEntry, fits: Boolean = true) {
        val quant = entry.key.toString().substringAfter(':')
        discovery.repoFiles[entry.key.repo] =
            listOf(RepoFile("${entry.realName.replace(' ', '-')}-$quant.gguf", quant, entry.modelBytes))
        hub.estimates[entry.key] =
            HubEstimate(
                entry.modelBytes,
                entry.mmprojBytes,
                fits,
                if (fits) CONTEXT_TOKENS else null,
                entry.modelBytes + entry.mmprojBytes + WORKING_MEMORY,
                DeviceKind.Npu,
            )
    }

    /** The scripted hub and scheduler, or (for the journeys about WorkManager itself) the real ones over the fake server. */
    fun config(asksForNotifications: Boolean = false): TestConfig =
        TestConfig.fresh(
            llamaBro = engine.llamaBro,
            hub = if (realDownloads) null else hub,
            scheduler = if (realDownloads) null else downloads,
            discovery = discovery,
            asksForNotifications = asksForNotifications,
            streamer = { convo, text, images, options ->
                sdkStream(convo, text, images, options).onStart { holdReplies.first { !it } }
            },
        )
}

private const val CONTEXT_TOKENS = 32_768
private const val WORKING_MEMORY = 300_000_000L

/**
 * The download worker, scripted. `get` starts a download that stands at 0 until [advance] moves it, [finish] puts the
 * model on the phone, and a cancel keeps what had arrived (the pause the library offers) until the model is deleted.
 */
class ScriptedDownloads(private val hub: FakeHubGateway) : DownloadScheduler {
    val enqueued = CopyOnWriteArrayList<HfModel>()
    val cancelled = CopyOnWriteArrayList<HfModel>()

    /** Told when a model is on the phone, as the worker tells the repository. */
    var onFinished: () -> Unit = {}

    /** Models the fake Hugging Face only gives to someone with a token ([hasToken] says whether the app has one). */
    val gated = mutableSetOf<HfModel>()
    var hasToken: () -> Boolean = { false }

    private val done = mutableMapOf<HfModel, Long>()
    private val totals = mutableMapOf<HfModel, Long>()

    fun total(model: HfModel) = totals.getOrPut(model) { DEFAULT_TOTAL }

    override fun enqueue(model: HfModel) {
        enqueued += model
        hub.status(model).value =
            if (model in gated && !hasToken()) {
                DownloadStatus.Failed(DownloadFailure(DownloadFailure.Kind.Unauthorized, retryable = false))
            } else {
                running(model)
            }
    }

    override fun cancel(model: HfModel) {
        cancelled += model
        val soFar = done[model] ?: 0L
        hub.status(model).value =
            if (soFar > 0) DownloadStatus.Partial(soFar, total(model)) else DownloadStatus.Absent
    }

    /** Moves the download to [fraction] of the whole, at a steady 9 MB/s. */
    fun advance(model: HfModel, fraction: Double) {
        done[model] = (total(model) * fraction).toLong()
        hub.status(model).value = running(model)
    }

    /** The model arrives: it is on the phone, complete, and the repository is told. */
    fun finish(model: HfModel) {
        done[model] = total(model)
        hub.cachedEntries += CachedEntry(model, total(model))
        hub.status(model).value = DownloadStatus.Complete
        onFinished()
    }

    private fun running(model: HfModel): DownloadStatus.Downloading {
        val soFar = done[model] ?: 0L
        val left = total(model) - soFar
        return DownloadStatus.Downloading(soFar, total(model), SPEED, left / SPEED)
    }

    private companion object {
        const val DEFAULT_TOTAL = 739_000_000L
        const val SPEED = 9_000_000L
    }
}
