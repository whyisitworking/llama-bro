package com.suhel.llamabro.sdk.internal

/**
 * Internal JNI callback invoked by the native layer during model loading.
 * Each call corresponds to one progress_callback invocation in llama.cpp.
 */
interface ProgressListener {
    /**
     * @param progress 0.0..1.0 reflecting how much of the model file has been read.
     * @return true to continue loading, false to abort (e.g., coroutine cancelled).
     */
    fun onProgress(progress: Float): Boolean
}
