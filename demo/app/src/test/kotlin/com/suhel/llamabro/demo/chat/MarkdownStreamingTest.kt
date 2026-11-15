package com.suhel.llamabro.demo.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long reply arriving a few characters at a time, as the chat parses it while it streams: the median parse per
 * append stays inside the budget of docs/design/demo-app.md §10 (0.5 ms on the phone; the host is faster, so this
 * catches a parser that re-reads the whole reply, not a small regression).
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

    /** A reply that is one long list, about 10 KB: each append parses only the last item again. */
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

    private fun streamOnce(reply: String): List<Long> {
        val times = ArrayList<Long>()
        var parsed: Markdown.Parsed? = null
        var end = 0
        while (end < reply.length) {
            end = minOf(reply.length, end + 4)
            val text = reply.substring(0, end)
            val t0 = System.nanoTime()
            parsed = Markdown.parseKeeping(text, parsed)
            times += System.nanoTime() - t0
        }
        return times
    }

    private fun median(name: String, reply: String): Double {
        repeat(5) { streamOnce(reply) } // warm up the JIT
        val times = streamOnce(reply).sorted()
        val medianMs = times[times.size / 2] / 1e6
        println(
            "Markdown.parseKeeping median per append, %s: %.4f ms over %d appends".format(name, medianMs, times.size),
        )
        return medianMs
    }

    @Test fun medianParsePerAppendIsWithinTheBudget() {
        val medianMs = median("mixed reply", reply)
        assertTrue("median $medianMs ms", medianMs <= 0.5)
    }

    @Test fun aLongListCostsNoMoreThanAShortOne() {
        val medianMs = median("one long list", longList)
        assertTrue("median $medianMs ms", medianMs <= 0.5)
    }

    @Test fun aLongTableStaysWithinTheBudget() {
        val medianMs = median("one long table", longTable)
        assertTrue("median $medianMs ms", medianMs <= 0.5)
    }
}
