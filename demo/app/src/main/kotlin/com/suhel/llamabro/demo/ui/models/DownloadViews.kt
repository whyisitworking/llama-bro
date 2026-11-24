package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.designsystem.components.DownloadStage
import com.suhel.llamabro.demo.designsystem.components.DownloadView
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.Format
import com.suhel.llamabro.demo.models.ModelLibrary
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** The name of the download that runs while [key] waits its turn, as the row says it; null when none does. */
internal fun ModelLibrary.aheadOf(key: HfModel): Flow<String?> =
    combine(running, records) { running, records ->
        running?.takeIf { it != key }?.let { ModelEntry.downloading(it, records[it]).name }
    }.distinctUntilChanged()

/** A download as the ring panel shows it, on a row and on the card (docs/design/models-page.md §3, §4). */
object DownloadViews {
    /**
     * [key]'s download in [status]: the quant is the key's own ("Q4_K_M", never a tier). The total is [totalOf]'s:
     * [recordBytes], the whole size the person chose it with, else the hub's total, so the line says "of 3.4 GB" from
     * the first frame after a restart and never switches between the two. A download waiting its
     * turn names the one [ahead] of it. Null once the model is on the phone.
     */
    fun of(key: HfModel, status: DownloadStatus, recordBytes: Long?, ahead: String? = null): DownloadView? {
        val quant = key.tag.orEmpty()
        return when (status) {
            is DownloadStatus.Downloading -> {
                val total = totalOf(status, recordBytes)
                val parts =
                    listOfNotNull(
                        total?.let { Format.arrived(status.bytesDone, it) },
                        status.etaSeconds?.let(Format::timeLeftText),
                    )
                DownloadView(
                    DownloadStage.Downloading,
                    quant,
                    fraction(status.bytesDone, total),
                    parts.joinToString(" · "),
                )
            }

            is DownloadStatus.Partial -> {
                val total = totalOf(status, recordBytes)
                DownloadView(
                    DownloadStage.Paused,
                    quant,
                    fraction(status.bytesDone, total),
                    total?.let { Format.arrived(status.bytesDone, it) }.orEmpty(),
                )
            }

            is DownloadStatus.Failed -> {
                // the bytes kept, with the ring frozen at them, so a stopped download says what a Try again
                // resumes from
                val total = totalOf(status, recordBytes)
                val reason = Limits.forDownload(status).line
                val kept =
                    total?.takeIf { status.bytesDone > 0 }?.let {
                        "${Format.arrived(
                            status.bytesDone,
                            it,
                        )} kept"
                    }
                DownloadView(
                    DownloadStage.Stopped,
                    quant,
                    fraction(status.bytesDone, total),
                    kept?.let { "${reason.removeSuffix(".")} · $it" } ?: reason,
                )
            }

            DownloadStatus.Absent -> {
                DownloadView(DownloadStage.Waiting, quant, 0f, ahead?.let { "after $it" } ?: "Waiting to start")
            }

            DownloadStatus.Complete -> {
                null
            }
        }
    }

    /**
     * The whole download's size, in one order everywhere (the row's line, the card's state line and its panel): the
     * size the person chose it with ([recordBytes]: the record's, else the row's own), then the hub's total.
     */
    fun totalOf(status: DownloadStatus, recordBytes: Long?): Long? =
        recordBytes?.takeIf { it > 0 } ?: when (status) {
            is DownloadStatus.Downloading -> status.bytesTotal
            is DownloadStatus.Partial -> status.bytesTotal
            is DownloadStatus.Failed -> status.bytesTotal
            else -> 0L
        }.takeIf { it > 0 }

    /**
     * What a Cancel past 300 MB asks, on a row and on the card: "0.9 of 3.4 GB has arrived. Cancelling deletes it.",
     * in the one progress string ([Format.arrived]); with no total known, what arrived alone ("420 MB has arrived."),
     * so the question is never skipped. Null when nothing has arrived.
     */
    fun cancelQuestion(status: DownloadStatus, recordBytes: Long?): String? {
        val done = arrivedOf(status).takeIf { it > 0 } ?: return null
        val arrived = totalOf(status, recordBytes)?.let { Format.arrived(done, it) } ?: Format.size(done)
        return "$arrived has arrived. Cancelling deletes it."
    }

    /** The bytes a download has on the phone, in any state. */
    fun arrivedOf(status: DownloadStatus): Long =
        when (status) {
            is DownloadStatus.Downloading -> status.bytesDone
            is DownloadStatus.Partial -> status.bytesDone
            is DownloadStatus.Failed -> status.bytesDone
            else -> 0L
        }

    private fun fraction(done: Long, total: Long?): Float =
        if (total == null) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}
