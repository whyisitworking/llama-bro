package com.suhel.llamabro.rag

import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.conversation
import com.suhel.llamabro.testing.FakeLlamaBro
import com.suhel.llamabro.typed.TypedTool
import com.suhel.llamabro.typed.tool
import com.suhel.llamabro.typed.typedSchema
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryToolTest {
    private val fake = FakeLlamaBro()
    private val chunker = Chunker(maxTokens = 8, overlap = 0f)

    private fun unit(i: Int): FloatArray = FloatArray(4) { if (it == i) 1f else 0f }

    private suspend fun library(): Library {
        val embedder = fake.llamaBro.loadModel(ModelSource.file("/e.gguf")) { task = ModelTask.Embedding() }
        fake.embeddings(unit(0))
        return Library.open(FakeFileSystem(), "/library".toPath(), embedder, EmbeddingFormat.None, chunker)
    }

    private suspend fun Library.addHandbook() {
        fake.embeddings(unit(0), unit(1), unit(2))
        add(Document("hb", "Handbook", "# Handbook\n\n## Leave\n\nTwenty days a year.")).toList()
    }

    private suspend fun chat(searchTool: TypedTool<SearchArgs, String>) =
        fake.llamaBro.loadModel(ModelSource.file("/chat.gguf")).conversation { tool(searchTool) }

    private fun toolResultSent(request: Int): String =
        Json
            .parseToJsonElement(fake.chatRequests[request])
            .jsonObject["messages"]!!
            .jsonArray
            .last { it.jsonObject["role"]!!.jsonPrimitive.content == "tool" }
            .jsonObject["content"]!!
            .jsonPrimitive.content

    private fun hit(n: Int, title: String, text: String, vararg headings: String) =
        Hit("d$n", title, text, 0, text.length, headings.toList(), 0f, n)

    @Test
    fun toolSearchesAndNumbersPassages() =
        runBlocking {
            val library = library()
            library.addHandbook()
            fake.reply { toolCall("search_library", """{"query":"twenty days"}""", id = "c1") }
            fake.reply { content("Twenty days [1].") }
            fake.embeddings(unit(2))
            val events = chat(library.searchTool(limit = 1)).send("How much leave?").toList()
            val result = events.filterIsInstance<ChatEvent.ToolResult>().single()
            assertEquals("[1] Handbook › Handbook › Leave\nTwenty days a year.", result.result)
            assertEquals(result.result, toolResultSent(1))
        }

    @Test
    fun emptyResultSaysNothingFound() =
        runBlocking {
            val library = library()
            fake.reply { toolCall("search_library", """{"query":"anything"}""", id = "c1") }
            fake.reply { content("Nothing.") }
            val events = chat(library.searchTool()).send("Hm?").toList()
            val result = events.filterIsInstance<ChatEvent.ToolResult>().single()
            assertEquals("No passages in the library match this query.", result.result)
            assertEquals("No passages in the library match this query.", emptyList<Hit>().asContext())
        }

    @Test
    fun blankQueryReturnsTheNoPassagesText() =
        runBlocking {
            val library = library()
            library.addHandbook()
            fake.reply { toolCall("search_library", """{"query":"   "}""", id = "c1") }
            fake.reply { content("ok") }
            val events = chat(library.searchTool()).send("?").toList()
            assertEquals(
                "No passages in the library match this query.",
                events.filterIsInstance<ChatEvent.ToolResult>().single().result,
            )
        }

    @Test
    fun asContextMatchesToolOutput() {
        val hits =
            listOf(
                hit(1, "Handbook", "Twenty days.", "Leave", "Annual"),
                hit(2, "Notes", "Door code 4417."),
            )
        assertEquals(
            "[1] Handbook › Leave › Annual\nTwenty days.\n\n[2] Notes\nDoor code 4417.",
            hits.asContext(),
        )
    }

    @Test
    fun aClosedLibraryBecomesASafeToolError() =
        runBlocking {
            val library = library()
            library.addHandbook()
            library.close()
            fake.reply { toolCall("search_library", """{"query":"leave"}""", id = "c1") }
            fake.reply { content("Sorry.") }
            val events = chat(library.searchTool()).send("?").toList()
            val result = events.filterIsInstance<ChatEvent.ToolResult>().single()
            val error = result.error
            assertTrue(error is IllegalStateException) // the cause reaches the app, not the model
            assertEquals("Error: the library is not available", result.result)
            assertFalse(result.result.contains("/library"))
        }

    @Test
    fun toolNameAndSchema() =
        runBlocking {
            val library = library()
            val tool = library.searchTool(name = "find_notes")
            assertEquals("find_notes", tool.name)
            assertEquals(2, tool.description.split(". ").size)
            val schema = Json.parseToJsonElement(typedSchema(tool.argsSerializer, tool.json)).jsonObject
            val properties = schema["properties"]!!.jsonObject
            assertEquals(setOf("query"), properties.keys)
            assertEquals("string", properties["query"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(listOf("query"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
}
