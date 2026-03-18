package com.suhel.llamabro.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStreamParserTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun TokenStreamParser.feed(vararg tokens: String): Pair<String?, String?> {
        val content = StringBuilder()
        val thinking = StringBuilder()
        for (token in tokens) process(token, content, thinking)
        flush(content, thinking)
        return content.ifEmpty { null }?.toString() to thinking.ifEmpty { null }?.toString()
    }

    // ── Basic routing ────────────────────────────────────────────────────────

    @Test
    fun `empty stream produces nulls`() {
        val parser = TokenStreamParser()
        val (content, thinking) = parser.feed()
        assertNull(content)
        assertNull(thinking)
    }

    @Test
    fun `pure content with no tags`() {
        val parser = TokenStreamParser()
        val (content, thinking) = parser.feed("Hello", " world")
        assertEquals("Hello world", content)
        assertNull(thinking)
    }

    @Test
    fun `pure thinking block`() {
        val parser = TokenStreamParser()
        val (content, thinking) = parser.feed("<think>", "reasoning", "</think>")
        assertNull(content)
        assertEquals("reasoning", thinking)
    }

    @Test
    fun `thinking then content`() {
        val parser = TokenStreamParser()
        val (content, thinking) = parser.feed("<think>", "thought", "</think>", "answer")
        assertEquals("answer", content)
        assertEquals("thought", thinking)
    }

    @Test
    fun `content before thinking block`() {
        val parser = TokenStreamParser()
        val (content, thinking) = parser.feed("pre", "<think>", "mid", "</think>", "post")
        assertEquals("prepost", content)
        assertEquals("mid", thinking)
    }

    // ── Tag splitting across token boundaries ────────────────────────────────

    @Test
    fun `open tag split across two tokens`() {
        val parser = TokenStreamParser()
        val (content, thinking) = parser.feed("<thi", "nk>", "thought", "</think>", "answer")
        assertEquals("answer", content)
        assertEquals("thought", thinking)
    }

    @Test
    fun `close tag split across two tokens`() {
        val parser = TokenStreamParser()
        val (content, thinking) = parser.feed("<think>", "thought", "</thi", "nk>", "answer")
        assertEquals("answer", content)
        assertEquals("thought", thinking)
    }

    @Test
    fun `both tags split one character at a time`() {
        val parser = TokenStreamParser()
        val tokens = "<think>thought</think>answer".map { it.toString() }.toTypedArray()
        val (content, thinking) = parser.feed(*tokens)
        assertEquals("answer", content)
        assertEquals("thought", thinking)
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `empty thinking block`() {
        val parser = TokenStreamParser()
        val (content, thinking) = parser.feed("<think>", "</think>", "answer")
        assertEquals("answer", content)
        assertNull(thinking)
    }

    @Test
    fun `multiple thinking blocks are concatenated`() {
        val parser = TokenStreamParser()
        val (content, thinking) = parser.feed(
            "<think>", "first", "</think>",
            "between",
            "<think>", "second", "</think>",
            "end"
        )
        assertEquals("betweenend", content)
        assertEquals("firstsecond", thinking)
    }

    @Test
    fun `stream ends mid open tag — flushed to content`() {
        val parser = TokenStreamParser()
        val content = StringBuilder()
        val thinking = StringBuilder()
        parser.process("<thi", content, thinking)
        // No flush yet — partial tag is buffered
        assertEquals("", content.toString())
        parser.flush(content, thinking)
        // On flush, buffer goes to content
        assertEquals("<thi", content.toString())
    }

    @Test
    fun `stream ends mid close tag — flushed to thinking`() {
        val parser = TokenStreamParser()
        val content = StringBuilder()
        val thinking = StringBuilder()
        parser.process("<think>thought</thi", content, thinking)
        parser.flush(content, thinking)
        assertEquals("thought</thi", thinking.toString())
    }

    // ── reset(startThinking) — pre-filled thinking mode ─────────────────────

    @Test
    fun `reset with startThinking routes immediately to thinking builder`() {
        val parser = TokenStreamParser()
        parser.reset(startThinking = true)
        val (content, thinking) = parser.feed("deep thought", "</think>", "answer")
        assertEquals("answer", content)
        assertEquals("deep thought", thinking)
    }

    @Test
    fun `reset clears prior state`() {
        val parser = TokenStreamParser()
        parser.feed("<think>", "old")
        assertTrue(parser.isThinking)

        parser.reset()
        assertFalse(parser.isThinking)
        assertFalse(parser.isStopped)

        val (content, _) = parser.feed("fresh")
        assertEquals("fresh", content)
    }

    // ── Stop strings ─────────────────────────────────────────────────────────

    @Test
    fun `stop string halts output and is not emitted`() {
        val parser = TokenStreamParser(stopStrings = listOf("[STOP]"))
        val (content, _) = parser.feed("hello ", "[STOP]", "ignored")
        assertEquals("hello ", content)
        assertTrue(parser.isStopped)
    }

    @Test
    fun `stop string split across tokens`() {
        val parser = TokenStreamParser(stopStrings = listOf("[STOP]"))
        val (content, _) = parser.feed("hello ", "[ST", "OP]", "ignored")
        assertEquals("hello ", content)
        assertTrue(parser.isStopped)
    }

    @Test
    fun `content before stop string is preserved`() {
        val parser = TokenStreamParser(stopStrings = listOf("<|im_start|>"))
        val (content, _) = parser.feed("answer text", "<|im_start|>", "user\nnext turn")
        assertEquals("answer text", content)
        assertTrue(parser.isStopped)
    }

    @Test
    fun `stop string before think tag — stop wins`() {
        val parser = TokenStreamParser(stopStrings = listOf("[STOP]"))
        val (content, thinking) = parser.feed("before [STOP]<think>ignored</think>")
        assertEquals("before ", content)
        assertNull(thinking)
        assertTrue(parser.isStopped)
    }

    @Test
    fun `think tag before stop string — think routes normally`() {
        val parser = TokenStreamParser(stopStrings = listOf("[STOP]"))
        val (content, thinking) = parser.feed("<think>thought</think>answer[STOP]extra")
        assertEquals("answer", content)
        assertEquals("thought", thinking)
        assertTrue(parser.isStopped)
    }

    @Test
    fun `isStopped makes subsequent process calls no-ops`() {
        val parser = TokenStreamParser(stopStrings = listOf("[STOP]"))
        val content = StringBuilder()
        val thinking = StringBuilder()
        parser.process("[STOP]", content, thinking)
        parser.process("should not appear", content, thinking)
        assertEquals("", content.toString())
        assertTrue(parser.isStopped)
    }

    @Test
    fun `reset clears isStopped`() {
        val parser = TokenStreamParser(stopStrings = listOf("[STOP]"))
        parser.feed("[STOP]")
        assertTrue(parser.isStopped)
        parser.reset()
        assertFalse(parser.isStopped)
        val (content, _) = parser.feed("fresh")
        assertEquals("fresh", content)
    }

    @Test
    fun `multiple stop strings — earliest wins`() {
        val parser = TokenStreamParser(stopStrings = listOf("[B]", "[A]"))
        val (content, _) = parser.feed("text[A]extra[B]more")
        assertEquals("text", content)
        assertTrue(parser.isStopped)
    }

    @Test
    fun `no stop strings — parser behaves identically to original`() {
        val parser = TokenStreamParser(stopStrings = emptyList())
        val (content, thinking) = parser.feed("<think>", "t", "</think>", "c")
        assertEquals("c", content)
        assertEquals("t", thinking)
        assertFalse(parser.isStopped)
    }

    // ── Custom think tags ────────────────────────────────────────────────────

    @Test
    fun `custom think tags are respected`() {
        val parser = TokenStreamParser(thinkingStart = "<reasoning>", thinkingEnd = "</reasoning>")
        val (content, thinking) = parser.feed("<reasoning>", "thought", "</reasoning>", "answer")
        assertEquals("answer", content)
        assertEquals("thought", thinking)
    }
}
