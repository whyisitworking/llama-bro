package com.suhel.llamabro.demo.ui.screens.models

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suhel.llamabro.demo.R
import com.suhel.llamabro.demo.model.Model
import com.suhel.llamabro.demo.model.ModelDownloadState
import com.suhel.llamabro.demo.ui.AppScaffold
import com.suhel.llamabro.demo.ui.theme.Error
import com.suhel.llamabro.demo.ui.theme.OnSurfaceFaint
import com.suhel.llamabro.demo.ui.theme.OnSurfaceMuted
import com.suhel.llamabro.demo.ui.theme.Success
import com.suhel.llamabro.demo.ui.theme.SurfaceVariant
import com.suhel.llamabro.demo.ui.theme.Violet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionScreen(
    onModelReady: () -> Unit,
    viewModel: ModelSelectionViewModel = hiltViewModel(),
) {
    AppScaffold(
        title = "Models"
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(viewModel.models, key = { it.id }) { model ->
                val downloadStateFlow = remember(model) { viewModel.downloadStateFor(model) }
                val state by downloadStateFlow.collectAsStateWithLifecycle(ModelDownloadState.NotDownloaded)

                ModelCard(
                    item = model,
                    state = state,
                    onDownload = { viewModel.download(model) },
                    onLoad = {
                        viewModel.load(model)
                        onModelReady()
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    item: Model,
    state: ModelDownloadState,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (state is ModelDownloadState.Downloaded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                AnimatedContent(
                    targetState = state,
                    label = "state-icon"
                ) { s ->
                    when (s) {
                        is ModelDownloadState.Downloaded ->
                            Icon(
                                painterResource(R.drawable.check_circle_24px),
                                contentDescription = null,
                                tint = Success,
                                modifier = Modifier.size(20.dp)
                            )

                        else ->
                            Icon(
                                painterResource(R.drawable.cloud_24px),
                                contentDescription = null,
                                tint = OnSurfaceFaint,
                                modifier = Modifier.size(20.dp)
                            )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            item.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                )
            }

            AnimatedVisibility(
                visible = state is ModelDownloadState.Downloading
            ) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    val progress = (state as? ModelDownloadState.Downloading)?.progress ?: 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape),
                        color = Violet,
                        trackColor = SurfaceVariant,
                    )
                }
            }

            (state as? ModelDownloadState.Error)?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = it.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = Error,
                )
            }

            Spacer(Modifier.height(16.dp))
            ModelActionButton(
                state = state,
                onDownload = onDownload,
                onLoad = onLoad,
            )
        }
    }
}

@Composable
private fun ModelActionButton(
    state: ModelDownloadState,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
) {
    when (state) {
        is ModelDownloadState.NotDownloaded ->
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet),
            ) {
                Icon(
                    painterResource(R.drawable.cloud_24px),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Download", fontWeight = FontWeight.SemiBold)
            }

        is ModelDownloadState.Downloaded ->
            Button(
                onClick = onLoad,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Violet),
            ) {
                Icon(
                    painterResource(R.drawable.play_arrow_24px),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Load model", fontWeight = FontWeight.SemiBold)
            }

        is ModelDownloadState.Error ->
            OutlinedButton(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                border = BorderStroke(1.dp, Error.copy(alpha = 0.5f)),
            ) {
                Text("Retry")
            }

        is ModelDownloadState.Downloading ->
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Please wait…")
            }
    }
}
