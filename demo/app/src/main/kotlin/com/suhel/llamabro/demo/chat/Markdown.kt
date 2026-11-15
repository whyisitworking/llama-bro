package com.suhel.llamabro.demo.chat

import androidx.tracing.trace
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.designsystem.components.MarkdownSpan
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock.ListItem as ListEntry

/**
 * The small markdown the app shows: paragraphs, `#`-`###` headings, `**bold**`, `*italic*`, `` `code` ``,
 * fenced code blocks, `-`/`*`/`1.` lists, `>` quotes and links. Anything else is shown as typed. An open fence renders
 * as code, so a streaming reply never flashes raw backticks.
 */
object Markdown {
    /** Parses all of [text]. */
    fun parse(text: String): MarkdownBlocks = parse(text, null)

    /**
     * Parses [text], the previous reply's text plus more. Blocks that ended before the old text's last block keep their
     * instances from [previous], so they don't redraw while a reply streams. Only the last block is parsed again.
     */
    fun parse(text: String, previous: Parsed?): MarkdownBlocks = parseKeeping(text, previous).blocks

    /** A parse that remembers where each block ended in the text, for the next append. */
    class Parsed internal constructor(val text: String, val blocks: MarkdownBlocks, internal val ends: List<Int>)

    fun parseKeeping(text: String, previous: Parsed?): Parsed =
        trace("Markdown.parse") {
            parseKeepingUntraced(text, previous)
        }

    private fun parseKeepingUntraced(text: String, previous: Parsed?): Parsed {
        val lines = splitLines(text)
        val blocks = mutableListOf<MarkdownBlock>()
        val ends = mutableListOf<Int>()
        // reuse the previous blocks that are certainly finished: all but the last one, when text only grew
        var start = 0
        if (previous != null && text.startsWith(previous.text) && previous.ends.size >= 2) {
            val keep = previous.ends.size - 1
            blocks += previous.blocks.blocks.subList(0, keep)
            ends += previous.ends.subList(0, keep)
            start = ends.last()
        }
        val lineStart = IntArray(lines.size + 1)
        for (k in lines.indices) lineStart[k + 1] = lineStart[k] + lines[k].length + 1
        var i = lineStart.indexOfFirst { it >= start }.let { if (it < 0) lines.size else it }
        while (i < lines.size) {
            val read = readBlock(lines, i)
            if (read == null) {
                i++
                continue
            }
            val (block, next) = read
            blocks += block
            ends += lineStart[next].coerceAtMost(text.length)
            i = next
        }
        return Parsed(text, MarkdownBlocks(blocks), ends)
    }

    private fun splitLines(text: String): List<String> = text.split('\n')

    private val fence = Regex("^\\s*```(.*)$")
    private val heading = Regex("^(#{1,3})\\s+(.*)$")
    private val bullet = Regex("^\\s*[-*]\\s+(.*)$")
    private val numbered = Regex("^\\s*(\\d+)\\.\\s+(.*)$")
    private val quote = Regex("^\\s*>\\s?(.*)$")

    /** The block starting at line [i] and the index of the line after it, or null for a blank line. */
    private fun readBlock(lines: List<String>, i: Int): Pair<MarkdownBlock, Int>? {
        val line = lines[i]
        if (line.isBlank()) return null
        return fence.matchEntire(line)?.let { readCode(lines, i, it) }
            ?: readListItem(lines, i)
            ?: singleLineBlock(line)?.let { it to i + 1 }
            ?: readParagraph(lines, i)
    }

    /** A fenced block: everything to the closing fence, or to the end when it is still open (streaming). */
    private fun readCode(lines: List<String>, i: Int, open: MatchResult): Pair<MarkdownBlock, Int> {
        val language = open.groupValues[1].trim().ifEmpty { null }
        val body = lines.drop(i + 1).takeWhile { !it.trimStart().startsWith("```") }
        val closingFence = i + 1 + body.size
        val next = if (closingFence < lines.size) closingFence + 1 else closingFence
        return MarkdownBlock.Code(language, body.joinToString("\n")) to next
    }

    /**
     * A list entry and its continuation lines: as CommonMark, the non-blank lines after it that start no other
     * block belong to it ("1. **Rest well**" then "Go to bed at the same time…" is one item), so its text stays
     * indented under the marker instead of becoming a paragraph of its own.
     */
    private fun readListItem(lines: List<String>, i: Int): Pair<MarkdownBlock, Int>? {
        val line = lines[i]
        val (ordered, index, first) =
            bullet.matchEntire(line)?.let { Triple(false, 0, it.groupValues[1]) }
                ?: numbered.matchEntire(line)?.let { Triple(true, it.groupValues[1].toInt(), it.groupValues[2]) }
                ?: return null
        val rest = lines.drop(i + 1).takeWhile { it.isNotBlank() && !startsBlock(it) }
        val text = (listOf(first) + rest).joinToString(" ") { it.trim() }
        return ListEntry(ordered, index, spans(text)) to i + 1 + rest.size
    }

    /** A heading or a quote: blocks of exactly one line. */
    private fun singleLineBlock(line: String): MarkdownBlock? =
        heading.matchEntire(line)?.let { MarkdownBlock.Heading(it.groupValues[1].length, spans(it.groupValues[2])) }
            ?: quote.matchEntire(line)?.let { MarkdownBlock.Quote(spans(it.groupValues[1])) }

    /** A paragraph runs to the next blank line or the next block start. */
    private fun readParagraph(lines: List<String>, i: Int): Pair<MarkdownBlock, Int> {
        val rest = lines.drop(i + 1).takeWhile { it.isNotBlank() && !startsBlock(it) }
        val text = (listOf(lines[i]) + rest).joinToString(" ") { it.trim() }
        return MarkdownBlock.Paragraph(spans(text)) to i + 1 + rest.size
    }

    private fun startsBlock(line: String): Boolean =
        fence.matches(line) || heading.matches(line) || bullet.matches(line) || numbered.matches(line) ||
            quote.matches(line)

    private val inline =
        Regex(
            "`(?<code>[^`]+)`|\\*\\*(?<bold>.+?)\\*\\*|\\*(?<italic>.+?)\\*|\\[(?<text>[^\\]]+)]\\((?<url>[^)\\s]+)\\)",
        )

    /** The span one inline match stands for: code, bold, italic, or a link. */
    private fun span(m: MatchResult): MarkdownSpan {
        val groups = m.groups
        return when {
            groups["code"] != null -> MarkdownSpan(groups["code"]!!.value, code = true)
            groups["bold"] != null -> MarkdownSpan(groups["bold"]!!.value, bold = true)
            groups["italic"] != null -> MarkdownSpan(groups["italic"]!!.value, italic = true)
            else -> MarkdownSpan(groups["text"]!!.value, link = groups["url"]!!.value)
        }
    }

    /** Inline marks of one block's text. Unmatched marks stay as typed. */
    internal fun spans(text: String): List<MarkdownSpan> {
        val out = mutableListOf<MarkdownSpan>()
        var last = 0
        for (m in inline.findAll(text)) {
            if (m.range.first > last) out += MarkdownSpan(text.substring(last, m.range.first))
            out += span(m)
            last = m.range.last + 1
        }
        if (last < text.length) out += MarkdownSpan(text.substring(last))
        return out.ifEmpty { listOf(MarkdownSpan("")) }
    }
}
