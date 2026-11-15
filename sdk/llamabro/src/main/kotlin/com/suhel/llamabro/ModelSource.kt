package com.suhel.llamabro

/**
 * Where a model's files are: local files ([ModelSource.file]), or a [RemoteModelSource] that fetches them before the
 * load (D64, D75). Not sealed, so that a module or an app can fetch files from anywhere; any other implementation of
 * this interface is refused with [LlamaBroException.InvalidRequest].
 */
public interface ModelSource {
    public companion object {
        /** A local GGUF, optionally accompanied by a projector or draft model. */
        public fun file(path: String, configure: FileSourceBuilder.() -> Unit = {}): ModelSource {
            val builder = FileSourceBuilder().apply(configure)
            return FileSource(path, builder.mmproj, builder.draft)
        }
    }
}

/**
 * A model whose files must be fetched before they load: the hub's `hub.model("…")`, an app's own server, or a test fake
 * (D64, D75). Core knows nothing of where they come from: `loadModel` calls [fetch] and loads the local files it
 * returns, reporting the download as [LoadProgress.Fetching]; `estimateFit` reads headers through [withHeaders] and
 * downloads no weights.
 *
 * Both functions run on the caller's dispatcher, which may be the main thread: an implementation switches to its own
 * dispatcher for IO and must be main-safe. Each hands back local files made with [ModelSource.file]; anything else
 * fails the call with [LlamaBroException.InvalidRequest].
 *
 * An implementation's own exceptions reach the caller of `loadModel` or `estimateFit` unchanged, not wrapped in a
 * [LlamaBroException]: the hub's throw `HubException` (`Network` offline with nothing installed, `Unauthorized`,
 * `NotFound`, `InsufficientStorage`), so the app catches what its source documents.
 */
public interface RemoteModelSource : ModelSource {
    /**
     * Downloads what is missing and returns the local files, made with [ModelSource.file]. [onProgress] may be called
     * from any thread, but never after [fetch] returns or throws. When the caller is cancelled, [fetch] throws
     * `CancellationException` rather than returning files; what it keeps for the next fetch is its own choice.
     */
    public suspend fun fetch(onProgress: (DownloadProgress) -> Unit): ModelSource

    /**
     * Runs [block] with local files made with [ModelSource.file] that hold at least the GGUF headers (the model's and
     * any image encoder's), valid only during [block], and removes what it made for it afterwards, also when [block]
     * throws.
     */
    public suspend fun <T> withHeaders(block: suspend (local: ModelSource) -> T): T
}

/** Optional local files loaded alongside a model GGUF. */
public class FileSourceBuilder internal constructor() {
    /** A multimodal projector GGUF. */
    public var mmproj: String? = null

    /** A draft model for Speculative.DFlash (D41); unused with any other Speculative (LoadReport says so). */
    public var draft: String? = null
}

internal data class FileSource(val path: String, val mmproj: String?, val draft: String?) : ModelSource
