package com.suhel.llamabro

/** Where a model's files are. P3 adds Hugging Face cache sources. */
public sealed interface ModelSource {
    public companion object {
        /** A local GGUF, optionally accompanied by a projector or draft model. */
        public fun file(path: String, configure: FileSourceBuilder.() -> Unit = {}): ModelSource {
            val builder = FileSourceBuilder().apply(configure)
            return FileSource(path, builder.mmproj, builder.draft)
        }
    }
}

/** Optional local files loaded alongside a model GGUF. */
public class FileSourceBuilder internal constructor() {
    /** A multimodal projector GGUF. */
    public var mmproj: String? = null

    /** A draft model for Speculative.DFlash (D41); unused with any other Speculative (LoadReport says so). */
    public var draft: String? = null
}

internal data class FileSource(val path: String, val mmproj: String?, val draft: String?) : ModelSource
