package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.designsystem.components.MarkdownAlignment
import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock
import com.suhel.llamabro.demo.designsystem.components.MarkdownSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun blocks(text: String) = Markdown.parse(text).blocks

    private fun spans(text: String) = (blocks(text).single() as MarkdownBlock.Paragraph).spans

    private fun item(
        text: String,
        index: Int = 0,
        ordered: Boolean = false,
        blocks: List<MarkdownBlock> = emptyList(),
        first: Boolean = false,
    ) = MarkdownBlock.ListItem(ordered, index, listOf(MarkdownSpan(text)), blocks, startsList = first)

    @Test fun paragraphs() {
        val b = blocks("First line\nsame paragraph\n\nSecond")
        assertEquals(2, b.size)
        assertEquals(listOf(MarkdownSpan("First line same paragraph")), (b[0] as MarkdownBlock.Paragraph).spans)
    }

    @Test fun headingsOfEveryLevel() {
        val b = blocks("# One\n## Two\n### Three\n#### Four\n##### Five\n###### Six")
        assertEquals(listOf(1, 2, 3, 4, 5, 6), b.map { (it as MarkdownBlock.Heading).level })
        assertEquals(listOf(MarkdownSpan("Four")), (b[3] as MarkdownBlock.Heading).spans)
    }

    @Test fun aSetextHeading() {
        assertEquals(MarkdownBlock.Heading(2, listOf(MarkdownSpan("Title"))), blocks("Title\n---").single())
    }

    @Test fun inlineMarks() {
        assertEquals(
            listOf(
                MarkdownSpan("a "),
                MarkdownSpan("bold", bold = true),
                MarkdownSpan(" and "),
                MarkdownSpan("it", italic = true),
                MarkdownSpan(" and "),
                MarkdownSpan("code", code = true),
                MarkdownSpan(" end"),
            ),
            spans("a **bold** and *it* and `code` end"),
        )
    }

    @Test fun underscoreEmphasisBoldItalicAndStrikethrough() {
        assertEquals(
            listOf(
                MarkdownSpan("it", italic = true),
                MarkdownSpan(" "),
                MarkdownSpan("bold", bold = true),
                MarkdownSpan(" "),
                MarkdownSpan("both", bold = true, italic = true),
                MarkdownSpan(" "),
                MarkdownSpan("gone", strike = true),
            ),
            spans("_it_ __bold__ ***both*** ~~gone~~"),
        )
    }

    @Test fun escapesAndIntrawordUnderscoresStayAsTyped() {
        assertEquals(listOf(MarkdownSpan("*not italic* and a_b_c")), spans("\\*not italic\\* and a_b_c"))
    }

    @Test fun entitiesAreDecoded() {
        // commonmark reads its entity table from a resource in its jar, which the release APK keeps
        assertEquals(listOf(MarkdownSpan("\u00A9 & \u00E9")), spans("&copy; &amp; &eacute;"))
    }

    @Test fun links() {
        assertEquals(MarkdownSpan("the docs", link = "https://x.dev/a"), spans("see [the docs](https://x.dev/a)")[1])
    }

    @Test fun bareUrlsAndAngleAutolinks() {
        val s = spans("go to https://example.com/path or <https://example.org>.")
        assertEquals(MarkdownSpan("https://example.com/path", link = "https://example.com/path"), s[1])
        assertEquals(MarkdownSpan("https://example.org", link = "https://example.org"), s[3])
    }

    @Test fun aLinkKeepsItsMarks() {
        assertEquals(
            MarkdownSpan("docs", bold = true, link = "https://x.dev"),
            spans("[**docs**](https://x.dev)").single(),
        )
    }

    @Test fun fencedCode() {
        val c = blocks("```kotlin\nval x = 1\nval y = 2\n```\nafter")
        assertEquals(MarkdownBlock.Code("kotlin", "val x = 1\nval y = 2"), c[0])
        assertTrue(c[1] is MarkdownBlock.Paragraph)
    }

    @Test fun theLanguageIsTheInfoStringsFirstWord() {
        assertEquals(MarkdownBlock.Code("kotlin", "x"), blocks("```kotlin title=\"a\"\nx\n```").single())
    }

    @Test fun indentedCode() {
        assertEquals(MarkdownBlock.Code(null, "val x = 1"), blocks("    val x = 1").single())
    }

    @Test fun unclosedFenceRendersAsCode() {
        assertEquals(MarkdownBlock.Code(null, "val x ="), blocks("```\nval x =")[0])
    }

    @Test fun lists() {
        val b = blocks("- one\n- two\n\n1. first\n2. second")
        assertEquals(listOf(false, false, true, true), b.map { (it as MarkdownBlock.ListItem).ordered })
        assertEquals(listOf(0, 0, 1, 2), b.map { (it as MarkdownBlock.ListItem).index })
    }

    @Test fun anOrderedListCountsFromItsStart() {
        assertEquals(listOf(3, 4), blocks("3. c\n4. d").map { (it as MarkdownBlock.ListItem).index })
    }

    @Test fun nestedListsOfTwoAndThreeLevelsMixed() {
        val b = blocks("- Fruit\n  - Apples\n    1. Gala\n    2. Fuji\n  - Pears\n- Veg")
        assertEquals(
            listOf(
                item(
                    "Fruit",
                    first = true,
                    blocks =
                        listOf(
                            item(
                                "Apples",
                                first = true,
                                blocks =
                                    listOf(
                                        item("Gala", 1, ordered = true, first = true),
                                        item("Fuji", 2, ordered = true),
                                    ),
                            ),
                            item("Pears"),
                        ),
                ),
                item("Veg"),
            ),
            b,
        )
    }

    @Test fun aListItemKeepsItsContinuationLines() {
        // as a model writes it: the item's title, then its text on the next lines, indented or not (CommonMark)
        val b = blocks("1. **Rest well**\n   Go to bed at the same time.\nEvery day.\n2. Second\n\nAfter the list.")
        assertEquals(3, b.size)
        val first = b[0] as MarkdownBlock.ListItem
        assertEquals(1, first.index)
        assertEquals("Rest well Go to bed at the same time. Every day.", first.spans.joinToString("") { it.text })
        assertEquals(true, first.spans[0].bold)
        assertEquals(2, (b[1] as MarkdownBlock.ListItem).index)
        assertEquals(true, b[2] is MarkdownBlock.Paragraph)
    }

    @Test fun aListItemHoldsCodeUnderIt() {
        val b = blocks("1. Run it:\n\n   ```sh\n   ls\n   ```")
        assertEquals(
            item("Run it:", 1, ordered = true, blocks = listOf(MarkdownBlock.Code("sh", "ls")), first = true),
            b.single(),
        )
    }

    @Test fun quotes() {
        assertEquals(
            MarkdownBlock.Quote(listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan("wise words"))))),
            blocks("> wise words")[0],
        )
    }

    @Test fun aMultiLineQuoteIsOneParagraphAndANestedQuoteNests() {
        val b = blocks("> runs\n> over\n>\n> > inner\n>\n> outer")
        val quote = b.single() as MarkdownBlock.Quote
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownSpan("runs over"))), quote.blocks[0])
        assertEquals(
            MarkdownBlock.Quote(listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan("inner"))))),
            quote.blocks[1],
        )
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownSpan("outer"))), quote.blocks[2])
    }

    @Test fun tablesWithAlignmentAndInlineMarks() {
        val t = blocks("| L | C | R | N |\n|:--|:-:|--:|---|\n| a | **b** | `c` | d |\n| e |").single()
        t as MarkdownBlock.Table
        assertEquals(
            listOf(MarkdownAlignment.Start, MarkdownAlignment.Center, MarkdownAlignment.End, MarkdownAlignment.Start),
            t.alignments,
        )
        assertEquals(listOf("L", "C", "R", "N"), t.header.map { cell -> cell.joinToString("") { it.text } })
        assertEquals(listOf(MarkdownSpan("b", bold = true)), t.rows[0][1])
        assertEquals(listOf(MarkdownSpan("c", code = true)), t.rows[0][2])
        // a short row is filled to the header's width
        assertEquals(4, t.rows[1].size)
    }

    @Test fun thematicBreaks() {
        val b = blocks("a\n\n---\n\nb\n\n***\n\nc\n\n* * *\n\nd")
        assertEquals(3, b.count { it == MarkdownBlock.Rule })
        assertEquals(7, b.size)
    }

    @Test fun anImageShowsItsAltText() {
        assertEquals(listOf(MarkdownSpan("see a llama")), spans("see ![a llama](https://x.dev/l.png)"))
    }

    @Test fun htmlShowsAsTyped() {
        assertEquals(listOf(MarkdownSpan("a <b>bold</b>")), spans("a <b>bold</b>"))
        assertEquals(
            MarkdownBlock.Paragraph(listOf(MarkdownSpan("<div>\nx\n</div>"))),
            blocks("<div>\nx\n</div>").single(),
        )
    }

    @Test fun mathsStaysText() {
        assertEquals("x^2 = \$y\$", spans("x^2 = \$y\$").joinToString("") { it.text })
    }

    @Test fun plainTextIsUntouched() {
        assertEquals(listOf(MarkdownSpan("2 * 3 = 6 and a_b")), spans("2 * 3 = 6 and a_b"))
    }

    @Test fun appendingReparsesOnlyTheLastBlock() {
        val first = Markdown.parseKeeping("# Title\n\nFirst paragraph.\n\nSecond par", null)
        val second = Markdown.parseKeeping("# Title\n\nFirst paragraph.\n\nSecond paragraph grows.", first)
        assertEquals(3, second.blocks.blocks.size)
        assertSame(first.blocks.blocks[0], second.blocks.blocks[0])
        assertSame(first.blocks.blocks[1], second.blocks.blocks[1])
        assertEquals(
            MarkdownSpan("Second paragraph grows."),
            (second.blocks.blocks[2] as MarkdownBlock.Paragraph).spans.single(),
        )
    }

    @Test fun aGrowingListKeepsItsEarlierItems() {
        val first = Markdown.parseKeeping("Intro\n\n- one\n- two\n- thr", null)
        val second = Markdown.parseKeeping("Intro\n\n- one\n- two\n- three\n- four", first)
        assertEquals(5, second.blocks.blocks.size)
        for (i in 0..2) assertSame(first.blocks.blocks[i], second.blocks.blocks[i])
    }

    @Test fun aGrowingTableKeepsTheBlocksBeforeIt() {
        val first = Markdown.parseKeeping("Intro\n\n| a | b |\n|---|---|\n| 1 |", null)
        val second = Markdown.parseKeeping("Intro\n\n| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |", first)
        assertSame(first.blocks.blocks[0], second.blocks.blocks[0])
        assertEquals(2, (second.blocks.blocks[1] as MarkdownBlock.Table).rows.size)
    }

    @Test fun aReferenceDefinitionReachesBackToEarlierLinks() {
        val first = Markdown.parseKeeping("see [docs]\n\nmore\n\n", null)
        val second = Markdown.parseKeeping("see [docs]\n\nmore\n\n[docs]: https://x.dev", first)
        assertEquals(
            MarkdownSpan("docs", link = "https://x.dev"),
            (second.blocks.blocks[0] as MarkdownBlock.Paragraph).spans[1],
        )
    }

    /**
     * Every construct, streamed a character at a time: no prefix throws, each incremental parse equals a whole parse
     * of the same text, and the blocks before the one the last line may join keep their instances.
     */
    @Test fun streamingEveryPrefixMatchesAWholeParse() {
        val reply =
            "# Title\n\nSome *it* and __bold__ ~~x~~ \\* `c` https://e.com see [ref]\n\n- a\n  - b\n    1. c\n- d\n" +
                "- e\n  more of e\n\n- loose\n\n+ other bullet\n+ again\n\n1. one\n1. two\n1. three\n\n" +
                "1) paren\n2) paren two\n\n7. seven\n8. eight\n   ```sh\n   ls\n   ```\n9. nine\n\n" +
                "| L | R |\n|:--|--:|\n| 1 | 2 |\n| 3 | 4 |\n\n" +
                "| A | B | C |\n|---|:-:|---|\n| x | **y** |\n| p | q | r | s |\n" +
                "| \\| esc | `c|d` | z |\nnot a row?\n\n> q\n> r\n>\n> > n\n\n---\n\n" +
                "```kotlin\nval x = 1\n```\n\n" +
                "Text ![alt](u) <b>h</b> \$x^2\$\n\nTitle\n---\n\nSteps:\n- step\n\n    indented\n\n" +
                "> [ref]: https://r.dev\n\nend [ref]"
        var parsed: Markdown.Parsed? = null
        for (end in 1..reply.length) {
            val text = reply.substring(0, end)
            val before = parsed
            parsed = Markdown.parseKeeping(text, before)
            assertEquals("at $end: ${text.takeLast(20)}", Markdown.parse(heldBack(text)), parsed.blocks)
            // a whole parse (once a reference definition arrives) may change earlier links: identity holds until then
            if (before != null && !parsed.whole) {
                val stable = before.sources.count { it.start < before.restart }
                for (i in 0 until stable) {
                    assertSame("block $i at $end", before.blocks.blocks[i], parsed.blocks.blocks[i])
                }
            }
        }
        assertEquals(
            MarkdownSpan("ref", link = "https://r.dev"),
            (parsed!!.blocks.blocks[1] as MarkdownBlock.Paragraph).spans.last(),
        )
    }

    /** What a streaming parse reads of [text]: all but a last line of only `-` or `=` after another line. */
    private fun heldBack(text: String): String {
        val lastLine = text.lastIndexOf('\n') + 1
        return if (lastLine > 0 && Regex("^ {0,3}(-+|=+)[ \t]*$").matches(text.substring(lastLine))) {
            text.substring(0, lastLine)
        } else {
            text
        }
    }

    @Test fun aStreamingUnderlineWaitsForItsLineToEnd() {
        val first = Markdown.parseKeeping("Steps:", null)
        val second = Markdown.parseKeeping("Steps:\n-", first)
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan("Steps:")))), second.blocks.blocks)
        val third = Markdown.parseKeeping("Steps:\n- a", second)
        assertEquals(2, third.blocks.blocks.size)
        assertTrue(third.blocks.blocks[1] is MarkdownBlock.ListItem)
        // a finished message is parsed whole, so its setext heading stands
        assertTrue(Markdown.parse("Title\n---").blocks.single() is MarkdownBlock.Heading)
    }

    @Test fun aLongListParsesOnlyItsLastItemAgain() {
        var parsed = Markdown.parseKeeping("1. a\n1. b\n1. c\n1. d", null)
        val again = Markdown.parseKeeping("1. a\n1. b\n1. c\n1. d is longer", parsed)
        for (i in 0..2) assertSame(parsed.blocks.blocks[i], again.blocks.blocks[i])
        assertEquals(listOf(1, 2, 3, 4), again.blocks.blocks.map { (it as MarkdownBlock.ListItem).index })
        assertEquals(
            listOf(true, false, false, false),
            again.blocks.blocks.map { (it as MarkdownBlock.ListItem).startsList },
        )
        parsed = Markdown.parseKeeping("1. a\n1. b\n1. c\n1. d is longer\n1. e", again)
        assertEquals(Markdown.parse("1. a\n1. b\n1. c\n1. d is longer\n1. e"), parsed.blocks)
    }

    @Test fun adjacentListsAreMarkedAsSeparate() {
        assertEquals(
            listOf(true, false, true),
            blocks("- a\n- b\n+ c").map { (it as MarkdownBlock.ListItem).startsList },
        )
    }

    @Test fun onlyWebAndMailLinksStayLinks() {
        val s =
            spans(
                "[a](intent://x) [b](tel:123) [c](file:///x) [d](content://x) [e](llamabro://x) " +
                    "[f](HTTPS://a) [g](mailto:a@b.c) [h](/rel)",
            )
        assertEquals(
            listOf("HTTPS://a", "mailto:a@b.c"),
            s.mapNotNull { it.link },
        )
        assertEquals("a b c d e f g h", s.joinToString("") { it.text })
    }

    @Test fun aDefinitionInsideAQuoteOrListIsFound() {
        val first = Markdown.parseKeeping("see [x]\n\nmore\n\n", null)
        val second = Markdown.parseKeeping("see [x]\n\nmore\n\n- [x]: https://x.dev\n", first)
        assertEquals(
            MarkdownSpan("x", link = "https://x.dev"),
            (second.blocks.blocks[0] as MarkdownBlock.Paragraph).spans[1],
        )
        // the quote held only the definition, so it shows nothing
        assertEquals(1, blocks("> [x]: https://x.dev\n\n[x]").size)
    }
}
