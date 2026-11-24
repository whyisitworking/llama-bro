package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.Fits
import com.suhel.llamabro.demo.models.HubSearch
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelLibrary
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.models.Sizes
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * A Models page row's own flows: its fit ([fit]), its size ([size]) and what its repo is doing here ([state]), so
 * either changing recomposes that row alone. Each lives in the scope its caller passes, the row's own in the page
 * (`rememberCoroutineScope`), so a row that scrolled away takes its flows with it: nothing here grows with the rows
 * seen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RowStates(
    private val hub: HubSearch,
    private val models: ModelLibrary,
    private val deviceFacts: StateFlow<DeviceFacts?>,
    private val library: StateFlow<List<LibraryModel>?>,
    private val downloading: StateFlow<List<HfModel>?>,
) {
    // external event: the card reports the fit it measured
    private val exactFits = MutableStateFlow<Map<String, FitView>>(emptyMap())

    /**
     * How [row] runs here, with no call of its own: a catalog model from the sizes it ships, any other guessed from
     * the Hub's facts the search brought (F175); none when the Hub gave no parameter count. An [exactFit] replaces a
     * guess. Kept per file ([fileOf]), never per model: a suggestion, its download in another quant and the file on
     * the phone each have their own fit.
     */
    fun fit(row: ModelEntry, scope: CoroutineScope): StateFlow<FitView?> {
        val file = fileOf(row)
        val exact = exactFits.map { it[file] }.distinctUntilChanged()
        return combine(deviceFacts, exact) { phone, known -> known ?: phone?.let { guess(row, it) } }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT),
                exactFits.value[file] ?: deviceFacts.value?.let { guess(row, it) },
            )
    }

    /**
     * [row]'s size on its line: its own (the catalog's, the file on the phone, a download's record), and for a row
     * whose size is only guessed, the exact size once the card has cached the repo's files: the Recommended file the
     * card would offer, with its image reader (docs/design/models-page.md §7). One stored read per row, no request.
     */
    fun size(row: ModelEntry, scope: CoroutineScope): StateFlow<SizeText?> {
        val own = row.size
        return if (own is SizeText.Exact || row.key != null) {
            // a constant: a row's own size never changes
            MutableStateFlow(own).asStateFlow()
        } else {
            hub
                .files(row.repo)
                .map { cached -> cached.value?.let(::recommendedSize) ?: own }
                .distinctUntilChanged()
                .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), own)
        }
    }

    /**
     * The card sized [row]'s files: the fit of the file it recommends stands over the guess of a row with no file of
     * its own (a Hub result, whose size is that file's); a row with a file already has its exact fit.
     */
    fun exactFit(row: ModelEntry, fit: FitView) {
        if (row.key == null && row.catalog == null) exactFits.update { it + (fileOf(row) to fit) }
    }

    /**
     * What [row]'s repo is doing here, from the repository alone, so a download started anywhere (another screen, an
     * earlier visit, the notification) shows: under way, waiting, paused or failed while any quant of it downloads,
     * with the ring panel's view, else on the phone when any quant is.
     */
    fun state(row: ModelEntry, scope: CoroutineScope): StateFlow<RowState?> {
        val repo = row.repo.lowercase()
        val where = { onPhone: List<LibraryModel>?, downloads: List<HfModel>? ->
            val installed = onPhone.orEmpty().any { it.key.repo.lowercase() == repo }
            downloads.orEmpty().firstOrNull { it.repo.lowercase() == repo } to installed
        }
        return combine(library, downloading) { onPhone, downloads -> where(onPhone, downloads) }
            .distinctUntilChanged()
            .flatMapLatest { (key, installed) ->
                when {
                    key != null -> downloadState(key)
                    installed -> flowOf(RowState(RowBadge.OnPhone, null))
                    else -> flowOf(null)
                }
            }.distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT),
                firstState(row, where(library.value, downloading.value)),
            )
    }

    /**
     * The row's state as the first frame draws it, from what is already read: a download's row has its panel from the
     * first frame (§3, "heights never change within a mode"), its total from the row's own size, which is the record's.
     */
    private fun firstState(row: ModelEntry, where: Pair<HfModel?, Boolean>): RowState? {
        val (key, installed) = where
        return when {
            key != null -> {
                val status = models.state(key).value
                RowState(badgeOf(status), DownloadViews.of(key, status, row.size?.bytes.takeIf { row.key == key }))
            }

            installed -> {
                RowState(RowBadge.OnPhone, null)
            }

            else -> {
                null
            }
        }
    }

    private fun downloadState(key: HfModel): Flow<RowState> =
        combine(models.state(key), models.records.map { it[key] }.distinctUntilChanged()) { status, record ->
            status to record
        }.flatMapLatest { (status, record) ->
            val ahead = if (status == DownloadStatus.Absent) models.aheadOf(key) else flowOf(null)
            ahead.map { name -> RowState(badgeOf(status), DownloadViews.of(key, status, record?.totalBytes, name)) }
        }

    private companion object {
        const val STOP_TIMEOUT = 5_000L
    }
}

/**
 * How [row] runs on [phone] before the card measures it, by the one formula every row, the switcher and the card use
 * ([Fits.quick]: the whole size for memory, the model file for speed, D121): from its size, which is
 * the file on the phone's, the catalog's, a download's record or the guess from the Hub's facts (F175).
 */
private fun guess(row: ModelEntry, phone: DeviceFacts): FitView? =
    row.size?.takeIf { it.bytes > 0 }?.let {
        Fits.quick(it.bytes, Fits.speedBytes(it.modelBytes, row.repo, row.parameters, row.activeParameters), phone)
    }

/** The file a row stands for, its fit's key: its own, the catalog's for a suggestion, else its repo's Recommended. */
internal fun fileOf(row: ModelEntry): String = ModelEntry.listKey(row)

private fun badgeOf(status: DownloadStatus): RowBadge =
    when (status) {
        DownloadStatus.Absent -> RowBadge.Queued
        is DownloadStatus.Downloading -> RowBadge.Downloading(status.fraction())
        is DownloadStatus.Partial -> RowBadge.Paused
        is DownloadStatus.Failed -> RowBadge.Failed(Limits.forDownload(status).line)
        DownloadStatus.Complete -> RowBadge.OnPhone
    }

private fun recommendedSize(files: List<RepoFile>): SizeText? =
    Sizes.tiers(files)[SizeTier.Recommended]?.let { SizeText.Exact(it.bytes + it.encoderBytes, it.bytes) }
