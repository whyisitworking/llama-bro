package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.demo.models.DownloadFailure
import com.suhel.llamabro.demo.models.DownloadStatus

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
}

/** One plain line and its action. */
data class Limit(
    val line: String,
    val action: LimitAction,
    val actionLabel: String,
    val second: LimitAction? = null,
    val secondLabel: String? = null,
)

/** The plain limit lines, in one place, so the copy is the design's word for word. */
object Limits {
    fun insufficientMemory(name: String) =
        Limit(
            "$name needs more memory than this phone has free right now.",
            LimitAction.Retry,
            "Try again",
            LimitAction.TrySmallerSize,
            "Smaller size",
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
