package com.suhel.llamabro.demo.models

import com.suhel.llamabro.demo.chat.HostState
import com.suhel.llamabro.demo.chat.ModelHost
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.DownloadFailure.Kind
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A model on the phone. [name] is its own name (the catalog's, else tidied from its repo); never a nickname. */
data class LibraryModel(
    val key: HfModel,
    val name: String,
    val sizeBytes: Long,
    val lastUsed: Long?,
    val isDefault: Boolean,
)

/**
 * The models on the phone and their downloads. `pause` stops the work and keeps the hub's partial; `get` starts it
 * again and the hub resumes; `cancel` stops it and deletes the partial. [host], when given, is released before a model
 * it holds is removed. [lastUsedAt] reads when a model was last used in a chat (null when unknown).
 */
class ModelRepository(
    private val hub: HubGateway,
    private val settings: SettingsRepository,
    private val scheduler: DownloadScheduler,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val host: ModelHost? = null,
    private val lastUsedAt: suspend (HfModel) -> Long? = { null },
) {
    private val changes = MutableStateFlow(0)

    val library: Flow<List<LibraryModel>> =
        combine(
            settings.settings.map { it.defaultModel }.distinctUntilChanged(),
            changes,
        ) { defaultKey, _ -> defaultKey }.map { defaultKey ->
            hub
                .cached()
                .map { entry ->
                    LibraryModel(
                        key = entry.model,
                        name = Catalog.byRepo(entry.model.repo)?.realName ?: tidy(entry.model),
                        sizeBytes = entry.sizeBytes,
                        lastUsed = lastUsedAt(entry.model),
                        isDefault = entry.model.toString() == defaultKey,
                    )
                }.sortedWith(compareByDescending<LibraryModel> { it.lastUsed ?: 0L }.thenBy { it.name })
        }

    /**
     * The downloads under way or paused, started here and not yet finished or cancelled, oldest first. The hub keeps a
     * partial file but has no list of them, so the app keeps the keys (settings), and each one's state is [state].
     */
    val downloading: Flow<List<HfModel>> =
        settings.settings.map { it.pendingDownloads }.distinctUntilChanged().map { keys ->
            keys.sorted().mapNotNull { runCatching { HfModel.parse(it) }.getOrNull() }
        }

    /** Starts the download, or does nothing when the model is already on the phone. */
    fun get(model: HfModel) {
        scope.launch {
            if (hub.cached().none { it.model == model }) {
                scheduler.enqueue(model)
                settings.addPendingDownload(model.toString())
            }
        }
    }

    /** A token was saved: the downloads Hugging Face refused for want of one start again. */
    fun tokenSaved() {
        scope.launch {
            val refused =
                settings.settings
                    .first()
                    .pendingDownloads
                    .mapNotNull { runCatching { HfModel.parse(it) }.getOrNull() }
                    .filter { (state(it).value as? DownloadStatus.Failed)?.failure?.kind == Kind.Unauthorized }
            refused.forEach(::get)
        }
    }

    /** Stops the work and keeps the partial: the state reads `Partial`, and `get` resumes it. */
    fun pause(model: HfModel) {
        scheduler.cancel(model)
    }

    /** Stops the work and deletes what had arrived. */
    fun cancel(model: HfModel) {
        scheduler.cancel(model)
        scope.launch {
            awaitStopped(model)
            hub.delete(model)
            settings.removePendingDownloads(listOf(model.toString()))
            changed()
        }
    }

    /** Deletes a model from the phone: releases it first when it is the loaded one, and unpins it as the default. */
    suspend fun remove(model: HfModel) {
        scheduler.cancel(model)
        val loaded =
            when (val s = host?.state?.value) {
                is HostState.Ready -> s.key
                is HostState.Waking -> s.key
                is HostState.Failed -> s.key
                else -> null
            }
        if (loaded == model) host?.release()
        awaitStopped(model)
        if (settings.settings.first().defaultModel == model.toString()) settings.pinDefault(null)
        hub.delete(model)
        settings.removePendingDownloads(listOf(model.toString()))
        changed()
    }

    fun state(model: HfModel): StateFlow<DownloadStatus> = hub.progress(model)

    /** The worker finished a model: the library lists it now. */
    internal fun changed() {
        changes.update { it + 1 }
        scope.launch {
            val onPhone = hub.cached().map { it.model.toString() }.toSet()
            val finished =
                settings.settings
                    .first()
                    .pendingDownloads
                    .filter { it in onPhone }
            settings.removePendingDownloads(finished)
        }
    }

    /** After the work is told to stop, a running transfer takes a moment to let go of its files. */
    private suspend fun awaitStopped(model: HfModel) {
        withTimeoutOrNull(STOP_WAIT_MILLIS) { hub.progress(model).first { it !is DownloadStatus.Downloading } }
    }

    private fun tidy(model: HfModel): String = model.repo.substringAfter('/').removeSuffix("-GGUF")

    private companion object {
        /** Builder's call, settled here: a cancelled transfer stops within a second or two. */
        const val STOP_WAIT_MILLIS = 5_000L
    }
}
