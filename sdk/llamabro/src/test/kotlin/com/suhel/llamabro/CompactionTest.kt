package com.suhel.llamabro

import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.Compaction
import com.suhel.llamabro.conversation.CompactionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases ported one for one from llama.cpp b12052 tools/ui/tests/unit/compaction.test.ts (same inputs and
 * expectations). Messages there carry ids; here each message has distinct content, so lists compare by value.
 * Not ported: the five compactionTokensBefore cases, which read per-message timings our Message types don't
 * carry (findings F26).
 */
class CompactionTest {
    // makeMessage / makeCompactionNode equivalents
    private fun user(text: String) = Message.user(text)
    private fun assistant(text: String) = Message.Assistant(text)
    private fun system(text: String) = Message.System(text)
    private fun tool(text: String) = Message.ToolResult("call-$text", text)
    private fun node(summary: String, kept: Int = 0) = Message.Compaction(summary, kept)
    // the framed copy sliceAtLastCompaction returns: a compaction node holding the framed text, sent as a user message
    private fun framed(summary: String, kept: Int = 0) = Message.Compaction(Compaction.CONTEXT_TEMPLATE.replace(Compaction.SUMMARY_PLACEHOLDER, summary), kept)
    private fun text(m: Message) = ((m as Message.User).content.single() as Content.Text).text

    // --- sliceAtLastCompaction ---

    @Test
    fun `returns the list unchanged when no compaction node is present`() {
        val messages = listOf(user("hi"), assistant("hello"))
        assertSame(messages, CompactionPlanner.modelView(messages))
    }

    @Test
    fun `drops messages before the boundary and keeps the system prefix`() {
        val s1 = system("be brief")
        val messages = listOf(s1, user("old question"), assistant("old answer"), node("summary"), user("new question"))
        val sliced = CompactionPlanner.modelView(messages)
        assertEquals(listOf(s1, framed("summary"), user("new question")), sliced)
        assertSame(s1, sliced[0])
    }

    @Test
    fun `frames the compaction node content without mutating the original`() {
        val n = node("summary text")
        val sliced = CompactionPlanner.modelView(listOf(user("question"), n))
        val sent = CompactionPlanner.forRequest(sliced)[0]
        assertEquals(Compaction.CONTEXT_TEMPLATE.replace(Compaction.SUMMARY_PLACEHOLDER, "summary text"), text(sent))
        assertTrue(sent is Message.User)  // role user, as the node's in tools/ui
        assertEquals("summary text", n.summary)
    }

    @Test
    fun `frames a summary as written, whatever it holds`() {
        val summary = "echo \$HOME \$& \$\$ \$'"
        val sliced = CompactionPlanner.modelView(listOf(node(summary)))
        val (before, after) = Compaction.CONTEXT_TEMPLATE.split(Compaction.SUMMARY_PLACEHOLDER)
        assertEquals(before + summary + after, text(CompactionPlanner.forRequest(sliced)[0]))
    }

    @Test
    fun `slices at the last boundary when the branch was compacted twice`() {
        val messages = listOf(node("first summary"), user("question"), assistant("answer"), node("second summary"), user("follow-up"))
        assertEquals(listOf(framed("second summary"), user("follow-up")), CompactionPlanner.modelView(messages))
    }

    @Test
    fun `ignores an empty node left by an interrupted compaction`() {
        val messages = listOf(user("question"), assistant("answer"), node(""))
        assertSame(messages, CompactionPlanner.modelView(messages))
    }

    // --- sliceAtLastCompaction with kept steps ---

    @Test
    fun `keeps the last steps before the node, a tool result with its call`() {
        val messages = listOf(
            system("be brief"), user("first"), assistant("calling"), tool("result"), user("second"), assistant("answer"),
            node("summary", 3), user("next"),
        )
        assertEquals(
            listOf(system("be brief"), framed("summary", 3), assistant("calling"), tool("result"), user("second"), assistant("answer"), user("next")),
            CompactionPlanner.modelView(messages),
        )
    }

    @Test
    fun `counts the kept steps across an older node, which it drops`() {
        val messages = listOf(
            user("first"), assistant("one"), node("first summary", 1), user("second"), assistant("two"),
            node("second summary", 3), user("next"),
        )
        assertEquals(
            listOf(framed("second summary", 3), assistant("one"), user("second"), assistant("two"), user("next")),
            CompactionPlanner.modelView(messages),
        )
    }

    @Test
    fun `keeps every step when it keeps more than there are, and none without kept`() {
        val messages = listOf(user("first"), assistant("one"), node("summary", 9))
        assertEquals(listOf(framed("summary", 9), user("first"), assistant("one")), CompactionPlanner.modelView(messages))
        assertEquals(listOf(framed("s")), CompactionPlanner.modelView(messages.take(2) + node("s")))
    }

    // --- compactionCut ---

    private val view = listOf(system("be brief"), user("first"), assistant("calling"), tool("result"), user("second"), assistant("answer"))

    @Test
    fun `cuts before the preserved steps, never between a call and its result`() {
        assertEquals(4, CompactionPlanner.cutIndex(view, 2))
        assertEquals(2, CompactionPlanner.cutIndex(view, 3))
        assertEquals(view.size, CompactionPlanner.cutIndex(view, 0))
    }

    @Test
    fun `cuts nowhere when no step lies before the preserved ones`() {
        assertNull(CompactionPlanner.cutIndex(view, 4))
        assertNull(CompactionPlanner.cutIndex(view, 5))
    }

    @Test
    fun `leaves the preserved steps as the model reads them once compacted`() {
        val cut = CompactionPlanner.cutIndex(view, 2)!!
        val compacted = CompactionPlanner.modelView(view + node("summary", 2))
        assertEquals(view.drop(cut), compacted.drop(2))
        assertEquals(listOf(system("be brief"), framed("summary", 2)), compacted.take(2))
    }

    // --- canCompactMessages ---

    @Test
    fun `rejects a branch with no step before the preserved ones`() {
        assertFalse(CompactionPlanner.canCompact(listOf(user("hi")), 1))
    }

    @Test
    fun `accepts a branch with a step before the preserved ones`() {
        val messages = listOf(user("hi"), assistant("hello"))
        assertTrue(CompactionPlanner.canCompact(messages, 1))
        assertFalse(CompactionPlanner.canCompact(messages, 2))
    }

    @Test
    fun `rejects a branch with nothing new since the last compaction`() {
        assertFalse(CompactionPlanner.canCompact(listOf(user("hi"), assistant("hello"), node("summary", 1)), 1))
    }

    @Test
    fun `accepts a compacted branch once a new step exists past the preserved ones`() {
        assertTrue(CompactionPlanner.canCompact(listOf(node("summary"), user("next"), assistant("reply")), 1))
    }

    @Test
    fun `accepts a branch whose trailing compaction node is empty`() {
        assertTrue(CompactionPlanner.canCompact(listOf(user("hi"), assistant("hello"), node("")), 1))
    }

    // --- steps (Kotlin-side helper the cut is built from) ---

    @Test
    fun stepsGroupAssistantWithToolResults() {
        assertEquals(listOf(1..1, 2..3, 4..4, 5..5), CompactionPlanner.steps(view))
    }
}
