package com.suhel.llamabro.demo

import androidx.lifecycle.ViewModel
import com.suhel.llamabro.demo.data.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val modelRepository: ModelRepository
) : ViewModel() {
    val currentModelFlow = modelRepository.currentModelFlow
}
