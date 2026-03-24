package com.suhel.llamabro.sdk

internal interface ProgressListener {
    fun onProgress(progress: Float): Boolean
}