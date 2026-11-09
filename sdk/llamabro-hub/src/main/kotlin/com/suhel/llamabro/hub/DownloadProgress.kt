package com.suhel.llamabro.hub

/** A point-in-time report from a cold Hub download flow. */
public class DownloadProgress internal constructor(
    /** Current operation. */
    public val phase: DownloadPhase,
    /** Verified or received bytes across the plan. */
    public val bytesDone: Long,
    /** Expected bytes across the plan. */
    public val bytesTotal: Long,
    /** Repository-relative file currently being transferred, if any. */
    public val currentFile: String?,
    /** One-based position of [currentFile], or zero when none. */
    public val fileIndex: Int,
    /** Number of files in the plan. */
    public val fileCount: Int,
    /** Smoothed transfer speed; populated by the T5 progress meter. */
    public val bytesPerSecond: Long?,
    /** Estimated seconds remaining; populated by the T5 progress meter. */
    public val etaSeconds: Long?,
)

/** Stage of a Hub download. */
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
