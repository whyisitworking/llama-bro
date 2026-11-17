package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.LlamaBroHub
import com.suhel.llamabro.hub.testing.FakeLlamaBroHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The SDK's fake hub (D70, D75) as the demo's tests use it: the real `LlamaBroHub` over a scripted Hugging Face and a
 * temporary folder, so what is on the phone, a download's progress and its failures are the hub's own. Each model is
 * published as its repo with one small GGUF per tag; [install] puts it on the phone through a real (scripted) download,
 * as the worker would, and [downloading], [partial] and [refuse] leave it under way, paused or failed. The helpers
 * block: the tests call them from inside `runBlocking` or from a plain test thread.
 */
class TestHub : Closeable {
    val fake = FakeLlamaBroHub()
    val hub: LlamaBroHub get() = fake.hub

    private val tags = ConcurrentHashMap<String, MutableSet<String>>()
    private val listings = ConcurrentHashMap<String, Listing>()
    private val gated = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap<HfModel, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Puts [models] on the fake Hub: each repo lists one GGUF per tag. A repo published again is a new commit. */
    @Synchronized
    fun publish(vararg models: HfModel) {
        for ((repo, inRepo) in models.groupBy { it.repo }) {
            val known = tags.getOrPut(repo) { mutableSetOf() }
            if (known.addAll(inRepo.map(::tagOf))) declare(repo)
        }
    }

    /** How search finds a repo. */
    private class Listing(val downloads: Long, val takesImages: Boolean, val baseModel: String?)

    /**
     * Lists [repo] for search: its [downloads], whether it [takesImages], the original it is a quantized upload of
     * ([baseModel], which search groups by), and a GGUF for each of [tags].
     */
    @Synchronized
    fun list(
        repo: String,
        downloads: Long,
        takesImages: Boolean = false,
        baseModel: String? = null,
        tags: List<String> = listOf("Q4_K_M"),
    ) {
        listings[repo] = Listing(downloads, takesImages, baseModel)
        this.tags.getOrPut(repo) { mutableSetOf() }.addAll(tags)
        declare(repo)
    }

    /** Puts [models] on the phone: published if they weren't, then downloaded through the hub. */
    fun install(vararg models: HfModel) =
        runBlocking {
            publish(*models)
            for (model in models) {
                stop(model)
                fake.downloadMillis = 0
                hub.download(model).collect()
            }
        }

    /** Starts [model]'s download and leaves it under way (`Downloading`) until [stop] or [install]. */
    fun downloading(model: HfModel) =
        runBlocking {
            publish(model)
            stop(model)
            fake.downloadMillis = STANDING_MILLIS
            running[model] = scope.launch { hub.download(model).collect() }
            withTimeout(WAIT_MILLIS) { hub.progress(model).first { it is DownloadState.Downloading } }
        }

    /** Moves [model]'s download on: some bytes arrive, and it stays under way from there. */
    fun advance(model: HfModel) {
        partial(model)
        downloading(model)
    }

    /** Starts [model]'s download moving in four steps over [millis], to its end unless [stop] comes first. */
    fun progressing(model: HfModel, millis: Long) =
        runBlocking {
            publish(model)
            stop(model)
            fake.downloadMillis = millis
            running[model] = scope.launch { hub.download(model).collect() }
            withTimeout(WAIT_MILLIS) { hub.progress(model).first { it is DownloadState.Downloading } }
        }

    /** Starts [model]'s download and stops it once some bytes arrived: the partial file stays (`Partial`). */
    fun partial(model: HfModel) =
        runBlocking {
            publish(model)
            stop(model)
            fake.downloadMillis = STEP_MILLIS
            hub
                .download(model)
                .takeWhile { it.phase != DownloadPhase.Downloading || it.bytesDone == 0L }
                .collect()
            fake.downloadMillis = 0
            withTimeout(WAIT_MILLIS) { hub.progress(model).first { it is DownloadState.Partial } }
        }

    /** Stops [model]'s download under way, if any, as a cancelled worker does: what arrived stays. */
    fun stop(model: HfModel) =
        runBlocking {
            running.remove(model)?.cancelAndJoin()
        }

    /** How [refuse] makes a download fail. */
    enum class Refusal { Unauthorized, Network, InsufficientStorage }

    /** Runs [model]'s download into [how]'s failure: the hub's state for it is `Failed` afterwards. */
    fun refuse(model: HfModel, how: Refusal) =
        runBlocking {
            publish(model)
            when (how) {
                Refusal.Unauthorized -> setGated(model.repo, true)
                Refusal.Network -> fake.offline = true
                Refusal.InsufficientStorage -> fake.freeBytes = 1
            }
            try {
                hub.download(model).collect()
                error("the download of $model was meant to fail")
            } catch (expected: HubException) {
                // the hub's state now says why
            } finally {
                when (how) {
                    Refusal.Unauthorized -> setGated(model.repo, false)
                    Refusal.Network -> fake.offline = false
                    Refusal.InsufficientStorage -> fake.freeBytes = Long.MAX_VALUE
                }
            }
            withTimeout(WAIT_MILLIS) { hub.progress(model).first { it is DownloadState.Failed } }
        }

    /** A real `HubException.Network` for a phone with no connection, as the hub throws it offline. */
    fun offlineFailure(): HubException =
        runBlocking {
            fake.offline = true
            try {
                failureOf { hub.search("any") }
            } finally {
                fake.offline = false
            }
        }

    /** A real refusal from a Hub that answered (HTTP 429): retryable, and not offline. */
    fun busyFailure(): HubException =
        runBlocking {
            fake.rateLimit()
            failureOf { hub.search("any") }
        }

    private suspend fun failureOf(call: suspend () -> Unit): HubException =
        try {
            call()
            error("the call was meant to fail")
        } catch (failure: HubException) {
            failure
        }

    /** Whether [model] is on the phone, as the library reads it. */
    fun onPhone(model: HfModel): Boolean = runBlocking { hub.cached().any { it.model == model } }

    override fun close() {
        scope.cancel()
        fake.close()
    }

    @Synchronized
    private fun setGated(repo: String, value: Boolean) {
        if (value) gated += repo else gated -= repo
        declare(repo)
    }

    private fun declare(repo: String) {
        val name = repo.substringAfter('/').removeSuffix("-GGUF")
        val fileTags = tags[repo].orEmpty().toList()
        val listing = listings[repo]
        fake.repo(repo) {
            gated = repo in this@TestHub.gated
            listing?.let {
                downloads = it.downloads
                takesImages = it.takesImages
                baseModel = it.baseModel
            }
            for (tag in fileTags) gguf("$name-$tag.gguf")
        }
    }

    private fun tagOf(model: HfModel): String = requireNotNull(model.tag) { "a test model names its tag: $model" }

    private companion object {
        /** A download that stays under way for the length of any test. */
        const val STANDING_MILLIS = 600_000L

        /** A download that moves in steps a test can stop between. */
        const val STEP_MILLIS = 400L
        const val WAIT_MILLIS = 10_000L
    }
}

/**
 * The SDK's projection as a test sets it, for the view models' `estimate` seam ([ModelDiscovery.estimate] in the app):
 * a model without one fails, as on an offline phone.
 */
class ScriptedEstimates {
    val estimates = ConcurrentHashMap<HfModel, HubEstimate>()

    /** Runs before every `estimate`: a test delays or counts here. */
    var onEstimate: suspend (HfModel) -> Unit = {}

    suspend fun estimate(model: HfModel): HubEstimate {
        onEstimate(model)
        return estimates[model] ?: throw IOException("offline")
    }
}

/**
 * WorkManager's side of the downloads, as a test sets it: what was started and stopped, and each model's work, queued
 * or running until it is cancelled or the test ends it ([endWork], as the worker giving up or the notification's
 * Cancel would). Nothing downloads. The repository decides off the caller's thread (it asks the hub what is on the
 * phone first), so a test waits for a start with [awaitEnqueued].
 */
class FakeScheduler : DownloadScheduler {
    val enqueued: MutableList<HfModel> = CopyOnWriteArrayList()
    val cancelled: MutableList<HfModel> = CopyOnWriteArrayList()

    /** Each model's work: true while queued or running, false once it ended. */
    private val work = MutableStateFlow<Map<HfModel, Boolean>>(emptyMap())

    /** Runs as a start is asked, before the repository watches it: a test makes the download end at once here. */
    var onEnqueue: (HfModel) -> Unit = {}

    /** The models whose work is queued or running now. */
    val running: List<HfModel> get() =
        work.value
            .filterValues { it }
            .keys
            .toList()

    override fun enqueue(model: HfModel) {
        enqueued += model
        work.update { it + (model to true) }
        onEnqueue(model)
    }

    /**
     * Holds the next cancel until the test completes it, as WorkManager takes a moment to record one; `cancel` returns
     * once it is recorded. Used once.
     */
    var cancelGate: CompletableDeferred<Unit>? = null

    /** What the next cancel throws, as a WorkManager operation that failed would. Used once. */
    var cancelFailure: Exception? = null

    override suspend fun cancel(model: HfModel) {
        cancelled += model
        cancelFailure?.let {
            cancelFailure = null
            throw it
        }
        cancelGate?.let {
            cancelGate = null
            it.await()
        }
        work.update { if (model in it) it + (model to false) else it }
    }

    /** [models]' work is queued or running, as WorkManager keeps it across a restart. */
    fun scheduled(vararg models: HfModel) {
        work.update { it + models.map { model -> model to true } }
    }

    /** [model]'s work ends outside the app: the worker gave up, or the notification's Cancel was tapped. */
    fun endWork(model: HfModel) {
        work.update { it + (model to false) }
    }

    override suspend fun isScheduled(model: HfModel): Boolean = work.value[model] == true

    override fun ends(model: HfModel): Flow<Unit> =
        flow {
            var live = false
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

    /** Waits until [models] have been started, in that order, and nothing else. */
    fun awaitEnqueued(vararg models: HfModel) =
        runBlocking {
            withTimeout(WAIT_MILLIS) { while (enqueued != models.toList()) delay(POLL_MILLIS) }
        }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
