package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.designsystem.components.MarkdownBlock
import com.suhel.llamabro.demo.designsystem.components.MarkdownSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun blocks(text: String) = Markdown.parse(text).blocks

    @Test fun paragraphs() {
        val b = blocks("First line\nsame paragraph\n\nSecond")
        assertEquals(2, b.size)
        assertEquals(listOf(MarkdownSpan("First line same paragraph")), (b[0] as MarkdownBlock.Paragraph).spans)
    }

    @Test fun headings() {
        val b = blocks("# One\n## Two\n### Three")
        assertEquals(listOf(1, 2, 3), b.map { (it as MarkdownBlock.Heading).level })
    }

    @Test fun inlineMarks() {
        val spans = (blocks("a **bold** and *it* and `code` end")[0] as MarkdownBlock.Paragraph).spans
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
            spans,
        )
    }

    @Test fun links() {
        val spans = (blocks("see [the docs](https://x.dev/a)")[0] as MarkdownBlock.Paragraph).spans
        assertEquals(MarkdownSpan("the docs", link = "https://x.dev/a"), spans[1])
    }

    @Test fun fencedCode() {
        val c = blocks("```kotlin\nval x = 1\nval y = 2\n```\nafter")
        assertEquals(MarkdownBlock.Code("kotlin", "val x = 1\nval y = 2"), c[0])
        assertTrue(c[1] is MarkdownBlock.Paragraph)
    }

    @Test fun unclosedFenceRendersAsCode() {
        assertEquals(MarkdownBlock.Code(null, "val x ="), blocks("```\nval x =")[0])
    }

    @Test fun lists() {
        val b = blocks("- one\n* two\n1. first\n2. second")
        assertEquals(listOf(false, false, true, true), b.map { (it as MarkdownBlock.ListItem).ordered })
        assertEquals(listOf(0, 0, 1, 2), b.map { (it as MarkdownBlock.ListItem).index })
    }

    @Test fun quotes() {
        assertEquals(MarkdownBlock.Quote(listOf(MarkdownSpan("wise words"))), blocks("> wise words")[0])
    }

    @Test fun plainTextIsUntouched() {
        assertEquals(
            listOf(MarkdownSpan("2 * 3 = 6 and a_b")),
            (blocks("2 * 3 = 6 and a_b")[0] as MarkdownBlock.Paragraph).spans,
        )
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
}
