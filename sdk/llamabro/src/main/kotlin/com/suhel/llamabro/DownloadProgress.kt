package com.suhel.llamabro

/**
 * A point-in-time report from a download: the hub's, or any [RemoteModelSource]'s fetch (an app's own server, a test
 * fake), which a load reports as [LoadProgress.Fetching]. In core so that core can carry it without knowing Hugging
 * Face ([D71](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d71),
 * [D75](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d75)).
 */
public data class DownloadProgress(
    /** Current operation. */
    public val phase: DownloadPhase,
    /** Verified or received bytes across the plan. */
    public val bytesDone: Long,
    /** Expected bytes across the plan. */
    public val bytesTotal: Long,
    /** Repository-relative file currently being transferred, if any. */
    public val currentFile: String? = null,
    /** One-based position of [currentFile], or zero when none. */
    public val fileIndex: Int = 0,
    /** Number of files in the plan. */
    public val fileCount: Int = 1,
    /** Smoothed transfer speed; null when the source doesn't measure it. */
    public val bytesPerSecond: Long? = null,
    /** Estimated seconds remaining; null when the source doesn't measure it. */
    public val etaSeconds: Long? = null,
)

/** Stage of a download. */
public sealed interface DownloadPhase {
    /** Resolving the model and its files. */
    public data object Resolving : DownloadPhase

    /** Hashing a partial file before resuming. */
    public data object Verifying : DownloadPhase

    /** Fetching file content. */
    public data object Downloading : DownloadPhase

    /** Every selected file is present and verified. */
    public data object Complete : DownloadPhase
}
