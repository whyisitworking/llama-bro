package com.suhel.llamabro.sdk.internal

/**
 * Internal JNI callback invoked by the native layer during model loading.
 *
 * Each invocation corresponds to a progress update from the llama.cpp model 
 * loader, typically reflecting the percentage of the model file read into memory.
 */
internal interface ProgressListener {
    /**
     * Called by the native layer with the current loading progress.
     *
     * @param progress A value from 0.0 to 1.0 reflecting loading status.
     * @return true to continue loading, false to abort the operation.
     */
    fun onProgress(progress: Float): Boolean
}
