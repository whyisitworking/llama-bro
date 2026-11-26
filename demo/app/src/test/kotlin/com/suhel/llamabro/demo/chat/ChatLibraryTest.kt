package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.Source
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.letAWrongActionLand
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.demo.ui.chat.Passages
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.rag.Hit
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * A chat that answers from the person's library (docs/design/rag.md): the search tool on a model that calls tools,
 * the passages in the turn on one that doesn't, and the passages a reply used saved with it as its sources.
 */
class ChatLibraryTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")

    private val fake = FakeLlamaBro()
    private val testHub = TestHub()
    private val dao = FakeChatDao()
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    private val images by lazy { ImageStore(File(folder.root, "images")) }
    private val host by lazy { ModelHost(fake.llamaBro, { testHub.hub.installedModel(it) }, scope) }
    private val models by lazy { ModelRepository(testHub.hub, settings, FakeScheduler(), scope, host) }

    private class FakeLibrary : ChatLibrary {
        val documents = MutableStateFlow(listOf("llamas.md"))
        val texts = mutableMapOf<String, String>()
        var hits: List<Hit> = emptyList()
        val queries: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override val hasDocuments = MutableStateFlow(true)

        override suspend fun search(query: String): List<Hit> {
            queries += query
            return hits
        }

        override suspend fun documentText(documentId: String): String? = texts[documentId]
    }

    private val library = FakeLibrary()

    private val llamaText = "# Llamas\n\n## Sounds\n\nLlamas hum when they are curious. They also click.\n\nThey spit."
    private val hum = hit(1, "Llamas hum when they are curious.")
    private val spit = hit(2, "They spit.")

    private fun hit(rank: Int, text: String): Hit {
        val start = llamaText.indexOf(text)
        return Hit("llamas.md", "Llamas", text, start, start + text.length, listOf("Llamas", "Sounds"), 0.03f, rank)
    }

    private fun engine(tools: Boolean, chats: ChatRepository = repository()) =
        ChatEngine(
            chats,
            host,
            models,
            settings,
            System::currentTimeMillis,
            scope,
            chatLibrary = library,
            callsTools = { _: ModelInfo -> tools },
        )

    private fun repository() = ChatRepository(dao, images, System::currentTimeMillis)

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private suspend fun newChat(engine: ChatEngine): ChatEngine.ChatHandle {
        settings.setLastModel(swift.toString())
        testHub.install(swift)
        models.changed()
        return engine.open(0)
    }

    private suspend fun answered(chat: ChatEngine.ChatHandle): StoredMessage {
        eventually("the reply is done") {
            chat.messages.value.any { it.kind == MessageKind.Assistant && it.state == MessageState.Done }
        }
        return chat.messages.value.last { it.kind == MessageKind.Assistant }
    }

    private fun request(n: Int): JsonObject = Json.parseToJsonElement(fake.chatRequests[n]).jsonObject

    private fun JsonObject.messages() = getValue("messages").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(): String = getValue("content").let { it.jsonPrimitive.content }

    private fun source(number: Int, hit: Hit) =
        Source(number, hit.documentId, hit.title, hit.start, hit.end, hit.headings, hit.text)

    @Test fun toolModelGetsTheTool() =
        runBlocking {
            library.hits = listOf(hum, spit)
            fake.reply { toolCall("search_library", """{"query":"why llamas hum"}""") }
            fake.reply { content("Out of curiosity [1].") }
            val chat = newChat(engine(tools = true))
            eventually("the toggle is offered") { chat.useLibrary.value == false }
            chat.setUseLibrary(true)
            eventually("the toggle is on") { chat.useLibrary.value == true }
            chat.send("Why do llamas hum?")
            val reply = answered(chat)

            val tools = request(0).getValue("tools").jsonArray.map { it.jsonObject }
            assertEquals(
                listOf("search_library"),
                tools.map {
                    it
                        .getValue("function")
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive.content
                },
            )
            assertEquals(
                "the person's words, as they wrote them",
                "Why do llamas hum?",
                request(0).messages().last().text(),
            )
            assertEquals(listOf("why llamas hum"), library.queries)
            val result = request(1).messages().last { it.getValue("role").jsonPrimitive.content == "tool" }.text()
            assertTrue(result, result.startsWith("[1] Llamas › Llamas › Sounds\nLlamas hum when they are curious."))
            assertEquals(listOf(source(1, hum), source(2, spit)), reply.sources)
            assertEquals("the choice was saved with the new chat", true, dao.chat(chat.chatId.value)?.useLibrary)
        }

    @Test fun noToolModelGetsPassagesInTheTurn() =
        runBlocking {
            library.hits = listOf(hum, spit)
            fake.reply { content("Out of curiosity [1].") }
            val chat = newChat(engine(tools = false))
            eventually("the toggle is offered") { chat.useLibrary.value == false }
            chat.setUseLibrary(true)
            chat.send("Why do llamas hum?")
            val reply = answered(chat)

            assertFalse("no tools", request(0).containsKey("tools"))
            assertEquals(listOf("Why do llamas hum?"), library.queries)
            val messages = request(0).messages()
            val system = messages.first { it.getValue("role").jsonPrimitive.content == "system" }.text()
            assertTrue(system, "[1] Llamas › Llamas › Sounds\nLlamas hum when they are curious." in system)
            assertTrue(system, "[2] Llamas › Llamas › Sounds\nThey spit." in system)
            assertEquals("the passages stay out of the person's message", "Why do llamas hum?", messages.last().text())
            assertEquals(listOf(source(1, hum), source(2, spit)), reply.sources)
        }

    @Test fun toggleOffUsesNothing() =
        runBlocking {
            library.hits = listOf(hum)
            fake.reply { content("Hello.") }
            val chat = newChat(engine(tools = true))
            eventually("the toggle is offered, off") { chat.useLibrary.value == false }
            chat.send("Why do llamas hum?")
            val reply = answered(chat)
            assertFalse(request(0).containsKey("tools"))
            assertTrue(request(0).messages().none { it.getValue("role").jsonPrimitive.content == "system" })
            assertEquals(emptyList<String>(), library.queries)
            assertEquals(emptyList<Source>(), reply.sources)

            // a library with no document is not offered at all, and a chat that had it on uses nothing
            chat.setUseLibrary(true)
            eventually("on") { chat.useLibrary.value == true }
            library.hasDocuments.value = false
            eventually("not offered") { chat.useLibrary.value == null }
            fake.reply { content("Again.") }
            chat.send("And now?")
            eventually("two replies") {
                chat.messages.value.count { it.kind == MessageKind.Assistant && it.state == MessageState.Done } == 2
            }
            letAWrongActionLand()
            assertEquals(emptyList<String>(), library.queries)
            assertFalse(request(1).containsKey("tools"))
        }

    @Test fun chipsListTheHitsUsed() {
        val sources = listOf(source(1, hum), source(2, spit))
        assertEquals("the ones cited", listOf(sources[1]), Passages.cited("They spit [2].", sources))
        assertEquals("[n] cited twice is one chip", sources, Passages.cited("[2] and [1], then [2] again.", sources))
        assertEquals(
            "a number with no passage is left out",
            listOf(sources[0]),
            Passages.cited("See [1] and [7].", sources),
        )
        assertEquals("none cited: every passage the reply read", sources, Passages.cited("They hum.", sources))
    }

    @Test fun chipsSurviveRestart() =
        runBlocking {
            library.hits = listOf(hum)
            fake.reply { content("Out of curiosity [1].") }
            val first = newChat(engine(tools = false))
            eventually("offered") { first.useLibrary.value == false }
            first.setUseLibrary(true)
            first.send("Why do llamas hum?")
            answered(first)
            val id = first.chatId.value
            first.close()

            // the app starts again, the document is gone from the library: the reply keeps its source
            library.hasDocuments.value = false
            val again = engine(tools = false, chats = repository()).open(id)
            eventually("the chat is read") { again.messages.value.isNotEmpty() }
            assertEquals(
                listOf(source(1, hum)),
                again.messages.value
                    .last()
                    .sources,
            )
            assertEquals("the chat still has it on", true, repository().useLibrary(id).first())
        }

    @Test fun passageSheetHighlightsTheSpan() {
        val view = Passages.view(source(1, hum), llamaText)
        assertEquals("Llamas", view.title)
        assertEquals("Llamas › Sounds", view.path)
        assertEquals(hum.text, view.passage)
        assertTrue(view.before, view.before.endsWith("## Sounds\n\n"))
        assertEquals(" They also click.\n\nThey spit.", view.after)
        assertEquals(
            "before, passage and after are the document's text",
            llamaText,
            view.before + view.passage + view.after,
        )

        // the document is gone, or was replaced and no longer holds the passage where it was: the passage alone
        val gone = Passages.view(source(1, hum), null)
        assertEquals(hum.text, gone.passage)
        assertEquals("", gone.before + gone.after)
        val changed = Passages.view(source(1, hum), "Something else entirely, now longer than it was before.")
        assertEquals(hum.text, changed.passage)
        assertEquals("", changed.before + changed.after)
        assertNull(Passages.view(source(1, hum.copy(headings = emptyList())), null).path)
    }
}
