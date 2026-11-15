package com.suhel.llamabro

/**
 * What a load is doing, given to [ModelConfig.onProgress]: a [RemoteModelSource] reports [Fetching] while its files
 * download, then every load reports [Loading] while llama.cpp reads the weights (D64, D75). A local file reports only
 * [Loading].
 */
public sealed interface LoadProgress {
    /**
     * The source's files are downloading; [download] is the source's own report. Not a data class, so fields can be
     * added without breaking `copy` or destructuring, as [DownloadProgress].
     */
    public class Fetching(public val download: DownloadProgress) : LoadProgress {
        override fun equals(other: Any?): Boolean = other is Fetching && download == other.download

        override fun hashCode(): Int = download.hashCode()

        override fun toString(): String = "Fetching(download=$download)"
    }

    /** llama.cpp's load progress, 0 to 1. Not a data class, as [Fetching]. */
    public class Loading(public val fraction: Float) : LoadProgress {
        override fun equals(other: Any?): Boolean = other is Loading && fraction == other.fraction

        override fun hashCode(): Int = fraction.hashCode()

        override fun toString(): String = "Loading(fraction=$fraction)"
    }
}
