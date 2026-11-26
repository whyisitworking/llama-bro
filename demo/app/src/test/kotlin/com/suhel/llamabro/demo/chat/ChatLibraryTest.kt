package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.ModelTask
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
import com.suhel.llamabro.demo.ui.chat.ChatItems
import com.suhel.llamabro.demo.ui.chat.Markdown
import com.suhel.llamabro.demo.ui.chat.MessageStyle
import com.suhel.llamabro.demo.ui.chat.Passages
import com.suhel.llamabro.demo.ui.chat.UserItem
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.rag.Chunker
import com.suhel.llamabro.rag.Document
import com.suhel.llamabro.rag.EmbeddingFormat
import com.suhel.llamabro.rag.Hit
import com.suhel.llamabro.rag.Library
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    private val llamaText = "Llamas hum softly.\n\nThey spit at foes."
    private val hum = hit(1, "Llamas hum softly.")
    private val spit = hit(2, "They spit at foes.")

    private fun hit(rank: Int, text: String): Hit {
        val start = llamaText.indexOf(text)
        return Hit("llamas.md", "Llamas", text, start, start + text.length, emptyList(), 0f, rank)
    }

    private fun unit(i: Int): FloatArray = FloatArray(4) { if (it == i) 1f else 0f }

    /**
     * A real library over the fake engine's embedder, holding [llamaText] as two passages: the hum passage's vector is
     * the one every query is embedded to, so it comes first. [uses] counts each time a chat opened it.
     */
    private inner class TestLibrary : ChatLibrary {
        private var open: Library? = null
        var uses = 0

        override val hasDocuments = MutableStateFlow(true)

        override suspend fun <T> use(block: suspend (Library) -> T): T {
            uses++
            val library =
                open ?: run {
                    val embedder = fake.llamaBro.loadModel(ModelSource.file("/e.gguf")) { task = ModelTask.Embedding() }
                    fake.embeddings(unit(3)) // the library's probe
                    val opened =
                        Library.open(FakeFileSystem(), "/library".toPath(), embedder, EmbeddingFormat.None, chunker)
                    fake.embeddings(unit(0), unit(1))
                    opened.add(Document("llamas.md", "Llamas", llamaText)).toList()
                    opened.also { open = it }
                }
            fake.embeddings(unit(0)) // the query, for a search
            return block(library)
        }

        override suspend fun documentText(documentId: String): String? = llamaText
    }

    private val chunker = Chunker(maxTokens = 8, overlap = 0f)
    private val library = TestLibrary()

    /** The query text of every search the library ran. */
    private fun searches(): List<String> =
        fake.embedRequests.filter { "llamas hum" in it.lowercase() && "softly" !in it }

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
            assertTrue(searches().toString(), searches().single().contains("why llamas hum"))
            val result = request(1).messages().last { it.getValue("role").jsonPrimitive.content == "tool" }.text()
            assertEquals("[1] Llamas\nLlamas hum softly.\n\n[2] Llamas\nThey spit at foes.", result)
            assertEquals(listOf(source(1, hum), source(2, spit)), reply.sources)
            assertEquals("the choice was saved with the new chat", true, dao.chat(chat.chatId.value)?.useLibrary)
        }

    private val passages = "[1] Llamas\nLlamas hum softly.\n\n[2] Llamas\nThey spit at foes."

    @Test fun noToolModelGetsPassagesInTheTurn() =
        runBlocking {
            fake.reply { content("Out of curiosity [1].") }
            val chat = newChat(engine(tools = false))
            eventually("the toggle is offered") { chat.useLibrary.value == false }
            chat.setUseLibrary(true)
            chat.send("Why do llamas hum?")
            val reply = answered(chat)

            assertFalse("no tools", request(0).containsKey("tools"))
            assertTrue(searches().toString(), searches().single().contains("Why do llamas hum?"))
            val asked = request(0).messages().last().text()
            assertTrue(asked, passages in asked)
            assertTrue("the passages, then the person's words", asked.endsWith(passages + "\n\nWhy do llamas hum?"))
            assertEquals(listOf(source(1, hum), source(2, spit)), reply.sources)
        }

    @Test fun passagesGoIntoTheTurnNotTheSystemPrompt() =
        runBlocking {
            settings.setInstructions("about me", "")
            fake.reply { content("Out of curiosity [1].") }
            fake.reply { content("At foes [2].") }
            val chat = newChat(engine(tools = false))
            eventually("the toggle is offered") { chat.useLibrary.value == false }
            chat.setUseLibrary(true)
            chat.send("Why do llamas hum?")
            answered(chat)
            chat.send("And spit?")
            eventually("two replies") {
                chat.messages.value.count { it.kind == MessageKind.Assistant && it.state == MessageState.Done } == 2
            }

            val first = request(0).messages()
            val second = request(1).messages()
            assertEquals("the system prompt is the same in both turns", first.first(), second.first())
            assertEquals(
                "system",
                first
                    .first()
                    .getValue("role")
                    .jsonPrimitive.content,
            )
            assertFalse(passages in first.first().text())
            assertEquals("the first turn is sent again as it was, passages and all", first, second.take(first.size))
            assertTrue(second.last().text().endsWith("And spit?"))
        }

    @Test fun visibleMessageHasNoPassageText() =
        runBlocking {
            fake.reply { content("Out of curiosity [1].") }
            val chat = newChat(engine(tools = false))
            eventually("the toggle is offered") { chat.useLibrary.value == false }
            chat.setUseLibrary(true)
            chat.send("Why do llamas hum?")
            answered(chat)

            val question = chat.messages.value.first { it.kind == MessageKind.User }
            assertEquals("its saved text is what the person wrote", "Why do llamas hum?", question.text)
            assertTrue("its passages are kept for the history", question.passages.orEmpty().endsWith(passages))
            val shown =
                ChatItems(
                    MessageStyle.Open,
                ) { _, text -> Markdown.parse(text) }.build(chat.messages.value, null)
            assertEquals("Why do llamas hum?", shown.filterIsInstance<UserItem>().single().text)
            assertEquals(
                "the chat list's preview",
                "Out of curiosity [1].",
                repository()
                    .chats()
                    .first()
                    .single()
                    .lastText,
            )
            assertFalse(
                repository()
                    .chats()
                    .first()
                    .single()
                    .title
                    .contains("Passages"),
            )
        }

    @Test fun toggleOffUsesNothing() =
        runBlocking {
            fake.reply { content("Hello.") }
            val chat = newChat(engine(tools = true))
            eventually("the toggle is offered, off") { chat.useLibrary.value == false }
            chat.send("Why do llamas hum?")
            val reply = answered(chat)
            assertFalse(request(0).containsKey("tools"))
            assertTrue(request(0).messages().none { it.getValue("role").jsonPrimitive.content == "system" })
            assertEquals(0, library.uses)
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
            assertEquals(0, library.uses)
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
                listOf(source(1, hum), source(2, spit)),
                again.messages.value
                    .last()
                    .sources,
            )
            assertEquals("the chat still has it on", true, repository().useLibrary(id).first())
        }

    @Test fun passageSheetHighlightsTheSpan() {
        val llamaText = "# Llamas\n\n## Sounds\n\nLlamas hum when they are curious. They also click.\n\nThey spit."
        val text = "Llamas hum when they are curious."
        val start = llamaText.indexOf(text)
        val hum = Hit("llamas.md", "Llamas", text, start, start + text.length, listOf("Llamas", "Sounds"), 0f, 1)
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
