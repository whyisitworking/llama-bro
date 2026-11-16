package com.suhel.llamabro.demo.ui.models

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.demo.models.attempt
import com.suhel.llamabro.demo.models.offline
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

sealed interface GetModelsState {
    /** Under two characters: the fixed list. [offlineLine] says so when a search just failed for want of a network. */
    data class Suggestions(val rows: List<ModelRow>, val offlineLine: String? = null) : GetModelsState

    data object Searching : GetModelsState

    data class Results(val rows: List<ModelRow>) : GetModelsState

    data object NoResults : GetModelsState

    data class Failed(val retryable: Boolean) : GetModelsState
}

/**
 * Search over Hugging Face and the five suggestions (demo design §4.3). A query is searched 350 ms after the last
 * keystroke; under two characters shows the suggestions. A row's fit arrives on its own flow ([fit]) so a late
 * estimate recomposes that row alone; so does a download's progress ([progress]).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GetModelsViewModel(
    private val search: suspend (String) -> List<SearchModel>,
    private val rowFit: suspend (ModelRow) -> FitView?,
    private val models: ModelRepository,
    private val markFirstRunSeen: suspend () -> Unit = {},
    initialQuery: String = "",
    private val debounceMillis: Long = 350,
    /** Keeps what was typed across process death. */
    savedState: SavedStateHandle,
) : ViewModel() {
    // the user's inputs: what was typed, how often "Retry" was tapped, which download started from which row
    private val queryInput = savedState.getMutableStateFlow(QUERY_KEY, initialQuery)
    private val retryInput = MutableStateFlow(0)
    private val startedInput = MutableStateFlow<Map<String, HfModel>>(emptyMap())

    private val openInput = MutableStateFlow<ModelRow?>(null)

    val query: StateFlow<String> = queryInput.asStateFlow()

    /** The row whose sheet is open; kept here so a rotation keeps the sheet. */
    val opened: StateFlow<ModelRow?> = openInput.asStateFlow()

    /** Everything on screen, derived from the query: suggestions, then searching, then results or why not. */
    val state: StateFlow<GetModelsState> =
        combine(
            queryInput.map { it.trim() }.distinctUntilChanged().debounce {
                if (it.length < MIN_QUERY) {
                    0L
                } else {
                    debounceMillis
                }
            },
            retryInput,
        ) { text, _ -> text }
            .flatMapLatest(::stateFor)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT),
                GetModelsState.Suggestions(ModelRow.suggestions),
            )

    /** The repos on the phone already, lower-cased: their rows say so. */
    val onPhone: StateFlow<Set<String>> =
        models.library
            .map { list -> list.map { it.key.repo.lowercase() }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptySet())

    private val fits = ConcurrentHashMap<String, StateFlow<FitView?>>()
    private val downloads = ConcurrentHashMap<String, StateFlow<DownloadStatus?>>()
    private val fitSlots = Semaphore(FIT_PARALLELISM)

    fun setQuery(text: String) {
        queryInput.value = text
    }

    fun open(row: ModelRow) {
        openInput.value = row
    }

    fun dismiss() {
        openInput.value = null
    }

    fun retry() {
        retryInput.update { it + 1 }
    }

    private fun stateFor(text: String): Flow<GetModelsState> =
        if (text.length < MIN_QUERY) {
            flowOf(GetModelsState.Suggestions(ModelRow.suggestions))
        } else {
            flow {
                emit(GetModelsState.Searching)
                emit(resultsFor(text))
            }
        }

    private suspend fun resultsFor(text: String): GetModelsState =
        try {
            val rows = search(text).map(ModelRow::of)
            if (rows.isEmpty()) GetModelsState.NoResults else GetModelsState.Results(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HubException) {
            if (e.offline) {
                GetModelsState.Suggestions(
                    ModelRow.suggestions,
                    OFFLINE_LINE,
                )
            } else {
                GetModelsState.Failed(e.retryable)
            }
        } catch (ignored: Exception) {
            GetModelsState.Failed(retryable = false)
        }

    /**
     * How [row] runs here: asked once when a row first appears, one tree call for a model the app doesn't ship sizes
     * for.
     */
    fun fit(row: ModelRow): StateFlow<FitView?> =
        fits.getOrPut(row.groupKey) {
            // no tint rather than a wrong one when the estimate fails
            flow { emit(attempt { fitSlots.withPermit { rowFit(row) } }) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)
        }

    /** The download started from [row], or null when none was. */
    fun progress(row: ModelRow): StateFlow<DownloadStatus?> =
        downloads.getOrPut(row.groupKey) {
            startedInput
                .map { it[row.groupKey] }
                .distinctUntilChanged()
                .flatMapLatest { key -> if (key == null) flowOf(null) else models.state(key) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)
        }

    /** A download was started from [row] (its sheet or its suggestion): its progress shows on that row. */
    fun started(row: ModelRow, key: HfModel) {
        startedInput.update { it + (row.groupKey to key) }
        viewModelScope.launch { markFirstRunSeen() }
    }

    companion object {
        const val MIN_QUERY = 2
        private const val FIT_PARALLELISM = 4
        private const val QUERY_KEY = "query"
        private const val STOP_TIMEOUT = 5_000L
        const val OFFLINE_LINE = "You're offline. These are suggested for this phone."
    }
}
