package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.MessageState
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.StoredMessage
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.FakeScheduler
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.TestHub
import com.suhel.llamabro.demo.models.installedModel
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * How a streaming reply reaches the screen and Room: deltas coalesce to a frame, Room is written at most every 250 ms,
 * and the stored list doesn't emit per token. Each bound follows the time the stream actually took, so a slow machine
 * can't break it (the rest of ChatEngine is in ChatEngineTest).
 */
class ChatEnginePacingTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
    private val deep = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
    private val llamaKey: HfModel = Catalog.suggested.first { !it.sees && !it.thinks }.key

    private val fake = FakeLlamaBro()
    private val testHub = TestHub()
    private val hub get() = testHub.hub
    private val dao = FakeChatDao()
    private val scheduler = FakeScheduler()
    private val settings by lazy {
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") },
        )
    }
    private val chats by lazy {
        ChatRepository(
            dao,
            ImageStore(File(folder.root, "images")),
            System::currentTimeMillis,
        )
    }
    private val host by lazy { ModelHost(fake.llamaBro, { hub.installedModel(it) }, scope) }
    private val models by lazy { ModelRepository(hub, settings, scheduler, scope, host) }

    private var streamer: Streamer? = null
    private var frameMillis = 16L
    private val engine by lazy {
        val custom = streamer
        if (custom != null) {
            ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, frameMillis, streamer = custom)
        } else {
            ChatEngine(chats, host, models, settings, System::currentTimeMillis, scope, frameMillis)
        }
    }

    @After fun stop() {
        runBlocking { host.release() }
        scope.cancel()
        testHub.close()
    }

    private fun onPhone(vararg keys: HfModel) {
        testHub.install(*keys)
        models.changed()
    }

    private suspend fun rows(handle: ChatEngine.ChatHandle): List<StoredMessage> = handle.messages.value

    private fun reply(text: String, reasoning: String? = null) =
        fake.reply {
            reasoning?.let { reasoning(it) }
            content(text)
            timings(promptTokens = 10, predictedTokens = 20)
        }

    private suspend fun newChat(): ChatEngine.ChatHandle {
        settings.setLastModel(swift.toString())
        onPhone(swift)
        return engine.open(0)
    }

    @Test fun deltasAreCoalescedToOneUpdatePerFrame() =
        runBlocking {
            streamer = { _, _, _, _ -> flow { repeat(200) { emit(ChatEvent.ContentDelta("w$it ")) } } }
            // a long frame, so the bound follows from the time the turn took, not from how fast this machine is
            frameMillis = 1_000
            val chat = newChat()
            val seen = mutableListOf<String>()
            val watcher = scope.launch { chat.streaming.collect { it?.let { s -> seen += s.text } } }
            val start = System.currentTimeMillis()
            chat.send("go", emptyList())
            eventually("done") { rows(chat).any { it.kind == MessageKind.Assistant && it.state == MessageState.Done } }
            watcher.cancel()
            val frames = (System.currentTimeMillis() - start) / frameMillis + 1
            assertTrue("one update per frame, not per delta: ${seen.size} in $frames frames", seen.size <= frames)
            assertTrue(rows(chat).last().text.startsWith("w0 ") && rows(chat).last().text.endsWith("w199 "))
        }

    @Test fun roomIsWrittenAtMostEvery250ms() =
        runBlocking {
            streamer = { _, _, _, _ ->
                flow {
                    repeat(40) {
                        emit(ChatEvent.ContentDelta("w$it "))
                        delay(25)
                    }
                }
            }
            val chat = newChat()
            chat.send("go", emptyList())
            eventually("started") { chat.busy.value }
            val before = dao.messageUpdates.get()
            val start = System.currentTimeMillis()
            eventually(
                "done",
                20_000,
            ) { rows(chat).any { it.kind == MessageKind.Assistant && it.state == MessageState.Done } }
            val writes = dao.messageUpdates.get() - before
            // the bound follows the time the reply took: a loaded machine stretches the reply, and writes with it
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                "a reply writes once per 250 ms plus the end, was $writes in $elapsed ms",
                writes <= elapsed / 250 + 5,
            )
        }

    @Test fun storedListDoesNotEmitPerToken() =
        runBlocking {
            streamer = { _, _, _, _ ->
                flow {
                    repeat(30) {
                        emit(ChatEvent.ContentDelta("w$it "))
                        delay(10)
                    }
                }
            }
            val chat = newChat()
            var emissions = 0
            val start = System.currentTimeMillis()
            val watcher = scope.launch { chat.messages.collect { emissions++ } }
            chat.send("go", emptyList())
            eventually("done") { rows(chat).any { it.kind == MessageKind.Assistant && it.state == MessageState.Done } }
            watcher.cancel()
            // the bound follows the time the reply took: a loaded machine stretches the reply, and writes with it
            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                "the list changes at the start and the end plus a 250 ms write, was $emissions in $elapsed ms",
                emissions <= elapsed / 250 + 9,
            )
        }
}
