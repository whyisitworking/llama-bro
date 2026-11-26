package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.demo.chat.ChatLibrary
import com.suhel.llamabro.demo.data.Source
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.ui.keepAlive
import com.suhel.llamabro.rag.Hit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chat screen's library: the toggle in the attachments, and a source opened in its sheet (docs/design/rag.md). */
class ChatViewModelLibraryTest : ChatViewModelFixture() {
    private val text = "Llamas live in herds. They hum when curious. They spit."

    private val library =
        object : ChatLibrary {
            override val hasDocuments = MutableStateFlow(false)

            override suspend fun search(query: String): List<Hit> = emptyList()

            override suspend fun documentText(documentId: String): String? = text.takeIf { documentId == "llamas" }
        }

    override val chatLibrary: ChatLibrary = library

    @Test fun theToggleIsOfferedOnlyWithADocumentAndKeepsTheChoice() =
        runBlocking {
            val vm = viewModel()
            vm.library.useLibrary.keepAlive(CoroutineScope(main))
            assertNull("no document: no toggle", vm.library.useLibrary.value)
            assertEquals("the + is the photo picker", false, vm.composerState.value.attachMenu)
            library.hasDocuments.value = true
            eventually("offered, off") { vm.library.useLibrary.value == false }
            eventually("the + is a menu") { vm.composerState.value.attachMenu }
            vm.library.setUseLibrary(true)
            eventually("on") { vm.library.useLibrary.value == true }
            vm.library.setUseLibrary(false)
            eventually("off again") { vm.library.useLibrary.value == false }
        }

    @Test fun aSourceOpensInItsSheetWithTheTextAroundIt() =
        runBlocking {
            val vm = viewModel()
            vm.library.passage.keepAlive(CoroutineScope(main))
            assertNull(vm.library.passage.value)
            val start = text.indexOf("They hum")
            val source = Source(1, "llamas", "Llamas", start, start + 22, listOf("Habits"), "They hum when curious.")
            vm.library.show(source)
            eventually("its surroundings arrive") {
                vm.library.passage.value
                    ?.before
                    ?.isNotEmpty() == true
            }
            assertEquals(Passages.view(source, text), vm.library.passage.value)
            vm.library.show(null)
            eventually("closed") { vm.library.passage.value == null }

            // a source whose document is gone still opens, alone
            val gone = source.copy(documentId = "removed")
            vm.library.show(gone)
            eventually("open") { vm.library.passage.value != null }
            assertEquals(Passages.view(gone, null), vm.library.passage.value)
        }
}
