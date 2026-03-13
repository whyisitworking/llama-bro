package com.suhel.llamabro.demo.ui.screens.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RootContainer(vm: RootViewModel = hiltViewModel()) {
    Column(modifier = Modifier.fillMaxSize()) {
        val state by vm.state.collectAsStateWithLifecycle()
        StatusBar(state)
        AppNavigation()
    }
}

@Composable
private fun StatusBar(state: RootUiState) {
    val backgroundColor = when (state) {
        RootUiState.NoModelLoaded -> MaterialTheme.colorScheme.surfaceDim
        is RootUiState.ModelLoadError -> MaterialTheme.colorScheme.error
        is RootUiState.ModelLoading -> MaterialTheme.colorScheme.surfaceContainerHigh
        is RootUiState.ModelLoaded -> MaterialTheme.colorScheme.surfaceContainer
    }

    val text = when (state) {
        RootUiState.NoModelLoaded -> "No model loaded"
        is RootUiState.ModelLoadError -> "Error loading model"
        is RootUiState.ModelLoading -> "Loading model"
        is RootUiState.ModelLoaded -> "Loaded ${state.model.name}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        (state as? RootUiState.ModelLoading)?.let {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                progress = { it.progress }
            )
        }
    }
}
