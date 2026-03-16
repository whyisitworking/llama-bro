package com.suhel.llamabro.demo.ui.screens.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.suhel.llamabro.demo.R
import com.suhel.llamabro.demo.model.MessageRole
import com.suhel.llamabro.demo.ui.AppScaffold
import com.suhel.llamabro.demo.ui.theme.OnSurface
import com.suhel.llamabro.demo.ui.theme.OnSurfaceFaint
import com.suhel.llamabro.demo.ui.theme.SurfaceBorder
import com.suhel.llamabro.demo.ui.theme.Violet
import dev.jeziellago.compose.markdowntext.MarkdownText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    AppScaffold(
        title = "Chat",
        onBack = onBack
    ) {
        val messages = viewModel.messages.collectAsLazyPagingItems()
        val incomingMessage by viewModel.incomingMessage.collectAsStateWithLifecycle()
        val listState = rememberLazyListState()

        // Auto-scroll to bottom when a NEW generation starts
        LaunchedEffect(incomingMessage?.isProcessing) {
            if (incomingMessage?.isProcessing == true) {
                listState.animateScrollToItem(0)
            }
        }

        // When generation ends, the new message is added to 'messages'.
        // We might want to ensure we stay at the bottom.
        LaunchedEffect(messages.itemCount) {
            if (listState.firstVisibleItemIndex <= 1) {
                listState.animateScrollToItem(0)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            reverseLayout = true
        ) {
            incomingMessage?.let { safeIncomingMessage ->
                item(key = "streaming") {
                    MessageBubble(safeIncomingMessage)
                }
            }

            items(
                count = messages.itemCount,
                key = { idx -> messages[idx]?.id ?: idx }
            ) { idx ->
                messages[idx]?.let { msg ->
                    MessageBubble(msg)
                }
            }
        }

        InputBar(
            isGenerating = incomingMessage != null,
            onStartGeneration = viewModel::sendMessage,
            onStopGeneration = viewModel::stopGeneration,
        )
    }
}

@Composable
private fun InputBar(
    isGenerating: Boolean,
    onStartGeneration: (String) -> Unit,
    onStopGeneration: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var inputText by remember { mutableStateOf("") }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Message", color = OnSurfaceFaint) },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Violet,
                    unfocusedBorderColor = SurfaceBorder,
                    cursorColor = Violet,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                ),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences
                )
            )

            IconButton(
                onClick = {
                    if (isGenerating) {
                        onStopGeneration()
                    } else if (inputText.isNotBlank()) {
                        val text = inputText
                        inputText = ""
                        onStartGeneration(text)
                    }
                }
            ) {
                AnimatedContent(
                    targetState = isGenerating,
                    label = "send-stop"
                ) { generating ->
                    if (generating) {
                        Icon(
                            painter = painterResource(R.drawable.stop_circle_24px),
                            contentDescription = "Stop"
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.arrow_circle_up_24px),
                            contentDescription = "Send"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: UiChatMessage) {
    val isUser = message.role == MessageRole.User

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = if (isUser) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (message.isProcessing && (message.content.isNullOrBlank() && message.thinking.isNullOrBlank())) {
                Text(
                    text = "Processing...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            message.thinking?.let { thinkingText ->
                Text(
                    text = thinkingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primaryFixedDim,
                )
            }

            message.content?.let { contentText ->
                if (isUser) {
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.shapes.medium
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            text = contentText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                } else {
                    MarkdownText(
                        markdown = contentText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (!isUser && message.tokensPerSecond != null) {
                Text(
                    text = "%.1f tok/s".format(message.tokensPerSecond),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
