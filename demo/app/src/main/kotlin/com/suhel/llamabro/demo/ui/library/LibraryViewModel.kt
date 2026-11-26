package com.suhel.llamabro.demo.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.library.EmbedderDoesNotFit
import com.suhel.llamabro.demo.library.EmbedderFit
import com.suhel.llamabro.demo.library.ImportRefused
import com.suhel.llamabro.demo.library.Mismatch
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.rag.DocumentInfo
import com.suhel.llamabro.rag.IndexMismatch
import com.suhel.llamabro.rag.IngestProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the embedder's file stands: not on the phone, arriving, or there. */
sealed interface EmbedderDownload {
    data object Absent : EmbedderDownload

    /** [progress] is 0 to 1, null before the size is known. */
    data class Downloading(val progress: Float?) : EmbedderDownload

    data object OnPhone : EmbedderDownload
}

/**
 * The library as the Library page reads and drives it: the app's repository and the embedder's download, a fake in the
 * tests.
 */
interface LibraryControl {
    /** The documents; null before the folder is read. */
    val documents: Flow<List<DocumentInfo>?>
    val mismatch: Flow<Mismatch?>
    val embedder: Flow<EmbedderDownload>

    /** Whether the embedder fits beside the chat model, asked once it is on the phone. */
    suspend fun fit(): EmbedderFit

    /** Starts the embedder's download. */
    fun getEmbedder()

    fun import(uri: Uri): Flow<IngestProgress>

    suspend fun remove(id: String)

    fun rebuild(): Flow<IngestProgress>
}

/** The Library page (docs/design/rag.md, the demo): the embedder's state, the work under way and the documents. */
data class LibraryPage(
    val embedder: EmbedderLine,
    val documents: List<DocumentRow>,
    val work: WorkLine? = null,
    val mismatch: MismatchLine? = null,
    /** Why the last import didn't happen; dismissed by the person. */
    val notice: String? = null,
) {
    /** A file can be added: the embedder is ready, nothing else is under way, and the library can be searched. */
    val canImport: Boolean get() = embedder is EmbedderLine.Ready && work == null && mismatch == null
}

/** The embedder, as the page's first lines say it. */
sealed interface EmbedderLine {
    /** Not on the phone: Get it, [size] to download. */
    data class Missing(val name: String, val size: String) : EmbedderLine

    data class Downloading(val name: String, val progress: Float?) : EmbedderLine

    /** On the phone, its fit not read yet. */
    data object Checking : EmbedderLine

    /** It won't load beside the chat model: [text] says what it needs and what is free. */
    data class DoesNotFit(val text: String) : EmbedderLine

    data class Ready(val name: String) : EmbedderLine
}

/** An import or a rebuild under way: [text] and its [progress], 0 to 1 (null before the passages are counted). */
data class WorkLine(val text: String, val progress: Float?)

/** The library was built by another embedder: [text] says so, [action] rebuilds it. */
data class MismatchLine(val text: String, val action: String)

/** A document as its row shows it: the title, then its passages and the file it came from. */
data class DocumentRow(val id: String, val title: String, val detail: String)

/**
 * The Library page's state, derived from the library, the embedder's download and fit, and the work this page
 * started. Imports and rebuilds run on [background], the app's scope, so leaving the page never stops one halfway.
 */
class LibraryViewModel(
    private val library: LibraryControl,
    private val embedderName: String,
    private val embedderBytes: Long,
    private val background: CoroutineScope,
) : ViewModel() {
    private val work = MutableStateFlow<WorkLine?>(null)
    private val notice = MutableStateFlow<String?>(null)

    /** A load that found no room: it holds until the next import tries again. */
    private val refused = MutableStateFlow<EmbedderFit.DoesNotFit?>(null)

    private val fit: Flow<EmbedderFit?> =
        library.embedder
            .map { it == EmbedderDownload.OnPhone }
            .distinctUntilChanged()
            .map { onPhone -> if (onPhone) library.fit() else null }

    private val embedder: Flow<EmbedderLine> =
        combine(library.embedder, fit.onStart { emit(null) }, refused) { download, fit, refused ->
            when (download) {
                EmbedderDownload.Absent -> EmbedderLine.Missing(embedderName, Format.size(embedderBytes))
                is EmbedderDownload.Downloading -> EmbedderLine.Downloading(embedderName, download.progress)
                EmbedderDownload.OnPhone -> line(refused ?: fit)
            }
        }

    val page: StateFlow<LibraryPage?> =
        combine(
            embedder,
            library.documents,
            library.mismatch,
            work,
            notice,
        ) { embedder, documents, mismatch, work, notice ->
            documents?.let {
                LibraryPage(
                    embedder,
                    it.map(::row),
                    work,
                    mismatch?.let { m -> mismatchLine(m, it.size) },
                    notice,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    fun import(uri: Uri) {
        if (work.value != null) return
        refused.value = null
        notice.value = null
        run(IMPORTING, library.import(uri))
    }

    fun rebuild() {
        if (work.value != null) return
        notice.value = null
        run(REBUILDING, library.rebuild())
    }

    fun remove(id: String) {
        background.launch {
            try {
                library.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                notice.value = failure("Couldn't remove the document", e)
            }
        }
    }

    fun getEmbedder() = library.getEmbedder()

    fun dismissNotice() {
        notice.value = null
    }

    private fun run(what: String, progress: Flow<IngestProgress>) {
        work.value = WorkLine(what, null)
        background.launch {
            try {
                progress.collect { step ->
                    work.value =
                        when (step) {
                            is IngestProgress.Chunked -> passages(what, 0, step.chunks)
                            is IngestProgress.Embedded -> passages(what, step.done, step.of)
                            IngestProgress.Saved -> null
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportRefused) {
                notice.value = e.message
            } catch (e: EmbedderDoesNotFit) {
                refused.value = e.fit
            } catch (_: IndexMismatch) {
                // the mismatch line says so, and offers the rebuild
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                notice.value = failure(if (what == IMPORTING) "Couldn't add the document" else "Couldn't rebuild", e)
            } finally {
                work.value = null
            }
        }
    }

    private fun line(fit: EmbedderFit?): EmbedderLine =
        when (fit) {
            null -> EmbedderLine.Checking
            EmbedderFit.Fits -> EmbedderLine.Ready(embedderName)
            is EmbedderFit.DoesNotFit -> EmbedderLine.DoesNotFit(doesNotFit(fit))
        }

    private fun doesNotFit(fit: EmbedderFit.DoesNotFit): String {
        val where = fit.beside?.let { "beside $it" } ?: "in the memory this phone has free"
        val free = fit.budgetBytes?.let { ", and ${Format.size(it)} is free" }.orEmpty()
        return "$embedderName doesn't fit $where: it needs ${Format.size(fit.needBytes)}$free."
    }

    private fun mismatchLine(mismatch: Mismatch, documents: Int): MismatchLine =
        MismatchLine(
            "This library was indexed by ${mismatch.built}, so ${mismatch.wanted} can't search it. Rebuilding " +
                "indexes its ${count(documents, "document")} again; nothing is deleted.",
            "Rebuild with ${mismatch.wanted}",
        )

    private companion object {
        const val STOP_TIMEOUT = 5_000L
        const val IMPORTING = "Adding a document"
        const val REBUILDING = "Rebuilding the library"

        fun row(document: DocumentInfo) =
            DocumentRow(
                document.id,
                document.title,
                listOfNotNull(count(document.chunks, "passage"), document.source).joinToString(" · "),
            )

        fun passages(what: String, done: Int, of: Int) =
            WorkLine("$what: $done of $of passages", if (of > 0) done.toFloat() / of else null)

        fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

        fun failure(what: String, e: Exception) =
            "$what: ${e.message?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}."
    }
}
