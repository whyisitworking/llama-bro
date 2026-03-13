package com.suhel.llamabro.demo.model

sealed interface ModelDownloadState {
    data object NotDownloaded : ModelDownloadState
    data class Downloading(val progress: Float) : ModelDownloadState  // 0f..1f
    data object Downloaded : ModelDownloadState
    data class Error(val message: String) : ModelDownloadState
}
