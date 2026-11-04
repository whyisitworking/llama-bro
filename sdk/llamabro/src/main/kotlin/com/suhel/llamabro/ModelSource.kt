package com.suhel.llamabro

/** Where a model's files are. P3 adds Hugging Face cache sources. */
public sealed interface ModelSource {
    public companion object {
        /** A local GGUF, with an optional multimodal projector GGUF. */
        public fun file(path: String, mmproj: String? = null): ModelSource = FileSource(path, mmproj)
    }
}

internal data class FileSource(val path: String, val mmproj: String?) : ModelSource
