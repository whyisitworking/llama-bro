package com.suhel.llamabro.demo.ui

import android.content.ContentResolver
import androidx.lifecycle.SavedStateHandle
import com.suhel.llamabro.demo.chat.ModelMemory
import com.suhel.llamabro.demo.chat.SamplingStore
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.DeviceFacts
import com.suhel.llamabro.demo.models.FitView
import com.suhel.llamabro.demo.models.ModelRepository
import com.suhel.llamabro.demo.models.SizeTier
import com.suhel.llamabro.demo.ui.chat.ChatViewModel
import com.suhel.llamabro.demo.ui.chat.MessageStyle
import com.suhel.llamabro.demo.ui.models.ModelEntry
import com.suhel.llamabro.demo.ui.models.ModelSheetViewModel
import com.suhel.llamabro.demo.ui.models.ModelsViewModel
import com.suhel.llamabro.demo.ui.settings.SettingsViewModel
import com.suhel.llamabro.demo.ui.shell.ShellViewModel
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * What the screens take from the app's composition root: the view models, built from the app's collaborators, and the
 * few collaborators the chat's sheets read. The container implements it, so no screen names the container, and a
 * screen's view model is made in one place.
 */
interface Screens {
    val settings: SettingsRepository
    val models: ModelRepository
    val chats: ChatRepository
    val memory: ModelMemory
    val sampling: SamplingStore

    /** Whether Get asks for the notification permission first. */
    val asksForNotifications: Boolean

    /** The phone's facts, read once. */
    suspend fun deviceFacts(): DeviceFacts

    /** A picture attached to a draft, for its preview. */
    fun draftPicture(name: String): File

    fun shellViewModel(saved: SavedStateHandle): ShellViewModel

    fun settingsViewModel(): SettingsViewModel

    fun modelsViewModel(initialQuery: String, initialIntro: Boolean?, saved: SavedStateHandle): ModelsViewModel

    fun modelSheetViewModel(row: ModelEntry, onExactFit: (FitView) -> Unit, startTier: SizeTier?): ModelSheetViewModel

    /**
     * The view model of chat [chatId] (0: a new chat), on [startModel] when it is new and one is given. [styles] fixes
     * the rows' layout (screenshots); null follows the layout setting. Pictures are picked through [resolver].
     */
    fun chatViewModel(
        chatId: Long,
        startModel: HfModel?,
        styles: Flow<MessageStyle>?,
        saved: SavedStateHandle,
        resolver: ContentResolver,
    ): ChatViewModel
}
