package com.suhel.llamabro.demo.ui.chat

import androidx.compose.runtime.Immutable
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Sidebar layout writes replies on the page; Chat list layout puts them in bubbles (demo design §4.2). */
enum class MessageStyle { Open, Bubbles }

/** One row of the message list. [key] is stable for a message, [type] is its Compose content type. */
@Immutable
sealed interface ChatItem {
    val key: String
    val type: String
}

@Immutable data class DayItem(val label: String, val epochDay: Long) : ChatItem {
    override val key get() = "day-$epochDay"
    override val type get() = "day"
}

/** [heldNote] is "<Name> will answer the moment it's here", set while the message waits for its model. */
@Immutable data class UserItem(
    val id: Long,
    val position: Int,
    val text: String,
    val images: List<File>,
    val time: String?,
    val heldNote: String?,
) : ChatItem {
    override val key get() = "m-$id"
    override val type get() = "user"
}

/**
 * [thoughtSeconds] is set once the model has answered after thinking; [failure] is the plain line of a failed reply.
 */
@Immutable data class ReplyItem(
    val id: Long,
    val position: Int,
    val text: String,
    val blocks: MarkdownBlocks,
    val reasoning: String?,
    val thoughtSeconds: Int?,
    val speed: String?,
    val time: String?,
    val stopped: Boolean,
    val failure: String?,
) : ChatItem {
    override val key get() = "m-$id"
    override val type get() = "reply"
}

@Immutable data class SwitchItem(val id: Long, val text: String) : ChatItem {
    override val key get() = "m-$id"
    override val type get() = "switch"
}

/** Turns stored messages into the list the screen draws. Pure, so it is tested on the JVM. */
class ChatItems(
    private val style: MessageStyle,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val locale: Locale = Locale.getDefault(),
    private val now: () -> Long = System::currentTimeMillis,
    private val blocksFor: (id: Long, text: String) -> MarkdownBlocks,
) {
    /**
     * [waitingFor] is the name of a model that isn't here yet (downloading or gone): messages held for it say so.
     * Day labels and times belong to the Bubbles style only.
     */
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    fun build(messages: List<StoredMessage>, waitingFor: String?): List<ChatItem> {
        val out = ArrayList<ChatItem>(messages.size + 2)
        var lastDay: Long? = null
        // the reply being written is drawn from the streaming flow, not from its stored row
        for (m in messages.filterNot { it.kind == MessageKind.Assistant && it.state == MessageState.Streaming }) {
            val at = Instant.ofEpochMilli(m.sentAt).atZone(zone)
            val day = at.toLocalDate().toEpochDay()
            if (style == MessageStyle.Bubbles && m.kind != MessageKind.Switch && day != lastDay) {
                out += DayItem(dayLabel(day), day)
                lastDay = day
            }
            out += item(m, time = if (style == MessageStyle.Bubbles) timeFormat.format(at) else null, waitingFor)
        }
        return out
    }

    private fun item(m: StoredMessage, time: String?, waitingFor: String?): ChatItem =
        when (m.kind) {
            MessageKind.User -> userItem(m, time, waitingFor)
            MessageKind.Assistant -> replyItem(m, time)
            MessageKind.Switch -> SwitchItem(m.id, m.text)
        }

    private fun userItem(m: StoredMessage, time: String?, waitingFor: String?): UserItem {
        val held = m.state == MessageState.Held && waitingFor != null
        val heldNote = if (held) "$waitingFor will answer the moment it's here" else null
        return UserItem(m.id, m.position, m.text, m.images, time, heldNote)
    }

    private fun replyItem(m: StoredMessage, time: String?): ReplyItem =
        ReplyItem(
            m.id,
            m.position,
            m.text,
            blocksFor(m.id, m.text),
            m.reasoning,
            thoughtSeconds = m.reasoningMillis?.let { (it / MILLIS_PER_SECOND).roundToInt() },
            speed = speed(m.wordsPerSecond),
            time = time,
            stopped = m.state == MessageState.Stopped,
            failure = if (m.state == MessageState.Failed) m.failure ?: "That didn't work. Try again." else null,
        )

    private fun dayLabel(epochDay: Long): String {
        val today =
            Instant
                .ofEpochMilli(now())
                .atZone(zone)
                .toLocalDate()
                .toEpochDay()
        return when (epochDay) {
            today -> "Today"
            today - 1 -> "Yesterday"
            else -> DateTimeFormatter.ofPattern("d MMM", locale).format(LocalDate.ofEpochDay(epochDay))
        }
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000.0

        /** Below half a word a second there is nothing worth showing. */
        private const val MIN_SPEED = 0.5

        /** "38 words/s", or null when the reply has no speed (stopped, failed, or too short to measure). */
        fun speed(wordsPerSecond: Double?): String? =
            wordsPerSecond?.takeIf { it >= MIN_SPEED }?.let { "${it.roundToInt()} words/s" }
    }
}

/** The share card's text: the first 600 characters of a reply, with "…" after when it was cut (demo design §4.4). */
object ShareText {
    const val LIMIT = 600

    fun cut(text: String): String = if (text.length <= LIMIT) text else text.substring(0, LIMIT).trimEnd() + "…"
}
