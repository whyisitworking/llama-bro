package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.attempt
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.LlamaBroHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The sampling layers of a model and of a chat (D60), read and changed in one place: Settings › Sampling changes a
 * model's values, a chat's sheet changes the chat's own, and the engine reads both when a turn starts. A suggested
 * model's card values ship in the catalog; any other model's come from [recommended] (the hub's, D68 and D79). That
 * lookup can go online (the base model's values), so it runs in the background on [scope] and never holds a turn:
 * until it answers the model has no card, nothing is sent, and llama.cpp applies the file's own values (F17).
 */
class SamplingStore(
    private val settings: SettingsRepository,
    private val chats: ChatRepository,
    private val recommended: suspend (HfModel) -> HubCard = { HubCard(null) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    /** The hub's answers so far, by model; a model not here has none yet. */
    private val answers = MutableStateFlow<Map<HfModel, HubCard>>(emptyMap())

    /** When each model's last lookup started: one in flight or failed recently is not started again. */
    private val tries = ConcurrentHashMap<HfModel, Long>()

    /** Models with changed values, by key: the count a list of models shows. */
    val changed: Flow<Map<String, Knobs>> = settings.modelSampling

    /** [key]'s card and its own values; the card arrives when the hub answers. */
    fun model(key: HfModel): Flow<SamplingLayers> =
        combine(cardOf(key), settings.modelSampling) { card, own ->
            SamplingLayers(card, own[key.toString()] ?: Knobs.None)
        }

    /** [key]'s layers with the chat's own values on top; a chat not created yet ([chatId] 0) has none. */
    fun chat(key: HfModel, chatId: Long): Flow<SamplingLayers> =
        combine(model(key), if (chatId == 0L) flowOf(Knobs.None) else chats.sampling(chatId)) { layers, own ->
            layers.copy(chat = own)
        }

    suspend fun setModel(key: HfModel, knob: Knob, value: Float?) =
        settings.updateModelSampling(key.toString()) { it.with(knob, value) }

    /** Gives [key] back its card's values. */
    suspend fun resetModel(key: HfModel) = settings.updateModelSampling(key.toString()) { Knobs.None }

    suspend fun setChat(chatId: Long, knob: Knob, value: Float?) = chats.updateSampling(chatId) { it.with(knob, value) }

    /** Gives the chat back its model's values. */
    suspend fun resetChat(chatId: Long) = chats.updateSampling(chatId) { Knobs.None }

    /**
     * The card a turn uses now, at once: the catalog's, else the hub's answer if it has come. Otherwise none, and the
     * lookup starts in the background for the turns after this one.
     */
    fun card(key: HfModel): CardSampling? {
        Catalog.byRepo(key.repo)?.let { return it.sampling }
        lookUp(key)
        return answers.value[key]?.card
    }

    private fun cardOf(key: HfModel): Flow<CardSampling?> =
        Catalog.byRepo(key.repo)?.let { flowOf(it.sampling) }
            ?: answers
                .map { it[key]?.card }
                .distinctUntilChanged()
                .onStart { lookUp(key) }

    /**
     * Starts [key]'s lookup unless it has a [HubCard.final] answer, one is under way, or one started in the last
     * [RETRY_MILLIS] (a phone on Wi-Fi with no route would otherwise ask again on every turn). An answer given
     * offline is used meanwhile and asked again later, so a file with no values gets the base model's once online.
     */
    private fun lookUp(key: HfModel) {
        if (answers.value[key]?.final == true) return
        val now = clock()
        var start = false
        tries.compute(key) { _, last ->
            if (last == null || now - last >= RETRY_MILLIS) now.also { start = true } else last
        }
        if (!start) return
        scope.launch {
            val answer = withTimeoutOrNull(LOOKUP_MILLIS) { attempt { recommended(key) } }
            if (answer != null) answers.update { it + (key to answer) }
        }
    }

    private companion object {
        /** How long a lookup may take before it counts as failed. */
        const val LOOKUP_MILLIS = 15_000L

        /** How long after a failed lookup the next may start. */
        const val RETRY_MILLIS = 5 * 60_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * A lookup's answer: [card] null when the hub has no values for the model. [final] is false for an answer given
 * offline, from the file alone, which is used and asked again later ([SamplingStore]).
 */
class HubCard(val card: CardSampling?, val final: Boolean = true)

/**
 * The hub's recommended sampling for [model] as a card the layers read (D60). The catalog's models never get here:
 * their cards state what their makers recommend (F136). Offline, the hub answers from the file alone with no base
 * model, as it answers for a repo that names none; only then does a look at the repo's files tell the two apart.
 */
suspend fun LlamaBroHub.recommendedCard(model: HfModel): HubCard {
    val recommended = recommendedSampling(model)
    val card = recommendedCard(recommended.fromFile, recommended.fromBaseModel)
    return HubCard(card, final = recommended.baseModel != null || !offline(model))
}

/**
 * Whether the Hub can't be reached for [model]'s repo now: `files` answers from the cache then, or fails unreachable.
 */
private suspend fun LlamaBroHub.offline(model: HfModel): Boolean =
    try {
        files(model.repo).offline
    } catch (e: HubException.Network) {
        e.unreachable
    }

/**
 * One card from the hub's two sources (D79). The file's values win, falling back to the base model's: the file's are
 * what llama.cpp applies at load (F17), so showing them as the model's default tells the person what really runs, and
 * where the file's author chose differently from the base model (LFM3-Omni's greedy header, F146) the app does not
 * overwrite that choice. The base model's values are used only when the file has none. Only the five knobs a person
 * can change are read (D60); one set serves thinking and not, since neither source tells them apart (D68).
 */
fun recommendedCard(fromFile: Sampling?, fromBaseModel: Sampling?): CardSampling? {
    val knobs = knobsOf(fromFile).takeUnless { it.isEmpty } ?: knobsOf(fromBaseModel)
    return if (knobs.isEmpty) null else CardSampling(knobs)
}

private fun knobsOf(sampling: Sampling?): Knobs =
    if (sampling == null) {
        Knobs.None
    } else {
        Knobs.None
            .with(Knob.Temperature, sampling.temperature)
            .with(Knob.TopP, sampling.topP)
            .with(Knob.TopK, sampling.topK?.toFloat())
            .with(Knob.MinP, sampling.minP)
            .with(Knob.PresencePenalty, sampling.presencePenalty)
    }
