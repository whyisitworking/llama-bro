package com.suhel.llamabro.sdk.chat.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AllocationOptimizedScanner].
 *
 * Covers pure-text pass-through, complete tag recognition, tag content extraction,
 * and robustness against tags split across multiple token boundaries.
 */
class AllocationOptimizedScannerTest {

    private val thinkDelimiter = TagDelimiter(open = "<think>", close = "</think>")
    private val toolDelimiter = TagDelimiter(open = "<tool_call>", close = "</tool_call>")

    /** Collects callback emissions into a list for test assertions. */
    private fun AllocationOptimizedScanner.feedCollect(token: String?): List<LexerEvent> {
        val events = mutableListOf<LexerEvent>()
        feed(token) { events += it }
        return events
    }

    // -- Pure text (no delimiters configured) ------------------------------

    @Test
    fun `plain text with no delimiters emits single Text event`() {
        val scanner = AllocationOptimizedScanner(emptyList())
        val events = scanner.feedCollect("Hello world")
        assertEquals(listOf(LexerEvent.Text("Hello world")), events)
    }

    @Test
    fun `empty token produces no events`() {
        val scanner = AllocationOptimizedScanner(emptyList())
        assertTrue(scanner.feedCollect("").isEmpty())
    }

    @Test
    fun `null token on empty buffer produces no events`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        assertTrue(scanner.feedCollect(null).isEmpty())
    }

    // -- Full tag in a single token ----------------------------------------

    @Test
    fun `full opening tag is emitted as TagOpened with no preceding text`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        val events = scanner.feedCollect("<think>")
        assertEquals(listOf(LexerEvent.TagOpened(thinkDelimiter)), events)
    }

    @Test
    fun `text before opening tag is flushed before TagOpened`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        val events = scanner.feedCollect("preamble<think>")
        assertEquals(
            listOf(
                LexerEvent.Text("preamble"),
                LexerEvent.TagOpened(thinkDelimiter),
            ),
            events
        )
    }

    @Test
    fun `content inside tag is emitted as TagContent`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        scanner.feedCollect("<think>")
        val events = scanner.feedCollect("deep thought")
        assertEquals(listOf(LexerEvent.TagContent(thinkDelimiter, "deep thought")), events)
    }

    @Test
    fun `complete round-trip emits opened, content, closed`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        val all = scanner.feedCollect("<think>") +
                scanner.feedCollect("reasoning") +
                scanner.feedCollect("</think>")
        assertEquals(
            listOf(
                LexerEvent.TagOpened(thinkDelimiter),
                LexerEvent.TagContent(thinkDelimiter, "reasoning"),
                LexerEvent.TagClosed(thinkDelimiter),
            ),
            all
        )
    }

    @Test
    fun `text after closing tag is emitted as Text`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        val all = scanner.feedCollect("<think>r</think>") + scanner.feedCollect("answer")
        assertEquals(LexerEvent.Text("answer"), all.last())
    }

    // -- Tags split across token boundaries --------------------------------

    @Test
    fun `opening tag split across two tokens is correctly recognized`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        val a = scanner.feedCollect("<thi")   // partial — should hold
        val b = scanner.feedCollect("nk>")    // completes the tag
        assertTrue("No events on partial", a.isEmpty())
        assertEquals(listOf(LexerEvent.TagOpened(thinkDelimiter)), b)
    }

    @Test
    fun `closing tag split across two tokens is correctly recognized`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        scanner.feedCollect("<think>")
        scanner.feedCollect("content")
        val a = scanner.feedCollect("</thi")  // partial closing tag
        val b = scanner.feedCollect("nk>")    // completes it
        assertTrue("No events on partial close", a.isEmpty())
        assertTrue("Closed event in second feed", b.any { it is LexerEvent.TagClosed })
    }

    @Test
    fun `tag split into single character tokens is correctly assembled`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        // Feed "<think>" one char at a time
        val events = "<think>".map { scanner.feedCollect(it.toString()) }.flatten()
        assertEquals(listOf(LexerEvent.TagOpened(thinkDelimiter)), events)
    }

    // -- Multiple delimiters -----------------------------------------------

    @Test
    fun `think and tool delimiters are both recognized independently`() {
        val delimiters = listOf(thinkDelimiter, toolDelimiter)
        val scanner = AllocationOptimizedScanner(delimiters)
        val events = scanner.feedCollect("<think>r</think><tool_call>fn</tool_call>")

        assertTrue(events.any { it is LexerEvent.TagOpened && it.delimiter == thinkDelimiter })
        assertTrue(events.any { it is LexerEvent.TagOpened && it.delimiter == toolDelimiter })
        assertTrue(events.any { it is LexerEvent.TagClosed && it.delimiter == thinkDelimiter })
        assertTrue(events.any { it is LexerEvent.TagClosed && it.delimiter == toolDelimiter })
    }

    // -- Flush on stream end -----------------------------------------------

    @Test
    fun `null token with no partial tag in buffer produces no events`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        val textEvents = scanner.feedCollect("Hello")
        assertEquals(listOf(LexerEvent.Text("Hello")), textEvents)

        val endEvents = scanner.feedCollect(null)
        assertTrue("No events expected after null on empty buffer", endEvents.isEmpty())
    }

    @Test
    fun `null token with partial tag in buffer does not speculatively flush it as text`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkDelimiter))
        val partial = scanner.feedCollect("<thi")
        assertTrue("Partial tag should be held, no events yet", partial.isEmpty())

        val endEvents = scanner.feedCollect(null)
        assertTrue("Partial tag must not be speculatively emitted as text", endEvents.isEmpty())
    }
}
