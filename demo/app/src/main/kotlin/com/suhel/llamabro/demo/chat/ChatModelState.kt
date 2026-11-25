package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.data.ModelSenses
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadStatus
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.hub.HfModel

/** The state of a chat's model that isn't on the phone: still arriving, its download failed, or missing. */
internal fun missingOrDownloading(name: String, status: DownloadStatus): ChatModelState =
    when (status) {
        is DownloadStatus.Downloading, is DownloadStatus.Partial -> ChatModelState.Downloading(status)
        is DownloadStatus.Failed -> ChatModelState.DownloadFailed(name, status.failure)
        else -> ChatModelState.Missing(name)
    }

/**
 * The chat's model as the composer sees it: waking while it loads, else ready with what it can do ([sensesOf]): the
 * loaded model's word, else what [senses] remembers of its header or last load (D76, D155), else the catalog's; voice
 * mode by its load, or [talking] (those that spoke on their last load, D100).
 */
internal fun readyOrWaking(
    key: HfModel,
    name: String,
    hostState: HostState,
    senses: Map<String, ModelSenses> = emptyMap(),
    talking: Set<String> = emptySet(),
): ChatModelState {
    if (hostState is HostState.Waking && hostState.key == key) return ChatModelState.Waking(hostState.progress)
    val known = sensesOf(key, hostState, senses[key.toString()], talking)
    return ChatModelState.Ready(name, known.sees, known.thinks, hears = known.hears, talks = known.talks)
}

/**
 * Why model [name] can't take a message's pictures or voice message, when it can't see or hear them, or null when it
 * can; [visionModel] is a model on the phone that sees.
 */
internal fun refusal(
    sees: Boolean,
    hears: Boolean,
    pictures: Boolean,
    voice: Boolean,
    name: String,
    visionModel: String?,
): Limit? =
    when {
        pictures && !sees -> Limits.noVision(name, visionModel)
        voice && !hears -> Limits.cantHear(name)
        else -> null
    }

/**
 * What a chat knows of its model: not looked up yet ([Unknown]), looked up with none chosen ([None]: a new chat and no
 * model for new chats), or [Chosen], by the lookup, the first send or a switch.
 */
internal sealed interface ModelChoice {
    data object Unknown : ModelChoice

    data object None : ModelChoice

    data class Chosen(val key: HfModel) : ModelChoice
}

/** The choice once the lookup found [found]: a key the first send or a switch chose meanwhile stands. */
internal fun ModelChoice.lookedUp(found: HfModel?): ModelChoice =
    this as? ModelChoice.Chosen ?: found?.let(ModelChoice::Chosen) ?: ModelChoice.None

/** The chosen model's key; null while unknown or when none is chosen. */
internal val ModelChoice.key: HfModel? get() = (this as? ModelChoice.Chosen)?.key

/** What the chat's model state is derived from. */
internal data class ModelInputs(
    val choice: ModelChoice,
    val library: List<LibraryModel>?,
    val host: HostState,
    val senses: Map<String, ModelSenses>,
    val talking: Set<String>,
)

/** What the chat knows about its model, for the header, the composer and the held-message note. */
sealed interface ChatModelState {
    data class Downloading(val status: DownloadStatus?) : ChatModelState

    /** The model is loading; [progress] is how far, 0 to 1, once the SDK has said. */
    data class Waking(val progress: Float? = null) : ChatModelState

    /** [hears]: the model takes voice messages (D76); [talks]: it holds a spoken conversation, voice mode (D100). */
    data class Ready(
        val name: String,
        val sees: Boolean,
        val canThink: Boolean,
        val hears: Boolean = false,
        val talks: Boolean = false,
    ) : ChatModelState

    data class Missing(val name: String) : ChatModelState

    /** A new chat with no model chosen yet (none by default, none last used): nothing is loading. */
    data object NoModel : ChatModelState

    /** The model is on its way but the download stopped: [failure] says why (a token, the network, the disk). */
    data class DownloadFailed(val name: String, val failure: DownloadFailure) : ChatModelState
}

/** A new chat's header before any model is chosen: what the title's switcher is for. */
internal const val NO_MODEL_TITLE = "Choose a model"
