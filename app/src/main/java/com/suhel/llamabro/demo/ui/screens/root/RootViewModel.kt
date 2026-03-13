package com.suhel.llamabro.demo.ui.screens.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suhel.llamabro.demo.data.repository.ModelRepository
import com.suhel.llamabro.demo.model.CurrentModel
import com.suhel.llamabro.sdk.model.LoadEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    modelRepository: ModelRepository
) : ViewModel() {
    val state = modelRepository.currentModelFlow
        .map { modelLoadEvent ->
            when (modelLoadEvent) {
                null -> RootUiState.NoModelLoaded
                is LoadEvent.Error -> RootUiState.ModelLoadError(
                    modelLoadEvent.error.message ?: "Unknown"
                )

                is LoadEvent.Loading -> RootUiState.ModelLoading(
                    modelLoadEvent.progress ?: 0f
                )

                is LoadEvent.Ready<CurrentModel> -> RootUiState.ModelLoaded(modelLoadEvent.resource.model)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootUiState.NoModelLoaded)
}
