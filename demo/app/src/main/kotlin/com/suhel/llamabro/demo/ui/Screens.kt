package com.suhel.llamabro.demo.ui

import androidx.lifecycle.SavedStateHandle
import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.chat.ChatSamplingViewModel
import com.suhel.llamabro.demo.ui.chat.ChatViewModel
import com.suhel.llamabro.demo.ui.chat.MessageStyle
import com.suhel.llamabro.demo.ui.chat.SwitcherViewModel
import com.suhel.llamabro.demo.ui.library.LibraryViewModel
import com.suhel.llamabro.demo.ui.models.ModelEntry
import com.suhel.llamabro.demo.ui.models.ModelSheetViewModel
import com.suhel.llamabro.demo.ui.models.ModelsViewModel
import com.suhel.llamabro.demo.ui.server.ServerViewModel
import com.suhel.llamabro.demo.ui.settings.SettingsViewModel
import com.suhel.llamabro.demo.ui.shell.ShellViewModel
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.Flow

/**
 * What the screens take from the app's composition root: view-model factories, each built from the app's collaborators
 * in one place. The container implements it, so no screen names the container or reads a repository.
 */
interface Screens {
    fun shellViewModel(saved: SavedStateHandle): ShellViewModel

    fun settingsViewModel(): SettingsViewModel

    fun modelsViewModel(initialQuery: String, initialIntro: Boolean?, saved: SavedStateHandle): ModelsViewModel

    fun modelSheetViewModel(row: ModelEntry, onExactFit: (FitView) -> Unit, startTier: SizeTier?): ModelSheetViewModel

    /** The Server page: server mode, the models it can serve and the phone's network, charger and heat. */
    fun serverViewModel(): ServerViewModel

    /** The Library page: the documents, the embedder's download and fit, import, remove and rebuild. */
    fun libraryViewModel(): LibraryViewModel

    /** The model switcher's state and writes for the chat under [handle]. */
    fun switcherViewModel(handle: ChatEngine.ChatHandle): SwitcherViewModel

    /** The sampling sheet of chat [chatId] on model [key]. */
    fun chatSamplingViewModel(chatId: Long, key: HfModel): ChatSamplingViewModel

    /**
     * The view model of chat [chatId] (0: a new chat), on [startModel] when it is new and one is given. [styles] fixes
     * the rows' layout (screenshots); null follows the layout setting.
     */
    fun chatViewModel(
        chatId: Long,
        startModel: HfModel?,
        styles: Flow<MessageStyle>?,
        saved: SavedStateHandle,
    ): ChatViewModel
}
