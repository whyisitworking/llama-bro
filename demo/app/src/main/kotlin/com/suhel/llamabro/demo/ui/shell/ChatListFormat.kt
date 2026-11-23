package com.suhel.llamabro.demo.ui.shell

import com.suhel.llamabro.demo.data.ChatSummary
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.hub.HfModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** The drawer's groups (demo design §4.2). */
enum class DayGroup(val label: String) { Today("Today"), ThisWeek("This week"), Earlier("Earlier") }

/** A group of chats, newest first inside. */
data class ChatGroup(val group: DayGroup, val chats: List<ChatSummary>)

/** What a chat list shows for chats: grouping by day, the row's time and the "Model: last text" preview. */
object ChatListFormat {
    private const val WEEK_DAYS = 6L

    fun dayGroup(updatedAt: Long, now: Long, zone: ZoneId): DayGroup {
        val day = Instant.ofEpochMilli(updatedAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            day >= today -> DayGroup.Today
            !day.isBefore(today.minusDays(WEEK_DAYS)) -> DayGroup.ThisWeek
            else -> DayGroup.Earlier
        }
    }

    /** [chats] must already be newest first; empty groups are left out. */
    fun group(chats: List<ChatSummary>, now: Long, zone: ZoneId): List<ChatGroup> =
        chats
            .groupBy { dayGroup(it.updatedAt, now, zone) }
            .map { (group, list) -> ChatGroup(group, list) }
            .sortedBy { it.group.ordinal }

    /** The time of a row: the time today, the weekday within the last week, else the date. */
    fun rowTime(updatedAt: Long, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochMilli(updatedAt).atZone(zone)
        return when (dayGroup(updatedAt, now, zone)) {
            DayGroup.Today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)
            DayGroup.ThisWeek -> DateTimeFormatter.ofPattern("EEE", locale).format(at)
            DayGroup.Earlier -> DateTimeFormatter.ofPattern("d MMM", locale).format(at)
        }
    }

    /** "Gemma 3 4B: Here's a tighter…" for a reply, "You: what about…" for the user's last message. */
    fun preview(chat: ChatSummary): String {
        if (chat.isEmpty) return ""
        // a reply with no answer text that the user stopped (while it was still thinking) says so, not a blank
        val last =
            chat.lastText
                .replace('\n', ' ')
                .trim()
                .ifEmpty { if (chat.lastStopped) "Stopped" else "" }
        return if (chat.lastFromUser) "You: $last" else "${modelName(chat.modelKey)}: $last"
    }

    fun modelName(modelKey: String): String = runCatching { Names.of(HfModel.parse(modelKey)) }.getOrDefault("Model")

    fun avatarLetter(modelKey: String): String =
        modelName(modelKey).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}
