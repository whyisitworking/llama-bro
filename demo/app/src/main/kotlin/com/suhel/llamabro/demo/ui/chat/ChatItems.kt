package com.suhel.llamabro.demo.ui.chat

import androidx.compose.runtime.Immutable
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.Source
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.hub.HfModel
import java.io.File
import java.text.NumberFormat
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

/** A voice message's recording, its length as shown ("0:42") and its levels for the bubble (D76). */
@Immutable data class VoiceItem(val file: File, val length: String, val levels: List<Float>)

/** A clip's length as a voice message shows it: minutes and seconds, "0:42", "2:00". */
fun clipLength(millis: Long): String {
    val seconds =
        java.util.concurrent.TimeUnit.MILLISECONDS
            .toSeconds(millis + HALF_SECOND_MILLIS)
    val minutes =
        java.util.concurrent.TimeUnit.SECONDS
            .toMinutes(seconds)
    return "$minutes:${(seconds - minutes * SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
}

/** Milliseconds in a second, as a Double for the divisions that show seconds; S3 makes it the demo's one. */
internal const val MILLIS_PER_SECOND = 1000.0

private const val HALF_SECOND_MILLIS = 500L
private const val SECONDS_PER_MINUTE = 60

/**
 * [heldNote] is "<Name> will answer the moment it's here", set while the message waits for its model. [voice] is a
 * voice message's recording (D76).
 */
@Immutable data class UserItem(
    val id: Long,
    val position: Int,
    val text: String,
    val images: List<File>,
    val time: String?,
    val heldNote: String?,
    val voice: VoiceItem? = null,
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
    /** What the details sheet lists (the model, the chip, speeds and token counts); empty for a failed reply. */
    val details: List<ReplyDetail> = emptyList(),
    /** The reply reached its length limit (D60). */
    val cut: Boolean = false,
    /** A cut reply that is the chat's last row: it can be continued. */
    val canContinue: Boolean = false,
    /** A reply spoken in voice mode: its speech, which the speaker action plays again (D100). */
    val speech: File? = null,
    /** The library's passages it cites, its source chips ([Passages.cited]); empty when it used none. */
    val sources: List<Source> = emptyList(),
) : ChatItem {
    override val key get() = "m-$id"
    override val type get() = "reply"
}

/** One line of a reply's details: "Speed", "38 words/s · 14.2 tokens/s". */
@Immutable data class ReplyDetail(val label: String, val value: String)

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
        val shown = messages.filterNot { it.kind == MessageKind.Assistant && it.state == MessageState.Streaming }
        // only the chat's last row can be continued: anything after a cut reply (a divider, a question) moved on
        val last = messages.lastOrNull()
        for (m in shown) {
            val at = Instant.ofEpochMilli(m.sentAt).atZone(zone)
            val day = at.toLocalDate().toEpochDay()
            if (style == MessageStyle.Bubbles && m.kind != MessageKind.Switch && day != lastDay) {
                out += DayItem(dayLabel(day), day)
                lastDay = day
            }
            out +=
                item(
                    m,
                    time =
                        if (style ==
                            MessageStyle.Bubbles
                        ) {
                            timeFormat.format(at)
                        } else {
                            null
                        },
                    waitingFor,
                    m === last,
                )
        }
        return out
    }

    private fun item(m: StoredMessage, time: String?, waitingFor: String?, isLast: Boolean): ChatItem =
        when (m.kind) {
            MessageKind.User -> userItem(m, time, waitingFor)
            MessageKind.Assistant -> replyItem(m, time, isLast)
            MessageKind.Switch -> SwitchItem(m.id, m.text)
        }

    private fun userItem(m: StoredMessage, time: String?, waitingFor: String?): UserItem {
        val held = m.state == MessageState.Held && waitingFor != null
        val heldNote = if (held) "$waitingFor will answer the moment it's here" else null
        val voice = m.voice?.let { VoiceItem(it.file, clipLength(it.millis), it.levels) }
        return UserItem(m.id, m.position, m.text, m.images, time, heldNote, voice)
    }

    private fun replyItem(m: StoredMessage, time: String?, isLast: Boolean): ReplyItem =
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
            failure = if (m.state == MessageState.Failed) m.failure ?: "That didn't work." else null,
            details = if (m.state == MessageState.Failed) emptyList() else details(m),
            cut = m.state == MessageState.Cut,
            canContinue = m.state == MessageState.Cut && isLast,
            speech = m.voice?.file,
            sources = Passages.cited(m.text, m.sources),
        )

    /** The details sheet's lines, each only when it's known: replies stored before version 2 have fewer. */
    private fun details(m: StoredMessage): List<ReplyDetail> {
        val s = m.stats
        val count = NumberFormat.getIntegerInstance(locale)

        fun rate(perSecond: Double) = String.format(locale, "%.1f tokens/s", perSecond)
        return buildList {
            m.modelKey
                ?.let { key -> runCatching { Names.of(HfModel.parse(key)) }.getOrNull() }
                ?.let { add(ReplyDetail("Model", it)) }
            s?.device?.let { add(ReplyDetail("Ran on", it)) }
            val words = speed(m.wordsPerSecond)
            val tokens = s?.tokensPerSecond?.takeIf { it > 0 }?.let(::rate)
            listOfNotNull(words, tokens).takeIf { it.isNotEmpty() }?.let {
                add(ReplyDetail("Speed", it.joinToString(" · ")))
            }
            s?.replyTokens?.let { add(ReplyDetail("Reply", "${count.format(it)} tokens")) }
            s?.promptTokens?.let { prompt ->
                val cached = s.cachedTokens?.takeIf { it > 0 }
                val line =
                    "${count.format(prompt)} tokens" +
                        (cached?.let { " · ${count.format(it)} from the cache" }.orEmpty())
                add(ReplyDetail("Prompt", line))
            }
            s?.promptPerSecond?.takeIf { it > 0 }?.let { add(ReplyDetail("Prompt read at", rate(it))) }
            m.reasoningMillis?.let {
                add(ReplyDetail("Thought for", "${(it / MILLIS_PER_SECOND).roundToInt()} s"))
            }
            val drafted = s?.draftTokens?.takeIf { it > 0 }
            if (drafted != null) {
                add(ReplyDetail("Drafts kept", "${count.format(s.draftAccepted ?: 0)} of ${count.format(drafted)}"))
            }
        }
    }

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
