package com.suhel.llamabro.demo.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long reply arriving a few characters at a time, as the chat parses it while it streams: each append reads again
 * only from the top-level block the old text's last line could still join (D108), never the reply before it, and
 * the blocks before that keep their instances. That is what keeps a parse per append inside the 0.5 ms budget of
 * docs/design/demo-app.md §10 however long the reply grows. The work is counted ([Markdown.Parsed.readFrom]), not
 * timed: a wall clock on a loaded test machine measures the machine. The time per append is measured on the phone,
 * by `StreamingBenchmark` against that budget (`demo/scripts/check.sh --perf`).
 */
class MarkdownStreamingTest {
    private val reply =
        buildString {
            repeat(6) { n ->
                append("## Part $n\n\nSome **bold** text, *italic*, `code` and a [link](https://example.com/$n).\n\n")
                append("- first item\n  - nested item\n    1. deeper\n- second item\n\n")
                append("| Left | Centre | Right |\n|:--|:-:|--:|\n| a | b | 1 |\n| c | d | 2 |\n\n")
                append("```kotlin\nfun main() {\n    println(\"$n\")\n}\n```\n\n")
                append("> A quote\n> over two lines.\n\n---\n\n")
            }
        }

    /** A reply that is one long list, about 10 KB: a list is one block, so each append parses it all again. */
    private val longList =
        buildString {
            repeat(250) { n -> append("${n + 1}. Item $n with **some** words and a [link](https://example.com/$n)\n") }
        }

    /** A reply that is one long table, about 10 KB: a table is one block, so each append parses it all again. */
    private val longTable =
        buildString {
            append("| Name | Size | Speed | Notes |\n|:--|--:|--:|:--|\n")
            repeat(250) { n -> append("| model $n | ${n * 7} MB | $n words/s | `fast` and **small** |\n") }
        }

    /**
     * Streams [reply] four characters at a time, each append parsed from the one before, and checks that the blocks
     * before where an append read are the same instances as before. With [checkFloor] (a whole parse per append, so
     * only for the short reply), also that it read from no earlier than the start of the second-to-last top-level
     * block of the text before it (the last block, or the one above when the last line began a new one), as a whole
     * parse of that text places them. Returns where each append read from.
     */
    private fun stream(reply: String, checkFloor: Boolean = false): List<Int> {
        val readFrom = ArrayList<Int>()
        var parsed: Markdown.Parsed? = null
        var end = 0
        while (end < reply.length) {
            end = minOf(reply.length, end + 4)
            val before = parsed
            parsed = Markdown.parseKeeping(reply.substring(0, end), before)
            readFrom += parsed.readFrom
            if (before == null) continue
            assertTrue("no reference definition here, so never a whole parse", !parsed.whole)
            if (checkFloor) {
                val starts = Markdown.parseKeeping(before.text, null).starts.distinct()
                val floor = starts.getOrElse(starts.size - 2) { 0 }
                assertTrue("at $end: read from ${parsed.readFrom}, before block start $floor", parsed.readFrom >= floor)
            }
            val kept = before.starts.count { it < parsed.readFrom }
            for (i in 0 until kept) assertSame("block $i at $end", before.blocks.blocks[i], parsed.blocks.blocks[i])
        }
        return readFrom
    }

    @Test fun eachAppendReadsOnlyTheLastBlockAgain() {
        val readFrom = stream(reply, checkFloor = true)
        // the reply is about 2 KB of blocks under 100 characters each: no append reads more than two of them
        val most = readFrom.withIndex().maxOf { (i, from) -> minOf(reply.length, (i + 1) * 4) - from }
        assertTrue("an append read $most characters", most < 200)
    }

    @Test fun aLongListAfterAReplyReadsOnlyTheList() {
        val listStart = reply.length
        val readFrom = stream(reply + longList)
        // once the list's first line is done, each append reads the list and nothing before it
        val afterFirstLine = (listStart + longList.indexOf('\n') + 1) / 4 + 1
        assertEquals(setOf(listStart), readFrom.drop(afterFirstLine).toSet())
    }

    @Test fun aLongTableAfterAReplyReadsOnlyTheTable() {
        val tableStart = reply.length
        val readFrom = stream(reply + longTable)
        // once the table's first line is done, each append reads the table and nothing before it
        val afterFirstLine = (tableStart + longTable.indexOf('\n') + 1) / 4 + 1
        assertEquals(setOf(tableStart), readFrom.drop(afterFirstLine).toSet())
    }
}
