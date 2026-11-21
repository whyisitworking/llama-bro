package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.ModelLibrary
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A row's ✕ past 300 MB arrived: which download, and the line the question says. */
data class CancelQuestion(val key: HfModel, val text: String)

/**
 * The Models page's rows' download controls (docs/design/models-page.md §3): the ring pauses a download, resumes a
 * paused one and tries a stopped one again; the ✕ cancels, asking first past [CANCEL_ASKS_BYTES] arrived as the card
 * does (§4.5). A row acts on the download of its own file, else the one its repo has under way ([downloading]); a
 * cancel that couldn't stop the work is said through [onProblem].
 */
class RowDownloads(
    private val models: ModelLibrary,
    private val scope: CoroutineScope,
    private val downloading: () -> List<HfModel>,
    private val onProblem: (String) -> Unit,
) {
    // user input: the ✕ question asked, answered by Keep or Cancel download
    private val questionInput = MutableStateFlow<CancelQuestion?>(null)

    /** The question a ✕ past [CANCEL_ASKS_BYTES] asks; null while none is asked. */
    val question: StateFlow<CancelQuestion?> = questionInput.asStateFlow()

    fun toggle(entry: ModelEntry) {
        val key = downloadOf(entry) ?: return
        when (models.state(key).value) {
            is DownloadStatus.Downloading -> models.pause(key)
            is DownloadStatus.Partial, is DownloadStatus.Failed -> models.get(key)
            DownloadStatus.Absent, DownloadStatus.Complete -> Unit
        }
    }

    fun cancel(entry: ModelEntry) {
        val key = downloadOf(entry) ?: return
        val status = models.state(key).value
        // the row's own size is the record's total for its download (the one size, §7)
        val question = DownloadViews.cancelQuestion(status, entry.size?.bytes.takeIf { entry.key == key })
        if (DownloadViews.arrivedOf(status) > CANCEL_ASKS_BYTES && question != null) {
            questionInput.value = CancelQuestion(key, question)
        } else {
            cancel(key)
        }
    }

    fun confirm() {
        val asked = questionInput.value ?: return
        questionInput.value = null
        cancel(asked.key)
    }

    fun keep() {
        questionInput.value = null
    }

    fun cancel(key: HfModel) {
        val cancelled = models.cancel(key)
        scope.launch {
            if (!cancelled.await()) onProblem("Couldn't cancel the download of ${Catalog.nameOf(key)}. Try again.")
        }
    }

    private fun downloadOf(entry: ModelEntry): HfModel? {
        val now = downloading()
        return entry.key?.takeIf { it in now } ?: now.firstOrNull { it.repo.equals(entry.repo, ignoreCase = true) }
    }

    private companion object {
        /** Past this much arrived, a row's ✕ asks first, as the card's Cancel does (models-page design §4.5). */
        const val CANCEL_ASKS_BYTES = 300_000_000L
    }
}
