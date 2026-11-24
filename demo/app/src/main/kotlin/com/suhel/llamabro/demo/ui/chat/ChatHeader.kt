package com.suhel.llamabro.demo.ui.chat

import androidx.compose.runtime.Immutable
import com.suhel.llamabro.demo.chat.ChatModelState
import com.suhel.llamabro.demo.models.DownloadStatus
import kotlin.math.roundToInt

/** What the top bar shows: the model's name, and while it downloads the ring's fraction and the label beside it. */
@Immutable
data class ChatHeader(val name: String, val downloadFraction: Float?, val downloadLabel: String?) {
    companion object {
        /** The top bar for model [name] in [model]'s state: a ring and its label while the model downloads. */
        fun of(name: String, model: ChatModelState): ChatHeader =
            when (val downloading = (model as? ChatModelState.Downloading)?.status) {
                is DownloadStatus.Downloading -> {
                    ChatHeader(name, fraction(downloading.bytesDone, downloading.bytesTotal), label(downloading))
                }

                is DownloadStatus.Partial -> {
                    ChatHeader(name, fraction(downloading.bytesDone, downloading.bytesTotal), null)
                }

                else -> {
                    ChatHeader(name, null, null)
                }
            }

        private fun fraction(done: Long, total: Long): Float =
            if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

        private fun label(s: DownloadStatus.Downloading): String {
            val pct = (fraction(s.bytesDone, s.bytesTotal) * 100).roundToInt()
            val eta = s.etaSeconds?.let { " · about ${if (it < 60) "$it s" else "${(it + 30) / 60} min"}" }.orEmpty()
            return "$pct%$eta"
        }
    }
}
