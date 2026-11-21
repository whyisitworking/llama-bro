package com.suhel.llamabro.demo.ui.shell

import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutesTest {
    @Test fun aNewChatOnAModelCarriesItsKeyEncoded() {
        val key = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
        assertEquals("chat/0?model=unsloth%2FQwen3.5-4B-GGUF%3AQ4_K_M", Routes.newChatWith(key))
    }

    @Test fun aChatsIdIsReadWithOrWithoutAModel() {
        assertEquals(7L, Routes.chatIdOf("chat/7"))
        assertEquals(0L, Routes.chatIdOf(Routes.newChatWith(HfModel.parse("a/b-GGUF:Q4_K_M"))))
        assertNull(Routes.chatIdOf("models?q="))
    }

    @Test fun aFreshInstallStartsOnTheModelsPageInEitherLayout() {
        // first run is the Models page's empty state now: there is no first-run route (models-page design §2, §7)
        assertEquals(Routes.MODELS, Routes.start(startsWithFirstRun = true, AppLayout.ChatList))
        assertEquals(Routes.MODELS, Routes.start(startsWithFirstRun = true, AppLayout.Sidebar))
        assertEquals(Routes.CHATS, Routes.start(startsWithFirstRun = false, AppLayout.ChatList))
        assertEquals(Routes.chat(Routes.NEW_CHAT_ID), Routes.start(startsWithFirstRun = false, AppLayout.Sidebar))
    }
}
