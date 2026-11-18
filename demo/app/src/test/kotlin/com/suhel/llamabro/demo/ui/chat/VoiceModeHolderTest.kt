package com.suhel.llamabro.demo.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.chat.FakeChatDao
import com.suhel.llamabro.demo.chat.VoiceChat
import com.suhel.llamabro.demo.chat.VoiceConversation
import com.suhel.llamabro.demo.data.AudioStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Voice mode's open and close on a chat screen (D100), and its fresh start in a chat that already has messages. */
class VoiceModeHolderTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val key = HfModel.parse("ggml-org/LFM3-Omni-2B-GGUF:Q4_K_M")
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }
    private val audio by lazy { AudioStore(File(folder.root, "audio")) }
    private val chats by lazy { ChatRepository(FakeChatDao(), ImageStore(File(folder.root, "i")), { 0L }, audio) }
    private val started = mutableListOf<VoiceChat>()

    @After fun stop() = scope.cancel()

    private fun conversation(chat: VoiceChat): VoiceConversation {
        started += chat
        return VoiceConversation(
            chat.chatId,
            chat.key,
            chat.name,
            { _, _ -> null },
            settings,
            chats,
            audio,
            microphone = { error("no microphone") },
            speech = { error("no speaker") },
            scope = scope,
            dispatcher = Dispatchers.Default,
        )
    }

    private fun holder(hasMessages: Boolean, chat: VoiceChat? = VoiceChat(7, key, "LFM3 Omni 2B")) =
        VoiceModeHolder(scope, ::conversation, { chat }) { hasMessages }

    private suspend fun opened(h: VoiceModeHolder) = withTimeout(5_000) { while (h.open.value == null) delay(10) }

    @Test fun aChatWithMessagesSaysVoiceStartsFresh() =
        runBlocking {
            val h = holder(hasMessages = true)
            h.open()
            opened(h)
            assertTrue(h.open.value!!.startsFresh)
            assertEquals(7L, started.single().chatId)
            h.open() // already open: nothing new
            delay(100)
            assertEquals(1, started.size)
            h.close()
            assertNull(h.open.value)
        }

    @Test fun anEmptyChatSaysNothing() =
        runBlocking {
            val h = holder(hasMessages = false)
            h.open()
            opened(h)
            assertFalse(h.open.value!!.startsFresh)
        }

    @Test fun noChatOrNoMicrophoneOpensNothing() =
        runBlocking {
            val noChat = holder(hasMessages = false, chat = null)
            noChat.open()
            delay(100)
            assertNull(noChat.open.value)
            val none = VoiceModeHolder(scope, null, { VoiceChat(1, key, "x") }) { false }
            assertFalse(none.available)
            none.open()
            delay(100)
            assertNull(none.open.value)
        }
}
