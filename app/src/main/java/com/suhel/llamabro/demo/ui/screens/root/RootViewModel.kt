package com.suhel.llamabro.demo.ui.screens.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.data.repository.ModelRepository
import com.suhel.llamabro.sdk.model.fold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    private val modelRepository: ModelRepository
) : ViewModel() {
    val state = modelRepository.currentInferenceContextFlow
        .map { currentInferenceContext ->
            currentInferenceContext?.engine?.fold(
                onLoading = { progress ->
                    RootUiState.ModelLoading(
                        model = currentInferenceContext.model,
                        progress = progress ?: 0f
                    )
                },
                onSuccess = {
                    RootUiState.ModelLoaded(model = currentInferenceContext.model)
                },
                onFailure = { error ->
                    RootUiState.ModelLoadError(
                        model = currentInferenceContext.model,
                        message = error.message ?: "Unknown"
                    )
                },
            ) ?: RootUiState.NoModelLoaded
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootUiState.NoModelLoaded)

    fun ejectModel() {
        modelRepository.ejectModel()
    }
}
