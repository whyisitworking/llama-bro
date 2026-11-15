package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.demo.chat.Markdown
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.data.db.ReplyStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

class ChatItemsTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 1_800_000_000_000L - (1_800_000_000_000L % day) + 12 * hour // noon UTC

    /** Newer JDKs put a narrow no-break space before AM/PM. */
    private fun String.plain() = replace('\u202f', ' ')

    private fun builder(style: MessageStyle) =
        ChatItems(style, ZoneOffset.UTC, Locale.US, { now }) { _, text -> Markdown.parse(text) }

    private fun msg(
        id: Long,
        kind: MessageKind,
        text: String,
        at: Long,
        state: MessageState = MessageState.Done,
        wps: Double? = null,
        think: Long? = null,
        failure: String? = null,
    ) = StoredMessage(id, 1, id.toInt(), kind, text, null, think, emptyList(), null, at, wps, state, failure)

    @Test fun aReplysDetailsListWhatRanItAndWhatWasMeasured() {
        val reply =
            msg(2, MessageKind.Assistant, "yo", now, wps = 38.4, think = 4_200).copy(
                modelKey = "unsloth/Qwen3.5-4B-GGUF:Q4_K_M",
                stats = ReplyStats(212, 14.24, 1_024, 980, 310.0, 240, 180, "NPU"),
            )
        val details = (builder(MessageStyle.Open).build(listOf(reply), null).single() as ReplyItem).details
        assertEquals(
            listOf(
                ReplyDetail("Ran on", "NPU"),
                ReplyDetail("Speed", "38 words/s · 14.2 tokens/s"),
                ReplyDetail("Reply", "212 tokens"),
                ReplyDetail("Prompt", "1,024 tokens · 980 from the cache"),
                ReplyDetail("Prompt read at", "310.0 tokens/s"),
                ReplyDetail("Thought for", "4 s"),
                ReplyDetail("Drafts kept", "180 of 240"),
            ),
            details.drop(1), // the first line is the model's name, from the catalog
        )
        assertEquals("Model", details.first().label)
    }

    @Test fun aReplyFromBeforeStatsShowsWhatItHas() {
        val reply = msg(2, MessageKind.Assistant, "yo", now, wps = 38.4)
        val details = (builder(MessageStyle.Open).build(listOf(reply), null).single() as ReplyItem).details
        assertEquals(listOf(ReplyDetail("Speed", "38 words/s")), details)
    }

    @Test fun aFailedReplyHasNoDetails() {
        val reply = msg(2, MessageKind.Assistant, "", now, state = MessageState.Failed, failure = "That didn't work.")
        assertTrue((builder(MessageStyle.Open).build(listOf(reply), null).single() as ReplyItem).details.isEmpty())
    }

    @Test fun openStyleHasNoTimesOrDayLabels() {
        val items =
            builder(
                MessageStyle.Open,
            ).build(
                listOf(msg(1, MessageKind.User, "hi", now), msg(2, MessageKind.Assistant, "yo", now, wps = 38.4)),
                null,
            )
        assertEquals(listOf("user", "reply"), items.map { it.type })
        assertNull((items[0] as UserItem).time)
        assertNull((items[1] as ReplyItem).time)
        assertEquals("38 words/s", (items[1] as ReplyItem).speed)
    }

    @Test fun bubbleStyleShowsTimesAndTodayOnce() {
        val items =
            builder(MessageStyle.Bubbles).build(
                listOf(
                    msg(1, MessageKind.User, "hi", now),
                    msg(
                        2,
                        MessageKind.Assistant,
                        "yo",
                        now + hour,
                    ),
                ),
                null,
            )
        assertEquals(listOf("day", "user", "reply"), items.map { it.type })
        assertEquals("Today", (items[0] as DayItem).label)
        assertEquals("12:00 PM", (items[1] as UserItem).time?.plain())
        assertEquals("1:00 PM", (items[2] as ReplyItem).time?.plain())
    }

    @Test fun dayLabelsSeparateDays() {
        val items =
            builder(MessageStyle.Bubbles).build(
                listOf(
                    msg(1, MessageKind.User, "a", now - 2 * day),
                    msg(2, MessageKind.User, "b", now - day),
                    msg(3, MessageKind.User, "c", now),
                ),
                null,
            )
        assertEquals(listOf("day", "user", "day", "user", "day", "user"), items.map { it.type })
        assertEquals(listOf("Yesterday", "Today"), items.filterIsInstance<DayItem>().drop(1).map { it.label })
        assertTrue((items[0] as DayItem).label.matches(Regex("\\d{1,2} [A-Z][a-z]{2}")))
    }

    @Test fun switchDividerHasNoDayLabelOrTime() {
        val items =
            builder(
                MessageStyle.Bubbles,
            ).build(listOf(msg(1, MessageKind.Switch, "Switched to Qwen3.5 4B", now)), null)
        assertEquals(listOf("switch"), items.map { it.type })
    }

    @Test fun heldMessageSaysWhoWillAnswer() {
        val held = msg(1, MessageKind.User, "hi", now, state = MessageState.Held)
        assertEquals(
            "Qwen3.5 0.8B will answer the moment it's here",
            (builder(MessageStyle.Open).build(listOf(held), "Qwen3.5 0.8B").single() as UserItem).heldNote,
        )
        assertNull((builder(MessageStyle.Open).build(listOf(held), null).single() as UserItem).heldNote)
        assertNull(
            (
                builder(
                    MessageStyle.Open,
                ).build(listOf(held.copy(state = MessageState.Done)), "Qwen3.5 0.8B").single() as UserItem
            ).heldNote,
        )
    }

    @Test fun replyCarriesThoughtSecondsStoppedAndFailure() {
        val a =
            builder(MessageStyle.Open)
                .build(
                    listOf(
                        msg(1, MessageKind.Assistant, "x", now, think = 4_400),
                        msg(2, MessageKind.Assistant, "y", now, state = MessageState.Stopped),
                        msg(3, MessageKind.Assistant, "", now, state = MessageState.Failed, failure = "Nope."),
                    ),
                    null,
                ).filterIsInstance<ReplyItem>()
        assertEquals(4, a[0].thoughtSeconds)
        assertTrue(a[1].stopped)
        assertEquals("Nope.", a[2].failure)
        assertNull(a[0].failure)
    }

    @Test fun theReplyBeingWrittenIsNotAStoredRow() {
        val items =
            builder(MessageStyle.Open).build(
                listOf(
                    msg(1, MessageKind.User, "hi", now),
                    msg(2, MessageKind.Assistant, "partial", now, state = MessageState.Streaming),
                ),
                null,
            )
        assertEquals(listOf("user"), items.map { it.type })
    }

    @Test fun speedIsAbsentWhenNotMeasured() {
        assertNull(ChatItems.speed(null))
        assertNull(ChatItems.speed(0.0))
        assertEquals("12 words/s", ChatItems.speed(11.6))
    }

    @Test fun shareTextIsUntouchedUnder600AndCutAfter() {
        assertEquals("short", ShareText.cut("short"))
        assertEquals("a".repeat(600), ShareText.cut("a".repeat(600)))
        assertEquals("a".repeat(600) + "…", ShareText.cut("a".repeat(601)))
    }

    @Test fun keysAreStablePerMessage() {
        val items = builder(MessageStyle.Open).build(listOf(msg(7, MessageKind.User, "hi", now)), null)
        assertEquals("m-7", items.single().key)
    }

    @Test fun onlyACutReplyThatEndsTheChatOffersContinue() {
        val rows =
            listOf(
                msg(1, MessageKind.User, "one", now),
                msg(2, MessageKind.Assistant, "cut early", now, state = MessageState.Cut),
                msg(3, MessageKind.User, "two", now),
                msg(4, MessageKind.Assistant, "cut too", now, state = MessageState.Cut),
            )
        val replies = builder(MessageStyle.Open).build(rows, null).filterIsInstance<ReplyItem>()
        assertEquals(listOf(true, true), replies.map { it.cut })
        assertEquals(listOf(false, true), replies.map { it.canContinue })
        assertEquals(listOf(false, false), replies.map { it.stopped })
    }
}
