package com.suhel.llamabro.demo.ui.screens.root

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val STATUS_BAR_HEIGHT = 48.dp

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
    val animatedProgress by animateFloatAsState(
        targetValue = if (state is RootUiState.ModelLoading) state.progress else 0f,
        label = "progress"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when (state) {
            is RootUiState.ModelLoadError -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        label = "backgroundColor"
    )

    Surface(
        color = backgroundColor,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .height(STATUS_BAR_HEIGHT)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
            ) {
                LoadingProgressBar(
                    isLoading = state is RootUiState.ModelLoading,
                    progress = animatedProgress
                )

                StatusText(
                    state = state,
                    animatedProgress = animatedProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 16.dp)
                )
            }

            EjectButton(
                isVisible = state is RootUiState.ModelLoaded,
                onEject = onEjectModel,
            )
        }
    }
}

@Composable
private fun LoadingProgressBar(
    isLoading: Boolean,
    progress: Float,
    modifier: Modifier = Modifier
) {
    if (isLoading) {
        Box(
            modifier = modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun StatusText(
    state: RootUiState,
    animatedProgress: Float,
    modifier: Modifier = Modifier
) {
    val text = when (state) {
        RootUiState.NoModelLoaded -> "No model loaded"
        is RootUiState.ModelLoading -> "${state.model.name} (${(animatedProgress * 100).toInt()}%)"
        is RootUiState.ModelLoaded -> state.model.name
        is RootUiState.ModelLoadError -> "Error loading ${state.model.name}"
    }

    val textColor by animateColorAsState(
        targetValue = when (state) {
            is RootUiState.ModelLoading -> MaterialTheme.colorScheme.onPrimary
            is RootUiState.ModelLoadError -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "textColor"
    )

    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = textColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun EjectButton(
    isVisible: Boolean,
    onEject: () -> Unit
) {
    if (isVisible) {
        Surface(
            onClick = onEject,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.fillMaxHeight()
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Eject",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
