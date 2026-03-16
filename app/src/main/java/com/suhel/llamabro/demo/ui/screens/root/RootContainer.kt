package com.suhel.llamabro.demo.ui.screens.root

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun RootContainer(vm: RootViewModel = hiltViewModel()) {
    Column(modifier = Modifier.fillMaxSize()) {
        val state by vm.state.collectAsStateWithLifecycle()
        StatusBar(state, onEjectModel = vm::ejectModel)
        AppNavigation(state, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusBar(
    state: RootUiState,
    onEjectModel: () -> Unit,
) {
    val backgroundColor = when (state) {
        RootUiState.NoModelLoaded,
        is RootUiState.ModelLoading,
        is RootUiState.ModelLoaded -> MaterialTheme.colorScheme.surfaceDim

        is RootUiState.ModelLoadError -> MaterialTheme.colorScheme.error
    }

    Surface(
        color = backgroundColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .animateContentSize(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val text = when (state) {
                    RootUiState.NoModelLoaded -> "No model loaded"
                    is RootUiState.ModelLoading -> "Loading ${state.model.name}"
                    is RootUiState.ModelLoaded -> "Loaded ${state.model.name}"
                    is RootUiState.ModelLoadError -> "Error loading ${state.model.name}"
                }

                Text(
                    text = text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (state is RootUiState.ModelLoaded) {
                    TextButton(
                        onClick = onEjectModel,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "Eject",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (state is RootUiState.ModelLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = { state.progress }
                )
            }
        }
    }
}
