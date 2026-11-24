package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.models.Cached
import com.suhel.llamabro.demo.models.DownloadRecord
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.HubCard
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.models.ModelLibrary
import com.suhel.llamabro.demo.models.PageFacts
import com.suhel.llamabro.demo.models.RepoFile
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow

/** [this], its first value [lag] ms late, as Room's first read answers after the screen is composed. */
private fun <T> Flow<T>.late(lag: Long): Flow<T> =
    if (lag == 0L) {
        this
    } else {
        flow {
            delay(lag)
            emitAll(this@late)
        }
    }

/**
 * A card's Hub cache in memory: what is stored per repo, and the refreshes asked; a refresh does what [onRefresh] says.
 */
internal class FakeHubCard : HubCard {
    val files = MutableStateFlow<Cached<List<RepoFile>>>(Cached(null, false, null))
    val page = MutableStateFlow<Cached<PageFacts>>(Cached(null, false, null))
    val facts = MutableStateFlow<Cached<SearchModel>>(Cached(null, false, null))
    val asked = mutableListOf<String>()
    var onRefresh: (String) -> Unit = {}

    /** How late each read answers first. */
    var lag = 0L

    override fun files(repo: String): Flow<Cached<List<RepoFile>>> = files.late(lag)

    override suspend fun refreshFiles(repo: String) {
        asked += "files"
        onRefresh("files")
    }

    override fun page(repo: String): Flow<Cached<PageFacts>> = page.late(lag)

    override suspend fun refreshPage(repo: String) {
        asked += "page"
        onRefresh("page")
    }

    override fun repoFacts(repo: String): Flow<Cached<SearchModel>> = facts.late(lag)

    override suspend fun refreshRepoFacts(repo: String) {
        asked += "facts"
        onRefresh("facts")
    }
}

/** The library and the download queue as plain flows; what the card asks of them is written down. */
internal class FakeModelLibrary : ModelLibrary {
    /** The models on the phone; null until the library has been read from disk. */
    val shelf = MutableStateFlow<List<LibraryModel>?>(emptyList())
    val queue = MutableStateFlow<List<HfModel>>(emptyList())
    val recorded = MutableStateFlow<Map<HfModel, DownloadRecord>>(emptyMap())

    /** The download the worker has now. */
    override val running = MutableStateFlow<HfModel?>(null)

    /** How late each read answers first. */
    var lag = 0L
    override val library: Flow<List<LibraryModel>> get() = shelf.filterNotNull().late(lag)
    override val downloading: Flow<List<HfModel>> get() = queue.late(lag)
    override val records: Flow<Map<HfModel, DownloadRecord>> get() = recorded.late(lag)
    val states = mutableMapOf<HfModel, MutableStateFlow<DownloadStatus>>()
    val got = mutableListOf<Pair<HfModel, DownloadRecord?>>()
    val paused = mutableListOf<HfModel>()
    val cancelled = mutableListOf<HfModel>()
    var cancels = true

    fun status(model: HfModel): MutableStateFlow<DownloadStatus> =
        states.getOrPut(model) { MutableStateFlow(DownloadStatus.Absent) }

    override fun state(model: HfModel): StateFlow<DownloadStatus> = status(model)

    override fun get(model: HfModel, record: DownloadRecord?) {
        got += model to record
    }

    override fun pause(model: HfModel) {
        paused += model
    }

    override fun cancel(model: HfModel): Deferred<Boolean> {
        cancelled += model
        return CompletableDeferred(cancels)
    }

    override suspend fun remove(model: HfModel): Boolean {
        shelf.value = shelf.value?.filterNot { it.key == model }
        return true
    }
}
