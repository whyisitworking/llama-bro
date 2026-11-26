package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * The library in a chat (docs/design/rag.md): "Use my library" in the attachments, and the sheet a source chip opens,
 * with the passage in its document's text ([documentText], null once the document is gone).
 */
class ChatLibraryHolder(
    private val handle: ChatEngine.ChatHandle,
    private val documentText: suspend (String) -> String?,
    scope: CoroutineScope,
) {
    /** "Use my library": on or off, null while it isn't offered (no document in the library). */
    val useLibrary: StateFlow<Boolean?> get() = handle.useLibrary

    /** Turns "Use my library" on or off for this chat; a new chat keeps it until its first message. */
    fun setUseLibrary(on: Boolean) = handle.setUseLibrary(on)

    // the person's input: the source chip they tapped, null when its sheet is closed
    private val opened = MutableStateFlow<Source?>(null)

    /**
     * The source sheet: the passage at once, then with its surroundings once the document's text is read, if the
     * document is still in the library; null while no source is open.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val passage: StateFlow<PassageView?> =
        opened
            .flatMapLatest { source ->
                if (source == null) {
                    flowOf<PassageView?>(null)
                } else {
                    flow {
                        emit(Passages.view(source, null))
                        emit(Passages.view(source, documentText(source.documentId)))
                    }
                }
            }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Opens [source] in its sheet, or closes the sheet with null. */
    fun show(source: Source?) {
        opened.value = source
    }
}
