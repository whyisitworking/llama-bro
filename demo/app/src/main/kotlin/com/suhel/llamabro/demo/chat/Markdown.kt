package com.suhel.llamabro.demo.chat

import androidx.tracing.trace
import com.suhel.llamabro.demo.designsystem.components.MarkdownAlignment
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.designsystem.components.MarkdownSpan
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock.ListItem as ListEntry
import org.commonmark.node.ListBlock as CmList
import org.commonmark.node.ListItem as CmListItem

/**
 * A reply's markdown, parsed by commonmark-java with the GFM tables, strikethrough and bare-URL links (D74), into the
 * design system's blocks. Images show their alt text, HTML shows as typed, and maths is left as text. An open fence
 * runs to the end as code, so a streaming reply never flashes raw backticks.
 */
object Markdown {
    private val parser =
        Parser
            .builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build()

    /** A line being written that is only dashes or equals signs: complete, it could underline the line above. */
    private val underline = Regex("^ {0,3}(-+|=+)[ \t]*$")

    /** Parses all of [text]. */
    fun parse(text: String): MarkdownBlocks = parse(text, null)

    /**
     * Parses [text], the previous reply's text plus more. Only the part the old text's last line may still join is
     * parsed again, with what follows it: the line being written can grow the block above it (a table's next row, a
     * list item's next line, a setext underline), but nothing before that. In a list that part is the last item, and
     * in a table the last row (read again under the table's header), so a long list or table costs the same per append
     * as a short one. The blocks before it keep their instances from [previous], so they don't redraw while a reply
     * streams; those parsed again that come out equal keep theirs. While streaming, a last line of only `-` or `=`
     * waits for its end, so "Steps:" doesn't flash as a heading before "- step" is written.
     */
    fun parse(text: String, previous: Parsed?): MarkdownBlocks = parseKeeping(text, previous).blocks

    /**
     * A parse that remembers where each block's source is in the text ([sources]) and where the next append parses
     * again from ([restart]: the last block that starts before the text's last line). A link reference definition
     * reaches back to links before it, so a reply with one is parsed whole ([whole]). [text] is the text parsed, which
     * leaves out a last line held back as a possible underline.
     */
    class Parsed internal constructor(
        val text: String,
        val blocks: MarkdownBlocks,
        internal val sources: Array<Source>,
        internal val restart: Int,
        internal val whole: Boolean,
    )

    /**
     * Where a block's source is: the line it [start]s on (a top-level list item's own line, else its top-level
     * block's); for a top-level list's item, the [list]'s start (-1 for none) and its [marker]; for a table, its
     * header and delimiter lines and where each body row starts.
     */
    internal class Source(
        val start: Int,
        val list: Int = -1,
        val marker: String? = null,
        val header: String? = null,
        val rows: IntArray? = null,
    )

    fun parseKeeping(text: String, previous: Parsed?): Parsed =
        trace("Markdown.parse") {
            parseKeepingUntraced(text, previous)
        }

    private fun parseKeepingUntraced(full: String, previous: Parsed?): Parsed {
        val lastLine = full.lastIndexOf('\n') + 1
        val text =
            if (previous != null && lastLine > 0 && underline.matches(full.substring(lastLine))) {
                full.substring(0, lastLine)
            } else {
                full
            }
        if (previous == null || previous.whole || !text.startsWith(previous.text)) return parseWhole(text, previous)
        val restart = previous.restart
        val at = previous.sources.indexOfFirst { it.start == restart }
        val source = previous.sources.getOrNull(at)
        val resumed =
            when {
                source?.rows != null -> resumeTable(text, previous, at, source)
                source != null && source.list in 0 until restart -> resumeList(text, previous, at, source)
                else -> null
            }
        return resumed ?: parseFrom(text, restart, previous) ?: parseWhole(text, previous)
    }

    private fun parseWhole(text: String, previous: Parsed?): Parsed = checkNotNull(parseFrom(text, 0, previous))

    /** Parses from a list's item past its first, as a list of its own numbered on from the item, else from the list. */
    private fun resumeList(text: String, previous: Parsed, at: Int, source: Source): Parsed? {
        val item = previous.blocks.blocks[at] as ListEntry
        val resume = ListResume(source.list, source.marker, item.index)
        return parseFrom(text, source.start, previous, list = resume) ?: parseFrom(text, source.list, previous)
    }

    /** Parses from a table's last row that starts before the old text's last line, under the table's header. */
    private fun resumeTable(text: String, previous: Parsed, at: Int, source: Source): Parsed? {
        val rows = checkNotNull(source.rows)
        val header = checkNotNull(source.header)
        val lastLine = previous.text.lastIndexOf('\n') + 1
        val row = rows.indexOfLast { it < lastLine }
        if (row < 0) return null
        val resume = TableResume(previous.blocks.blocks[at] as MarkdownBlock.Table, source, row)
        return parseFrom(text, rows[row] - header.length, previous, table = resume, keepBefore = source.start)
    }

    /** Where a parse from inside a list picks up: the list's start, its marker, and the first item's number. */
    private class ListResume(val list: Int, val marker: String?, val index: Int)

    /** Where a parse from inside a table picks up: the table so far, its source, and the first row read again. */
    private class TableResume(val table: MarkdownBlock.Table, val source: Source, val row: Int)

    /**
     * Parses [text] from [from], a line start, keeping [previous]'s blocks before [keepBefore]; null when the part read
     * holds a link reference definition and earlier text must be read again with it, or when it doesn't open with the
     * rest of the [list] or [table] it was to resume. For a [table], [from] is where the table's header would start
     * if its rows from the resumed one followed it.
     */
    private fun parseFrom(
        text: String,
        from: Int,
        previous: Parsed?,
        list: ListResume? = null,
        table: TableResume? = null,
        keepBefore: Int = from,
    ): Parsed? {
        val input =
            when {
                table != null -> checkNotNull(table.source.header) + text.substring(from + table.source.header.length)
                from == 0 -> text
                else -> text.substring(from)
            }
        val document = parser.parse(input)
        val whole = MarkdownTree.hasDefinition(document)
        if (whole && from > 0) return null
        val first = document.firstChild
        val out = Collector(text, previous, keepBefore)
        when {
            list != null -> if (!resumesList(first, list, from, out)) return null
            table != null -> if (!resumesTable(first, table, from, out)) return null
            first != null -> add(first, from, out)
        }
        var node = first?.next
        while (node != null) {
            add(node, from, out)
            node = node.next
        }
        return out.parsed(whole)
    }

    private fun resumesList(first: Node?, resume: ListResume, from: Int, out: Collector): Boolean {
        if (first !is CmList || lineStart(first) != 0 || MarkdownTree.marker(first) != resume.marker) return false
        for ((item, start) in MarkdownTree.items(first, resume.index)) {
            out.add(item, Source(from + start, resume.list, resume.marker))
        }
        return true
    }

    private fun resumesTable(first: Node?, resume: TableResume, from: Int, out: Collector): Boolean {
        if (first !is TableBlock || lineStart(first) != 0) return false
        val read = MarkdownTree.table(first)
        val old = resume.table
        if (read.header != old.header || read.alignments != old.alignments) return false
        val rows = checkNotNull(resume.source.rows)
        val table = MarkdownBlock.Table(old.alignments, old.header, old.rows.subList(0, resume.row) + read.rows)
        val starts = rows.copyOf(resume.row) + MarkdownTree.rowStarts(first).map { from + it }
        out.add(table, Source(resume.source.start, header = resume.source.header, rows = starts))
        return true
    }

    /** Adds the blocks of a top-level [node] of a part parsed from [from]. */
    private fun add(node: Node, from: Int, out: Collector) {
        val start = from + lineStart(node)
        when (node) {
            is CmList -> {
                val marker = MarkdownTree.marker(node)
                for ((item, itemStart) in MarkdownTree.items(node, null)) {
                    out.add(item, Source(from + itemStart, start, marker))
                }
            }

            is TableBlock -> {
                // the header and delimiter lines, kept to read later rows under; only a table with body rows resumes
                val rows = MarkdownTree.rowStarts(node).map { from + it }.toIntArray()
                val header = if (rows.isEmpty()) null else out.text(start, rows[0])
                out.add(MarkdownTree.table(node), Source(start, header = header, rows = header?.let { rows }))
            }

            else -> {
                for (block in MarkdownTree.blocks(node)) out.add(block, Source(start))
            }
        }
    }

    /** The blocks of a parse of [text] in order: [previous]'s before [keepBefore], then those read, with sources. */
    private class Collector(private val text: String, private val previous: Parsed?, keepBefore: Int) {
        private val blocks = ArrayList<MarkdownBlock>()
        private val sources = ArrayList<Source>()

        init {
            if (previous != null) {
                var kept = 0
                while (kept < previous.sources.size && previous.sources[kept].start < keepBefore) kept++
                blocks += previous.blocks.blocks.subList(0, kept)
                for (i in 0 until kept) sources += previous.sources[i]
            }
        }

        fun add(block: MarkdownBlock, source: Source) {
            // a block that came out the same as before keeps its instance, so it doesn't redraw
            val old = previous?.blocks?.blocks?.getOrNull(blocks.size)
            blocks += if (old != null && old == block) old else block
            sources += source
        }

        fun text(start: Int, end: Int): String = text.substring(start, end)

        fun parsed(whole: Boolean): Parsed {
            val lastLine = text.lastIndexOf('\n') + 1
            val restart = sources.lastOrNull { it.start < lastLine }?.start ?: 0
            return Parsed(text, MarkdownBlocks(blocks), sources.toTypedArray(), restart, whole)
        }
    }
}

/** Where the line of a [node]'s first character starts in the text parsed. */
private fun lineStart(node: Node): Int {
    val span = node.sourceSpans.firstOrNull() ?: return 0
    return span.inputIndex - span.columnIndex
}

/** Maps commonmark's tree into the design system's blocks. */
private object MarkdownTree {
    /** Link targets a tap may open: the web and mail. Others (intent:, tel:, file:, content:, deep links) stay text. */
    private val safeSchemes = setOf("http", "https", "mailto")

    fun hasDefinition(node: Node): Boolean = node is LinkReferenceDefinition || kids(node).any(::hasDefinition)

    /** A list's kind and marker: "-", "*" or "+" for bullets, "1." or "1)" for numbers. */
    fun marker(list: CmList): String? =
        when (list) {
            is BulletList -> list.marker
            is OrderedList -> "1" + list.markerDelimiter
            else -> null
        }

    /** The design system's blocks for one parsed block: none, one, or a list's items. */
    fun blocks(node: Node): List<MarkdownBlock> =
        when (node) {
            is Paragraph -> listOf(MarkdownBlock.Paragraph(spans(node)))

            is Heading -> listOf(MarkdownBlock.Heading(node.level, spans(node)))

            is FencedCodeBlock -> listOf(MarkdownBlock.Code(language(node.info), node.literal.removeSuffix("\n")))

            is IndentedCodeBlock -> listOf(MarkdownBlock.Code(null, node.literal.removeSuffix("\n")))

            is CmList -> items(node, null).map { it.first }

            // a quote that holds only a link reference definition shows nothing
            is BlockQuote -> children(node).let { if (it.isEmpty()) emptyList() else listOf(MarkdownBlock.Quote(it)) }

            is ThematicBreak -> listOf(MarkdownBlock.Rule)

            is TableBlock -> listOf(table(node))

            // shown as typed: the app renders no HTML
            is HtmlBlock -> listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan(node.literal.trimEnd('\n')))))

            is LinkReferenceDefinition -> emptyList()

            else -> children(node)
        }

    private fun children(node: Node): List<MarkdownBlock> = kids(node).flatMap(::blocks).toList()

    private fun kids(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }

    /** The first word of a fence's info string, as GFM reads it: "kotlin" of "kotlin title=x". */
    private fun language(info: String?): String? =
        info
            ?.trim()
            ?.substringBefore(' ')
            ?.ifEmpty { null }

    /**
     * A list's items, numbered from its start (or from [continuing], when the list is the rest of one already begun),
     * each with the line its source starts on: its first paragraph beside its marker, the rest under it.
     */
    fun items(list: CmList, continuing: Int?): List<Pair<ListEntry, Int>> {
        val ordered = list is OrderedList
        val start = continuing ?: (list as? OrderedList)?.markerStartNumber ?: 1
        return kids(list)
            .filterIsInstance<CmListItem>()
            .mapIndexed { k, item ->
                val content = children(item)
                val first = content.firstOrNull() as? MarkdownBlock.Paragraph
                ListEntry(
                    ordered = ordered,
                    index = if (ordered) start + k else 0,
                    spans = first?.spans ?: emptyList(),
                    blocks = if (first != null) content.drop(1) else content,
                    startsList = k == 0 && continuing == null,
                ) to lineStart(item)
            }.toList()
    }

    /** Where each body row of a table starts, in the text parsed. */
    fun rowStarts(table: TableBlock): List<Int> =
        kids(table)
            .flatMap(::kids)
            .filterIsInstance<TableRow>()
            .drop(1)
            .map(::lineStart)
            .toList()

    fun table(table: TableBlock): MarkdownBlock.Table {
        // the head's row, then the body's
        val rows =
            kids(table)
                .flatMap(::kids)
                .filterIsInstance<TableRow>()
                .map { kids(it).filterIsInstance<TableCell>().toList() }
                .toList()
        val header = rows.firstOrNull().orEmpty()
        val width = header.size
        val alignments =
            header.map {
                when (it.alignment) {
                    TableCell.Alignment.CENTER -> MarkdownAlignment.Center
                    TableCell.Alignment.RIGHT -> MarkdownAlignment.End
                    else -> MarkdownAlignment.Start
                }
            }
        // a body row is as wide as the header: short rows are filled with empty cells, long ones cut
        val body = rows.drop(1).map { row -> List(width) { row.getOrNull(it)?.let(::spans).orEmpty() } }
        return MarkdownBlock.Table(alignments, header.map(::spans), body)
    }

    /** The inline marks in force while a node's text is read. */
    private data class Marks(
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val link: String? = null,
    )

    /** The inline text of a paragraph, heading or cell as spans, neighbours with the same marks joined. */
    private fun spans(node: Node): List<MarkdownSpan> {
        val out = ArrayList<MarkdownSpan>()
        inline(node, Marks(), out)
        return out.ifEmpty { listOf(MarkdownSpan("")) }
    }

    private fun inline(parent: Node, marks: Marks, out: MutableList<MarkdownSpan>) {
        var node = parent.firstChild
        while (node != null) {
            when (node) {
                is Text -> add(out, node.literal, marks, code = false)

                is Code -> add(out, node.literal, marks, code = true)

                is SoftLineBreak -> add(out, " ", marks, code = false)

                is HardLineBreak -> add(out, "\n", marks, code = false)

                // shown as typed: the app renders no HTML
                is HtmlInline -> add(out, node.literal, marks, code = false)

                is Emphasis -> inline(node, marks.copy(italic = true), out)

                is StrongEmphasis -> inline(node, marks.copy(bold = true), out)

                is Strikethrough -> inline(node, marks.copy(strike = true), out)

                is Link -> inline(node, marks.copy(link = node.destination.takeIf(::safe)), out)

                // an image shows its alt text, the image's children, plainly
                else -> inline(node, marks, out)
            }
            node = node.next
        }
    }

    private fun safe(url: String): Boolean = url.substringBefore(':', "").lowercase() in safeSchemes

    private fun add(out: MutableList<MarkdownSpan>, text: String, marks: Marks, code: Boolean) {
        if (text.isEmpty()) return
        val span = MarkdownSpan(text, marks.bold, marks.italic, code, marks.link, marks.strike)
        val last = out.lastOrNull()
        if (last != null && last.copy(text = "") == span.copy(text = "")) {
            out[out.lastIndex] = last.copy(text = last.text + text)
        } else {
            out += span
        }
    }
}
