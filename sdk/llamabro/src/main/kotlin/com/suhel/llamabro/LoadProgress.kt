package com.suhel.llamabro

/**
 * What a load is doing, given to [ModelConfig.onProgress]: a [RemoteModelSource] reports [Fetching] while its files
 * download, then every load reports [Loading] while llama.cpp reads the weights (D64, D75). A local file reports only
 * [Loading].
 */
public sealed interface LoadProgress {
    /**
     * The source's files are downloading; [download] is the source's own report.
     */
    public data class Fetching(public val download: DownloadProgress) : LoadProgress

    /** llama.cpp's load progress, 0 to 1. */
    public data class Loading(public val fraction: Float) : LoadProgress
}
