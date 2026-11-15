package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The sampling layers of a model and of a chat (D60), read and changed in one place: Settings › Sampling changes a
 * model's values, a chat's sheet changes the chat's own, and the engine reads both when a turn starts.
 */
class SamplingStore(private val settings: SettingsRepository, private val chats: ChatRepository) {
    /** Models with changed values, by key: the count a list of models shows. */
    val changed: Flow<Map<String, Knobs>> = settings.modelSampling

    /** [key]'s card and its own values. */
    fun model(key: HfModel): Flow<SamplingLayers> =
        settings.modelSampling.map { SamplingLayers(cardOf(key), it[key.toString()] ?: Knobs.None) }

    /** [key]'s layers with the chat's own values on top; a chat not created yet ([chatId] 0) has none. */
    fun chat(key: HfModel, chatId: Long): Flow<SamplingLayers> =
        combine(model(key), if (chatId == 0L) flowOf(Knobs.None) else chats.sampling(chatId)) { layers, own ->
            layers.copy(chat = own)
        }

    suspend fun setModel(key: HfModel, knob: Knob, value: Float?) {
        val own = settings.modelSampling.first()[key.toString()] ?: Knobs.None
        settings.setModelSampling(key.toString(), own.with(knob, value))
    }

    /** Gives [key] back its card's values. */
    suspend fun resetModel(key: HfModel) = settings.setModelSampling(key.toString(), Knobs.None)

    suspend fun setChat(chatId: Long, knob: Knob, value: Float?) {
        chats.setSampling(chatId, chats.sampling(chatId).first().with(knob, value))
    }

    /** Gives the chat back its model's values. */
    suspend fun resetChat(chatId: Long) = chats.setSampling(chatId, Knobs.None)

    private fun cardOf(key: HfModel): CardSampling? = Catalog.byRepo(key.repo)?.sampling
}
