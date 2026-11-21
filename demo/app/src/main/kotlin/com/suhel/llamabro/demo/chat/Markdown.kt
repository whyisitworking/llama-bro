package com.suhel.llamabro.demo.chat

import androidx.tracing.trace
import com.suhel.llamabro.demo.designsystem.components.MarkdownAlignment
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlocks
import com.suhel.llamabro.demo.designsystem.components.MarkdownSpan
import com.suhel.llamabro.demo.models.Names
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
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
 * runs to the end as code, so a streaming reply never flashes raw backticks. A model's page ([parsePage]) is read the
 * same way, but its HTML is flattened to its text.
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
     * Parses a model's page (its `README.md`, models-screen design "The card"). Pages lean on HTML for centred logos,
     * badges and link rows; the app draws no HTML, so a block of it becomes its text, a paragraph per HTML paragraph,
     * and inline tags go, leaving their words. Images keep their alt text: the app has no image loader (D116). A
     * leading heading that only repeats [dropHeading] (the model's name, already the sheet's title) is left out: the
     * first heading before any text of the page's own, after the HTML logos and badges many pages open with, read as
     * [Names.key] reads a repo's name and without separators, so "# Qwen3.5-4B-GGUF" goes under "Qwen3.5 4B" (models
     * audit S5). What a page carries for GitHub rather than for a reader is left out first ([PageNoise], D123): its
     * note and warning boxes, and paragraphs or HTML blocks that are only links or badge images.
     */
    fun parsePage(text: String, dropHeading: String? = null): MarkdownBlocks =
        trace("Markdown.parsePage") {
            val document = parser.parse(text)
            PageNoise.strip(document)
            if (dropHeading != null) {
                val first =
                    generateSequence(document.firstChild) { it.next }.firstOrNull { it !is HtmlBlock } as? Heading
                if (first != null && bare(MarkdownTree.Page.plainText(first)) == bare(dropHeading)) first.unlink()
            }
            MarkdownBlocks(MarkdownTree.Page.children(document))
        }

    /** [name] as a model's key, without separators: "Qwen3.5-4B-GGUF" and "qwen3.5 4b" read alike. */
    private fun bare(name: String): String = Names.key(name).filter { it.isLetterOrDigit() }

    /**
     * Parses [text], the previous reply's text plus more. Only the top-level block the old text's last line may still
     * join is parsed again, with what follows it: the line being written can grow the block above it (a table's next
     * row, a list's next item, a setext underline), but nothing before that. A list or a table being written is parsed
     * again whole on each append. The blocks before it keep their instances from [previous], so they don't redraw
     * while a reply streams; those parsed again that come out equal keep theirs. While streaming, a last line of only
     * `-` or `=` waits for its end, so "Steps:" doesn't flash as a heading before "- step" is written.
     */
    fun parse(text: String, previous: Parsed?): MarkdownBlocks = parseKeeping(text, previous).blocks

    /**
     * A parse that remembers the line each block's top-level source starts on ([starts]; a list's items share their
     * list's) and where the next append parses again from ([restart]: the last top-level block that starts before the
     * text's last line). A link reference definition reaches back to links before it, so a reply with one is parsed
     * whole ([whole]). [text] is the text parsed, which leaves out a last line held back as a possible underline.
     */
    class Parsed internal constructor(
        val text: String,
        val blocks: MarkdownBlocks,
        internal val starts: IntArray,
        internal val restart: Int,
        internal val whole: Boolean,
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
        val from = if (previous == null || previous.whole || !text.startsWith(previous.text)) 0 else previous.restart
        // from 0 a parse never returns null: a definition there has nothing earlier to reach back to
        return parseFrom(text, from, previous) ?: checkNotNull(parseFrom(text, 0, previous))
    }

    /**
     * Parses [text] from [from], a top-level block's line start, keeping [previous]'s blocks before it; null when the
     * part read holds a link reference definition, so earlier text must be read again with it.
     */
    private fun parseFrom(text: String, from: Int, previous: Parsed?): Parsed? {
        val document = parser.parse(if (from == 0) text else text.substring(from))
        val whole = MarkdownTree.hasDefinition(document)
        if (whole && from > 0) return null
        val out = Collector(text, previous, from)
        var node = document.firstChild
        while (node != null) {
            val start = from + lineStart(node)
            for (block in MarkdownTree.Reply.blocks(node)) out.add(block, start)
            node = node.next
        }
        return out.parsed(whole)
    }

    /** A parse of [text]'s blocks in order: [previous]'s before [keepBefore], then those read, with their starts. */
    private class Collector(private val text: String, private val previous: Parsed?, keepBefore: Int) {
        private val blocks = ArrayList<MarkdownBlock>()
        private val starts = ArrayList<Int>()

        init {
            if (previous != null) {
                var kept = 0
                while (kept < previous.starts.size && previous.starts[kept] < keepBefore) kept++
                blocks += previous.blocks.blocks.subList(0, kept)
                for (i in 0 until kept) starts += previous.starts[i]
            }
        }

        fun add(block: MarkdownBlock, start: Int) {
            // a block that came out the same as before keeps its instance, so it doesn't redraw
            val old = previous?.blocks?.blocks?.getOrNull(blocks.size)
            blocks += if (old != null && old == block) old else block
            starts += start
        }

        fun parsed(whole: Boolean): Parsed {
            val lastLine = text.lastIndexOf('\n') + 1
            val restart = starts.lastOrNull { it < lastLine } ?: 0
            return Parsed(text, MarkdownBlocks(blocks), starts.toIntArray(), restart, whole)
        }
    }
}

/** Where the line of a [node]'s first character starts in the text parsed. */
private fun lineStart(node: Node): Int {
    val span = node.sourceSpans.firstOrNull() ?: return 0
    return span.inputIndex - span.columnIndex
}

/** Maps commonmark's tree into the design system's blocks: a reply's HTML as typed, a [page]'s flattened to text. */
private class MarkdownTree(private val page: Boolean) {
    companion object {
        val Reply = MarkdownTree(page = false)
        val Page = MarkdownTree(page = true)

        /** Link targets a tap may open: the web and mail. Others (intent:, tel:, file:, deep links) stay text. */
        private val safeSchemes = setOf("http", "https", "mailto")

        fun hasDefinition(node: Node): Boolean = node is LinkReferenceDefinition || kids(node).any(::hasDefinition)

        private fun kids(node: Node): Sequence<Node> = generateSequence(node.firstChild) { it.next }
    }

    /** The design system's blocks for one parsed block: none, one, or a list's items. */
    fun blocks(node: Node): List<MarkdownBlock> =
        when (node) {
            is Paragraph -> {
                listOf(MarkdownBlock.Paragraph(spans(node)))
            }

            is Heading -> {
                listOf(MarkdownBlock.Heading(node.level, spans(node)))
            }

            is FencedCodeBlock -> {
                listOf(MarkdownBlock.Code(language(node.info), node.literal.removeSuffix("\n")))
            }

            is IndentedCodeBlock -> {
                listOf(MarkdownBlock.Code(null, node.literal.removeSuffix("\n")))
            }

            is CmList -> {
                items(node)
            }

            // a quote that holds only a link reference definition shows nothing
            is BlockQuote -> {
                children(node).let { if (it.isEmpty()) emptyList() else listOf(MarkdownBlock.Quote(it)) }
            }

            is ThematicBreak -> {
                listOf(MarkdownBlock.Rule)
            }

            is TableBlock -> {
                listOf(table(node))
            }

            is HtmlBlock -> {
                html(node)
            }

            is LinkReferenceDefinition -> {
                emptyList()
            }

            else -> {
                children(node)
            }
        }

    fun children(node: Node): List<MarkdownBlock> = kids(node).flatMap(::blocks).toList()

    /** A heading's words as it would show them, without their styles. */
    fun plainText(node: Heading): String = spans(node).joinToString("") { it.text }

    /** A reply's HTML shows as typed (the app draws no HTML); a page's is its text, a paragraph per HTML paragraph. */
    private fun html(node: HtmlBlock): List<MarkdownBlock> =
        if (page) {
            PageHtml.paragraphs(node.literal).map { MarkdownBlock.Paragraph(listOf(MarkdownSpan(it))) }
        } else {
            listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan(node.literal.trimEnd('\n')))))
        }

    /** The first word of a fence's info string, as GFM reads it: "kotlin" of "kotlin title=x". */
    private fun language(info: String?): String? =
        info
            ?.trim()
            ?.substringBefore(' ')
            ?.ifEmpty { null }

    /** A list's items, numbered from its start: each one's first paragraph beside its marker, the rest under it. */
    private fun items(list: CmList): List<ListEntry> {
        val ordered = list is OrderedList
        val start = (list as? OrderedList)?.markerStartNumber ?: 1
        return kids(list)
            .filterIsInstance<CmListItem>()
            .mapIndexed { k, item ->
                val content = children(item)
                val first = content.firstOrNull() as? MarkdownBlock.Paragraph
                ListEntry(
                    ordered = ordered,
                    index = if (ordered) start + k else 0,
                    spans = first?.spans.orEmpty(),
                    blocks = if (first != null) content.drop(1) else content,
                    startsList = k == 0,
                )
            }.toList()
    }

    private fun table(table: TableBlock): MarkdownBlock.Table {
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
                is Text -> {
                    add(out, node.literal, marks, code = false)
                }

                is Code -> {
                    add(out, node.literal, marks, code = true)
                }

                is SoftLineBreak -> {
                    add(out, " ", marks, code = false)
                }

                is HardLineBreak -> {
                    add(out, "\n", marks, code = false)
                }

                // a reply's shows as typed (the app renders no HTML); a page's tags go, a line break stays one
                is HtmlInline -> {
                    add(
                        out,
                        if (page) PageHtml.inline(node.literal) else node.literal,
                        marks,
                        code = false,
                    )
                }

                is Emphasis -> {
                    inline(node, marks.copy(italic = true), out)
                }

                is StrongEmphasis -> {
                    inline(node, marks.copy(bold = true), out)
                }

                is Strikethrough -> {
                    inline(node, marks.copy(strike = true), out)
                }

                is Link -> {
                    inline(node, marks.copy(link = node.destination.takeIf(::safe)), out)
                }

                // an image shows its alt text, the image's children, plainly
                else -> {
                    inline(node, marks, out)
                }
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

/** A model page's HTML as plain text: tags, comments, scripts and styles go; entities are read; paragraphs are kept. */
private object PageHtml {
    private val dropped =
        Regex("<!--.*?-->|<(script|style)\\b.*?</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val breaks =
        Regex(
            "</?(p|div|br|h[1-6]|li|ul|ol|tr|table|center|details|summary|blockquote|pre|hr)\\b[^>]*>",
            RegexOption.IGNORE_CASE,
        )
    private val tag = Regex("<[^>]*>")
    private val lineBreak = Regex("<br\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val paragraph = Regex("\\n\\s*\\n")
    private val space = Regex("\\s+")
    private val entities =
        // `&amp;` last, so "&amp;lt;" reads as "&lt;", not "<"
        listOf("&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&nbsp;" to " ", "&amp;" to "&")

    /** The text of a block of HTML, a paragraph per block element, blanks left out. */
    fun paragraphs(html: String): List<String> =
        decode(tag.replace(breaks.replace(dropped.replace(html, ""), "\n\n"), ""))
            .split(paragraph)
            .map { it.replace(space, " ").trim() }
            .filter { it.isNotEmpty() }

    /** An inline tag's text: a line break stays one, any other tag is nothing. */
    fun inline(html: String): String = if (lineBreak.matches(html)) "\n" else ""

    private fun decode(text: String): String = entities.fold(text) { acc, (entity, char) -> acc.replace(entity, char) }
}

/**
 * What a model's page carries for GitHub and not for a reader (D123: the less text, the better): a note
 * box ("> [!Note]" and its kin), a paragraph that is only links or badge images (a row of badges, a bare link line),
 * and a block of HTML that is only links and images (a centred logo, a badge row). Top-level blocks only: a link inside
 * a list or a sentence is the page's own.
 */
private object PageNoise {
    private val alert =
        Regex("^\\s*\\[!(note|tip|important|warning|caution)]", RegexOption.IGNORE_CASE)
    private val separators = Regex("[\\s|·•,/–—-]*")
    private val linksAndImages =
        Regex("<a\\b[^>]*>.*?</a\\s*>|<img\\b[^>]*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val linkOrImage = Regex("<(a|img)\\b", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]*>|&nbsp;")

    fun strip(document: Node) {
        generateSequence(document.firstChild) { it.next }.toList().filter(::isNoise).forEach(Node::unlink)
    }

    private fun isNoise(node: Node): Boolean =
        when (node) {
            is BlockQuote -> (node.firstChild as? Paragraph)?.let { alert.containsMatchIn(literal(it)) } == true
            is Paragraph -> onlyLinks(node)
            is HtmlBlock -> linkOrImage.containsMatchIn(node.literal) && onlyLinks(node.literal)
            else -> false
        }

    /** A paragraph of nothing but links and images, with at most spaces, breaks and separators between them. */
    private fun onlyLinks(paragraph: Paragraph): Boolean {
        var links = 0
        var node = paragraph.firstChild
        while (node != null) {
            when (node) {
                is Link, is Image -> links++
                is Text -> if (!separators.matches(node.literal)) return false
                is HtmlInline -> if (linkOrImage.containsMatchIn(node.literal)) links++
                is SoftLineBreak, is HardLineBreak -> Unit
                else -> return false
            }
            node = node.next
        }
        return links > 0
    }

    /** HTML whose text, once its links and images are gone, is nothing but separators. */
    private fun onlyLinks(html: String): Boolean = separators.matches(tag.replace(linksAndImages.replace(html, ""), ""))

    /** The text a paragraph's own words spell, without their marks. */
    private fun literal(node: Node): String =
        if (node is Text) {
            node.literal
        } else {
            generateSequence(node.firstChild) { it.next }.joinToString("", transform = ::literal)
        }
}
