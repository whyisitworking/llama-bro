package com.suhel.llamabro.demo.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

/** What the models take and what is left: "6.1 GB used · 120 GB free", and the bar between them. */
data class StorageInfo(val usedBytes: Long, val freeBytes: Long)

/**
 * The library (demo design §4.3): the models on the phone with their last use, the downloads under way, and the storage
 * line. A swipe asks first ([askRemove]); only [confirmRemove] deletes.
 */
class LibraryViewModel(
    private val models: ModelRepository,
    private val freeBytes: suspend () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    io: CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    /** Null until the first read of the hub's list. */
    val rows: StateFlow<List<LibraryModel>?> =
        models.library
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    val downloads: StateFlow<List<HfModel>> =
        models.downloading.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT),
            emptyList(),
        )

    val storage: StateFlow<StorageInfo?> =
        rows
            .map { list ->
                list?.let { StorageInfo(it.sumOf { m -> m.sizeBytes }, freeBytes()) }
            }.flowOn(io)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    private val _pendingRemove = MutableStateFlow<LibraryModel?>(null)

    /** The model a swipe is asking about; the dialog names it and its size. */
    val pendingRemove: StateFlow<LibraryModel?> = _pendingRemove.asStateFlow()

    fun lastUsed(model: LibraryModel): String =
        Format
            .usedAgo(model.lastUsed, clock(), zone)

    fun askRemove(model: LibraryModel) {
        _pendingRemove.value = model
    }

    fun dismissRemove() {
        _pendingRemove.value = null
    }

    private val _problem = MutableStateFlow<String?>(null)

    /**
     * A remove or a cancel that couldn't stop the download's work: nothing was deleted, and this line says so, so the
     * tap isn't lost. Closed with its ✕, or replaced by the next one.
     */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun dismissProblem() {
        _problem.value = null
    }

    fun confirmRemove() {
        val model = _pendingRemove.value ?: return
        _pendingRemove.value = null
        viewModelScope.launch {
            if (!models.remove(model.key)) _problem.value = "Couldn't remove ${model.name}. Try again."
        }
    }

    fun state(key: HfModel): StateFlow<DownloadStatus> = models.state(key)

    fun pause(key: HfModel) = models.pause(key)

    fun resume(key: HfModel) = models.get(key)

    fun cancel(key: HfModel) {
        val cancelled = models.cancel(key)
        viewModelScope.launch {
            if (!cancelled.await()) {
                _problem.value =
                    "Couldn't cancel the download of ${Catalog.nameOf(key)}. Try again."
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
