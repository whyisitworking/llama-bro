package com.suhel.llamabro.demo.ui.screens.root

import com.suhel.llamabro.demo.model.Model

sealed interface RootUiState {
    data object NoModelLoaded : RootUiState
    data class ModelLoading(val progress: Float) : RootUiState
    data class ModelLoaded(val model: Model) : RootUiState
    data class ModelLoadError(val message: String): RootUiState
}
