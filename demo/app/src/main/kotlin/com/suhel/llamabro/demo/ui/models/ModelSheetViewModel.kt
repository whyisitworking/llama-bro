package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.models.Sizes
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

enum class SheetMode { Basic, Advanced }

/** The repo's files, once listed: the three tiers of Basic, every quant for Advanced, and the one Recommended. */
@Immutable
sealed interface SheetState {
    data object Loading : SheetState

    data class Ready(val tiers: List<Pair<SizeTier, RepoFile>>, val files: List<RepoFile>, val recommended: RepoFile?) :
        SheetState

    data class Failed(val retryable: Boolean) : SheetState
}

/**
 * What the sheet shows of one file: how it runs here ([fit], from the file and its encoder, D101), the download's size
 * ([totalBytes], the file and its image reader) and the reader's own size ([readerBytes], 0 for none).
 */
@Immutable
data class FileFit(val fit: FitView, val totalBytes: Long, val readerBytes: Long)

/**
 * A model's sheet (demo design §4.3). The inputs are the user's: the mode, the file picked, "Retry". Everything shown
 * is derived from them: the file list ([state]), the file that counts as selected ([selected], Recommended unless the
 * user chose another, and always one of the three sizes in Basic), the button's words ([button]) and the image reader
 * ([imageReaderBytes]). Each file's fit ([fit]) is its own flow, made from file sizes alone (D101) when a row is first
 * shown, so a row recomposes alone when its fit arrives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelSheetViewModel(
    val row: ModelRow,
    private val files: suspend (String) -> List<RepoFile>,
    private val facts: suspend () -> DeviceFacts,
    private val models: ModelRepository,
    /** The size the sheet starts on instead of Recommended ("Smaller size" opens it on Smaller). */
    private val startTier: SizeTier? = null,
) : ViewModel() {
    private val modeInput = MutableStateFlow(SheetMode.Basic)
    private val pickInput = MutableStateFlow<RepoFile?>(null)
    private val retryInput = MutableStateFlow(0)
    private val fits = ConcurrentHashMap<String, StateFlow<FileFit?>>()

    val mode: StateFlow<SheetMode> = modeInput.asStateFlow()

    val state: StateFlow<SheetState> =
        retryInput
            .flatMapLatest { listing() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), SheetState.Loading)

    val selected: StateFlow<RepoFile?> =
        combine(state, modeInput, pickInput) { state, mode, pick ->
            val ready = state as? SheetState.Ready ?: return@combine null
            val started = startTier?.let { tier -> ready.tiers.firstOrNull { it.first == tier }?.second }
            val wanted = pick ?: started ?: ready.recommended
            // Basic offers three sizes: a quant picked in Advanced that isn't one of them gives way to Recommended
            if (mode == SheetMode.Basic && ready.tiers.none { it.second == wanted }) ready.recommended else wanted
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /**
     * The image reader's size (the catalog's own figure, else the hub's listing); null for a model without one.
     */
    val imageReaderBytes: StateFlow<Long?> =
        state
            .flatMapLatest { state ->
                val recommended = (state as? SheetState.Ready)?.recommended
                if (recommended == null) {
                    flowOf(null)
                } else {
                    fit(recommended).map { it?.readerBytes?.takeIf { bytes -> bytes > 0 } }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** The button's words: Basic names the model and the time, Advanced the quant and the size. */
    val button: StateFlow<String> =
        combine(
            selected,
            modeInput,
            selected.flatMapLatest { file -> if (file == null) flowOf(null) else fit(file) },
        ) { file, mode, fit ->
            if (file == null || fit == null) return@combine ""
            when (mode) {
                SheetMode.Basic -> {
                    "Get ${row.name} · ${Format.downloadTime(fit.totalBytes)}"
                }

                SheetMode.Advanced -> {
                    "Get ${file.quant} · ${Format.sizeInGb(fit.totalBytes)}" +
                        if (fit.readerBytes > 0) " in all" else ""
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), "")

    fun setMode(mode: SheetMode) {
        modeInput.value = mode
    }

    fun select(file: RepoFile) {
        pickInput.value = file
    }

    fun retry() {
        retryInput.update { it + 1 }
    }

    /** The fit flow of [file]: null until the phone's facts are read, then the fit from its sizes. */
    fun fit(file: RepoFile): StateFlow<FileFit?> =
        fits.getOrPut(file.path) {
            flow { emit(fitOf(file)) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)
        }

    /** Starts the download of the selected file; returns its key (the row shows its progress), or null when none. */
    fun get(): HfModel? {
        val file = selected.value ?: return null
        return HfModel.parse("${row.repo}:${file.quant}").also(models::get)
    }

    private fun listing(): Flow<SheetState> =
        flow {
            emit(SheetState.Loading)
            emit(
                try {
                    val list = files(row.repo)
                    val tiers = Sizes.tiers(list)
                    SheetState.Ready(
                        tiers = SizeTier.entries.mapNotNull { tier -> tiers[tier]?.let { tier to it } },
                        files = list.sortedBy { it.bytes },
                        recommended = tiers[SizeTier.Recommended],
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SheetState.Failed(retryable = (e as? HubException)?.retryable ?: true)
                },
            )
        }

    private suspend fun fitOf(file: RepoFile): FileFit {
        // the catalog's figure for a model it knows, else the encoder the hub would fetch with this quant
        val reader = row.catalog?.mmprojBytes ?: file.encoderBytes
        return FileFit(Fits.quick(file.bytes + reader, file.bytes, facts()), file.bytes + reader, reader)
    }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}
