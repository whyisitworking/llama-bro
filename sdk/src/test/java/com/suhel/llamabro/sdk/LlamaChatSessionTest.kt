package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.model.Message
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaChatSessionTest {

    // ── Test double ─────────────────────────────────────────────────────────

    /** A fake [LlamaSession] that returns a pre-configured token sequence. */
    private class FakeSession(private val tokens: List<String>) : LlamaSession {
        private var index = 0
        var promptedWith: Message? = null
        var cleared = false

        override suspend fun prompt(message: Message) {
            promptedWith = message
        }

        override suspend fun generate(): String? {
            return if (index < tokens.size) tokens[index++] else null
        }

        override fun clear() {
            cleared = true
            index = 0
        }

        override fun abort() {}

        override fun close() {}
    }

    // ── Token-per-second ────────────────────────────────────────────────────

    @Test
    fun `tokensPerSecond is non-zero for non-empty generation`() = runTest {
        val session = LlamaChatSession(FakeSession(listOf("Hello", " world")))
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertTrue("isComplete should be true", last.isComplete)
        assertNotNull("tokensPerSecond should not be null", last.tokensPerSecond)
        assertTrue("tokensPerSecond should be > 0, was ${last.tokensPerSecond}", last.tokensPerSecond!! > 0f)
    }

    @Test
    fun `tokensPerSecond is zero for empty generation`() = runTest {
        val session = LlamaChatSession(FakeSession(emptyList()))
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertTrue(last.isComplete)
    }

    // ── Content accumulation ────────────────────────────────────────────────

    @Test
    fun `content text accumulates across tokens`() = runTest {
        val session = LlamaChatSession(FakeSession(listOf("Hello", " ", "world")))
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("Hello world", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `scan produces progressive snapshots`() = runTest {
        val session = LlamaChatSession(FakeSession(listOf("A", "B")))
        val generations = session.chat("Hi").toList()

        // First emission is the initial ChatGeneration() from scan seed
        assertTrue(generations.size >= 3) // seed + A + B + metric
        assertEquals("A", generations[1].contentText)
        assertEquals("AB", generations[2].contentText)
    }

    // ── Thinking tag classification ─────────────────────────────────────────

    @Test
    fun `thinking tags separate thinking from content`() = runTest {
        val tokens = listOf("<think>", "reasoning", "</think>", "answer")
        val session = LlamaChatSession(FakeSession(tokens))
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("reasoning", last.thinkingText)
        assertEquals("answer", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `thinking tags split across token boundaries`() = runTest {
        // "<think>" split across two tokens: "<thi" + "nk>"
        val tokens = listOf("<thi", "nk>", "deep thought", "</thi", "nk>", "visible")
        val session = LlamaChatSession(FakeSession(tokens))
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("deep thought", last.thinkingText)
        assertEquals("visible", last.contentText)
        assertTrue(last.isComplete)
    }

    @Test
    fun `content before think tag is emitted as content`() = runTest {
        val tokens = listOf("preamble", "<think>", "thought", "</think>", "answer")
        val session = LlamaChatSession(FakeSession(tokens))
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertEquals("thought", last.thinkingText)
        assertEquals("preambleanswer", last.contentText)
    }

    @Test
    fun `no thinking tags means all content`() = runTest {
        val tokens = listOf("just", " content")
        val session = LlamaChatSession(FakeSession(tokens))
        val generations = session.chat("Hi").toList()
        val last = generations.last()

        assertNull(last.thinkingText)
        assertEquals("just content", last.contentText)
    }

    // ── couldContainPartialTag ──────────────────────────────────────────────

    @Test
    fun `couldContainPartialTag detects partial open tag`() {
        assertTrue(LlamaChatSession.couldContainPartialTag("abc<"))
        assertTrue(LlamaChatSession.couldContainPartialTag("abc<th"))
        assertTrue(LlamaChatSession.couldContainPartialTag("<thin"))
        assertTrue(LlamaChatSession.couldContainPartialTag("<think"))
    }

    @Test
    fun `couldContainPartialTag detects partial close tag`() {
        assertTrue(LlamaChatSession.couldContainPartialTag("abc</"))
        assertTrue(LlamaChatSession.couldContainPartialTag("abc</thi"))
    }

    @Test
    fun `couldContainPartialTag returns false for non-tag text`() {
        assertTrue(!LlamaChatSession.couldContainPartialTag("hello"))
        assertTrue(!LlamaChatSession.couldContainPartialTag(""))
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    @Test
    fun `reset delegates to session clear`() {
        val fake = FakeSession(emptyList())
        val session = LlamaChatSession(fake)
        session.reset()
        assertTrue(fake.cleared)
    }
}
