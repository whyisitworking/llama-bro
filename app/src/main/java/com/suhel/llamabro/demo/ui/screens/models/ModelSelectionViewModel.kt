package com.suhel.llamabro.demo.ui.screens.models

import androidx.lifecycle.ViewModel
import com.suhel.llamabro.demo.data.repository.ModelRepository
import com.suhel.llamabro.demo.model.Model
import com.suhel.llamabro.demo.model.ModelDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class ModelSelectionViewModel @Inject constructor(
    private val modelRepository: ModelRepository
) : ViewModel() {

    val models = modelRepository.getAllModels()

    fun download(model: Model) {
        modelRepository.startDownload(model)
    }

    fun load(model: Model) {
        modelRepository.loadModel(model)
    }

    fun downloadStateFor(model: Model): Flow<ModelDownloadState> =
        modelRepository.getStateFor(model)
}
