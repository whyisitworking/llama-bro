package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the user can do about a [Limit]: the buttons the design lists, as values the screen maps to components. */
enum class LimitAction {
    TrySmallerSize,
    CloseOtherAppsAndRetry,
    StartNewChat,
    GetVisionModel,
    UseVisionModel,
    Retry,
    AddToken,
    SwitchModel,
    OpenMemorySettings,
    OpenAppSettings,
}

/**
 * One plain line and its action. A [dismissible] line is a note, not a wall (nothing waits on it): it has a close
 * button. A line that holds the chat up can't be closed, or its way out would go with it.
 */
data class Limit(
    val line: String,
    val action: LimitAction,
    val actionLabel: String,
    val second: LimitAction? = null,
    val secondLabel: String? = null,
    val dismissible: Boolean = false,
)

/**
 * The note the person closed (its ✕), so the line under the messages leaves it out. Only a [Limit.dismissible] note
 * closes: a line the chat waits on keeps its way out.
 */
class ClosedNotes {
    private val _closed = MutableStateFlow<Limit?>(null)
    val closed: StateFlow<Limit?> = _closed

    fun close(limit: Limit?) {
        _closed.value = limit?.takeIf { it.dismissible }
    }
}

/** The plain limit lines, in one place, so the copy is the design's word for word. */
object Limits {
    /**
     * The model loaded although the SDK's memory check said it may not fit (`LoadReport.memoryWarning`, F128): the
     * check can't count the background apps Android closes to make room, so this is a note, not a wall. The reply
     * goes on; the next message clears the line.
     */
    fun memoryWarning(name: String) =
        Limit(
            "$name is using more memory than was free. Android may close apps in the background.",
            LimitAction.TrySmallerSize,
            "Smaller size",
            dismissible = true,
        )

    /**
     * The model runs on another chip than the one chosen in Settings › Memory (D72), with the gist of why. A note: the
     * reply goes on, on the chip that loaded it. **Settings** opens the choice.
     */
    fun chipFallback(name: String, fallback: ChipFallback) =
        Limit(
            "$name is running on the ${fallback.runsOn.label}: ${fallback.why}.",
            LimitAction.OpenMemorySettings,
            "Settings",
            dismissible = true,
        )

    /** A load that failed for memory all the same: the phone really had no room. */
    fun insufficientMemory(name: String) =
        Limit(
            "$name needs more memory than this phone has free right now.",
            LimitAction.TrySmallerSize,
            "Smaller size",
            LimitAction.Retry,
            "Try again",
        )

    fun closeOtherApps(name: String) =
        Limit(
            "$name needs more memory than this phone has free right now.",
            LimitAction.CloseOtherAppsAndRetry,
            "Close other apps and retry",
        )

    fun contextExceeded(name: String) =
        Limit(
            "This chat is longer than $name can hold.",
            LimitAction.StartNewChat,
            "Start a new chat",
        )

    fun noVision(name: String, visionModelName: String?) =
        Limit(
            "$name can't see images. ${visionModelName ?: "Qwen3.5 4B"} can.",
            if (visionModelName != null) LimitAction.UseVisionModel else LimitAction.GetVisionModel,
            if (visionModelName != null) "Switch to $visionModelName" else "Get one that can",
        )

    /**
     * The chat holds voice messages its model can't hear (D76): each went as a note saying one was there. A note, so it
     * can be closed; its way out is another model.
     */
    fun cantHear(name: String) =
        Limit(
            "$name can't hear voice messages, so it got a note in their place.",
            LimitAction.SwitchModel,
            "Switch model",
            dismissible = true,
        )

    /** The microphone permission was refused (D76): Android's own page for the app is where it is given. */
    fun microphone() =
        Limit(
            "Llama Bro needs the microphone for voice messages.",
            LimitAction.OpenAppSettings,
            "Open settings",
            dismissible = true,
        )

    fun modelGone(name: String) = Limit("$name isn't on this phone any more.", LimitAction.SwitchModel, "Switch model")

    /** The line for a model that failed to load or a reply that failed, or null when it needs none. */
    fun forLoadFailure(name: String, error: Exception): Limit? =
        when {
            error is LlamaBroException.ModelLoadFailed &&
                error.reason == LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory -> insufficientMemory(name)

            error is LlamaBroException.ContextExceeded -> contextExceeded(name)

            else -> null
        }

    /** The line for a failed download (the hub's [failure]). */
    fun forDownload(failure: DownloadFailure): Limit =
        when {
            failure.retryable -> {
                Limit("The download paused. It'll pick up where it left off.", LimitAction.Retry, "Retry")
            }

            failure.kind == DownloadFailure.Kind.Unauthorized -> {
                Limit("This model needs a Hugging Face token.", LimitAction.AddToken, "Add token")
            }

            failure.kind == DownloadFailure.Kind.InsufficientStorage -> {
                Limit("There isn't enough space on this phone for this model.", LimitAction.Retry, "Retry")
            }

            else -> {
                Limit("Hugging Face wouldn't give us this file.", LimitAction.Retry, "Retry")
            }
        }

    fun forDownload(status: DownloadStatus.Failed): Limit = forDownload(status.failure)
}
