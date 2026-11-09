package com.suhel.llamabro.hub

/** The locally known state of a selected Hub download. */
public sealed interface DownloadState {
    /** No selected files or partial transfer are known locally. */
    public data object Absent : DownloadState

    /** Some selected bytes are present, but the download is incomplete. */
    public class Partial internal constructor(public val bytesDone: Long, public val bytesTotal: Long) : DownloadState

    /** A transfer is currently running in this process. */
    public class Downloading internal constructor(public val progress: DownloadProgress) : DownloadState

    /** All selected files are present locally. */
    public data object Complete : DownloadState

    /** The last transfer ended with [error]. */
    public class Failed internal constructor(public val error: HubException) : DownloadState
}
