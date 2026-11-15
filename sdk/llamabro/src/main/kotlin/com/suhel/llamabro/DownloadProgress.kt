package com.suhel.llamabro

/**
 * A point-in-time report from a download: the hub's, or any [RemoteModelSource]'s fetch (an app's own server, a test
 * fake), which a load reports as [LoadProgress.Fetching]. In core so that core can carry it without knowing Hugging
 * Face (D71, D75). Not a data class, so fields can be added without breaking `copy` or destructuring.
 */
public class DownloadProgress(
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
) {
    override fun equals(other: Any?): Boolean =
        other is DownloadProgress &&
            phase == other.phase &&
            bytesDone == other.bytesDone &&
            bytesTotal == other.bytesTotal &&
            currentFile == other.currentFile &&
            fileIndex == other.fileIndex &&
            fileCount == other.fileCount &&
            bytesPerSecond == other.bytesPerSecond &&
            etaSeconds == other.etaSeconds

    override fun hashCode(): Int =
        listOf(phase, bytesDone, bytesTotal, currentFile, fileIndex, fileCount, bytesPerSecond, etaSeconds).hashCode()

    override fun toString(): String =
        "DownloadProgress(phase=$phase, bytesDone=$bytesDone, bytesTotal=$bytesTotal, currentFile=$currentFile, " +
            "fileIndex=$fileIndex, fileCount=$fileCount, bytesPerSecond=$bytesPerSecond, etaSeconds=$etaSeconds)"
}

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
