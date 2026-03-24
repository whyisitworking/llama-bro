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

    private val thinkMarker = ThinkingMarker(open = "<think>", close = "</think>")
    private val toolMarker = ToolCallMarker(open = "<tool_call>", close = "</tool_call>")

    // ── Pure text (no markers configured) ──────────────────────────────────

    @Test
    fun `plain text with no markers emits single Text event`() {
        val scanner = AllocationOptimizedScanner(emptyList())
        val events = scanner.feed("Hello world")
        assertEquals(listOf(LexerEvent.Text("Hello world")), events)
    }

    @Test
    fun `empty token produces no events`() {
        val scanner = AllocationOptimizedScanner(emptyList())
        assertTrue(scanner.feed("").isEmpty())
    }

    @Test
    fun `null token on empty buffer produces no events`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        assertTrue(scanner.feed(null).isEmpty())
    }

    // ── Full tag in a single token ──────────────────────────────────────────

    @Test
    fun `full opening tag is emitted as TagOpened with no preceding text`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        val events = scanner.feed("<think>")
        assertEquals(listOf(LexerEvent.TagOpened(thinkMarker)), events)
    }

    @Test
    fun `text before opening tag is flushed before TagOpened`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        val events = scanner.feed("preamble<think>")
        assertEquals(
            listOf(
                LexerEvent.Text("preamble"),
                LexerEvent.TagOpened(thinkMarker),
            ),
            events
        )
    }

    @Test
    fun `content inside tag is emitted as TagContent`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        scanner.feed("<think>")
        val events = scanner.feed("deep thought")
        assertEquals(listOf(LexerEvent.TagContent(thinkMarker, "deep thought")), events)
    }

    @Test
    fun `complete round-trip emits opened, content, closed`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        val all = scanner.feed("<think>") + scanner.feed("reasoning") + scanner.feed("</think>")
        assertEquals(
            listOf(
                LexerEvent.TagOpened(thinkMarker),
                LexerEvent.TagContent(thinkMarker, "reasoning"),
                LexerEvent.TagClosed(thinkMarker),
            ),
            all
        )
    }

    @Test
    fun `text after closing tag is emitted as Text`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        val all = scanner.feed("<think>r</think>") + scanner.feed("answer")
        assertEquals(LexerEvent.Text("answer"), all.last())
    }

    // ── Tags split across token boundaries ────────────────────────────────────

    @Test
    fun `opening tag split across two tokens is correctly recognized`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        val a = scanner.feed("<thi")   // partial — should hold
        val b = scanner.feed("nk>")    // completes the tag
        assertTrue("No events on partial", a.isEmpty())
        assertEquals(listOf(LexerEvent.TagOpened(thinkMarker)), b)
    }

    @Test
    fun `closing tag split across two tokens is correctly recognized`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        scanner.feed("<think>")
        scanner.feed("content")
        val a = scanner.feed("</thi")  // partial closing tag
        val b = scanner.feed("nk>")    // completes it
        // 'a' may include a TagContent for the partial prefix of the closing tag held back — it should be empty
        assertTrue("No events on partial close", a.isEmpty())
        assertTrue("Closed event in second feed", b.any { it is LexerEvent.TagClosed })
    }

    @Test
    fun `tag split into single character tokens is correctly assembled`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        // Feed "<think>" one char at a time
        val events = "<think>".map { scanner.feed(it.toString()) }.flatten()
        assertEquals(listOf(LexerEvent.TagOpened(thinkMarker)), events)
    }

    // ── Multiple markers ───────────────────────────────────────────────────

    @Test
    fun `think and tool markers are both recognized independently`() {
        val markers = listOf(thinkMarker, toolMarker)
        val scanner = AllocationOptimizedScanner(markers)
        val events = scanner.feed("<think>r</think><tool_call>fn</tool_call>")

        assertTrue(events.any { it is LexerEvent.TagOpened && it.marker == thinkMarker })
        assertTrue(events.any { it is LexerEvent.TagOpened && it.marker == toolMarker })
        assertTrue(events.any { it is LexerEvent.TagClosed && it.marker == thinkMarker })
        assertTrue(events.any { it is LexerEvent.TagClosed && it.marker == toolMarker })
    }

    // ── Flush on stream end ───────────────────────────────────────────────────

    @Test
    fun `null token with no partial tag in buffer produces no events`() {
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        // Feed a complete text token — it is immediately flushed
        val textEvents = scanner.feed("Hello")
        assertEquals(listOf(LexerEvent.Text("Hello")), textEvents)

        // Feeding null after a fully-flushed buffer should produce nothing
        val endEvents = scanner.feed(null)
        assertTrue("No events expected after null on empty buffer", endEvents.isEmpty())
    }

    @Test
    fun `null token with partial tag in buffer does not speculatively flush it as text`() {
        // The scanner is conservative: if a buffer tail could still be the start of a registered
        // tag, it will NOT emit it speculatively. The LLM stream is expected to disambiguate it
        // by emitting more tokens. Null does not break this invariant.
        val scanner = AllocationOptimizedScanner(listOf(thinkMarker))
        // "<thi" is a valid prefix of "<think>" — scanner holds it
        val partial = scanner.feed("<thi")
        assertTrue("Partial tag should be held, no events yet", partial.isEmpty())

        // Null: scanner runs its loop but still finds a partial match — it continues to hold.
        val endEvents = scanner.feed(null)
        assertTrue("Partial tag must not be speculatively emitted as text", endEvents.isEmpty())
    }
}
