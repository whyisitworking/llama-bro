package com.suhel.llamabro.sample

import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The sample's ViewModel on the JVM, the way an app tests its own (PA T8, D27): no phone, no model. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Before fun main() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun reset() = Dispatchers.resetMain()

    private fun loaded(fake: FakeLlamaBro) = ChatViewModel(fake.llamaBro, "/models/fake.gguf").also { vm ->
        runBlocking { withTimeout(5_000) { vm.ready.first { it } } }
    }

    private fun ChatViewModel.waitFor(text: String) = runBlocking {
        withTimeout(5_000) { transcript.first { it.contains(text) } }
    }

    @Test
    fun loadsThenStreamsAReply() {
        val fake = FakeLlamaBro { reply { content("Hello there!"); timings(promptTokens = 12, predictedTokens = 3) } }
        val vm = loaded(fake)
        assertTrue(vm.status.value, vm.status.value.contains("context 8192"))
        vm.send("Hi")
        vm.waitFor("Hello there!")
        assertTrue(vm.transcript.value.contains("> Hi"))
        runBlocking { withTimeout(5_000) { vm.stats.first { it != null && it.contains("t/s decode") } } }
    }

    @Test
    fun compactsWhenTheContextIsFull() {
        val fake = FakeLlamaBro {
            repeat(12) { i -> reply { content("Answer $i.") } }
            contextExceeded(promptTokens = 9000, contextSize = 8192)
            reply { content("Summary of the chat.") }
            reply { content("Answer after compacting.") }
        }
        val vm = loaded(fake)
        repeat(12) { i ->
            vm.send("Question $i")
            vm.waitFor("Answer $i.")
        }
        vm.send("One more")
        vm.waitFor("Answer after compacting.")
        assertTrue(vm.transcript.value.contains("[older turns summarised]"))
        assertEquals(15, fake.chatRequests.size)
    }

    @Test
    fun loadFailureIsShown() {
        val vm = ChatViewModel(FakeLlamaBro().llamaBro, null)
        assertEquals("No .gguf in the app's files", vm.status.value)
    }
}
