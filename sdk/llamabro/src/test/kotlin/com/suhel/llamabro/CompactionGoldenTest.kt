package com.suhel.llamabro

import com.suhel.llamabro.chat.Content
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.Compaction
import com.suhel.llamabro.conversation.CompactionPlanner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Differential test against llama.cpp's own tools/ui code: 400 seeded random message lists, each run through
 * upstream's compaction.ts by sdk/scripts/tools-ui/gen-compaction-golden.mjs (node, no build), replayed here
 * through the Kotlin port. Regenerate the golden after a llama.cpp upgrade (sdk/scripts/check.sh does).
 */
class CompactionGoldenTest {
    private data class Row(val type: String, val role: String, val content: String, val kept: Int)

    private fun str(o: JsonObject, k: String) = (o[k] as JsonPrimitive).content

    private fun toMessage(o: JsonObject): Message = when (str(o, "type")) {
        "system" -> Message.System(str(o, "content"))
        "compaction" -> Message.Compaction(str(o, "content"), (o["kept"] as? JsonPrimitive)?.int ?: 0)
        else -> when (str(o, "role")) {
            "user" -> Message.user(str(o, "content"))
            "assistant" -> Message.Assistant(str(o, "content"))
            "tool" -> Message.ToolResult(str(o, "id"), str(o, "content"))
            else -> error("unexpected role in golden: $o")
        }
    }

    /** A Kotlin message in the golden's terms (tools/ui keeps the framed node's compaction type and user role). */
    private fun row(m: Message): Row = when (m) {
        is Message.System -> Row("system", "system", m.text, 0)
        is Message.User -> Row("text", "user", (m.content.single() as Content.Text).text, 0)
        is Message.Assistant -> Row("text", "assistant", m.content!!, 0)
        is Message.ToolResult -> Row("text", "tool", m.content, 0)
        is Message.Compaction -> Row("compaction", "user", m.summary, m.keptSteps)
    }

    @Test
    fun everyGoldenCaseMatchesUpstream() {
        val golden = Json.parseToJsonElement(javaClass.classLoader!!.getResource("tools-ui/compaction-golden.json")!!.readText()) as JsonObject
        assertEquals(Compaction.CONTEXT_TEMPLATE, str(golden, "contextTemplate"))
        val cases = golden["cases"] as JsonArray
        assertEquals(400, cases.size)
        cases.forEachIndexed { i, c ->
            c as JsonObject
            val messages = (c["messages"] as JsonArray).map { toMessage(it as JsonObject) }
            val preserve = c["preserve"]!!.jsonPrimitive.int
            val view = CompactionPlanner.modelView(messages)
            if (c["viewIsInput"]!!.jsonPrimitive.boolean) assertSame("case $i: view", messages, view)
            val expected = (c["view"] as JsonArray).map {
                it as JsonObject
                Row(str(it, "type"), str(it, "role"), str(it, "content"), (it["kept"] as? JsonPrimitive)?.int ?: 0)
            }
            assertEquals("case $i: view", expected, view.map { row(it) })
            val cut = c["cut"].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.int }
            assertEquals("case $i: cut", cut, CompactionPlanner.cutIndex(view, preserve))
            assertEquals("case $i: canCompact", c["canCompact"]!!.jsonPrimitive.boolean, CompactionPlanner.canCompact(messages, preserve))
        }
    }
}
