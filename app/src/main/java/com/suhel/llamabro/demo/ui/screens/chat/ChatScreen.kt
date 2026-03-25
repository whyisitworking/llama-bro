package com.suhel.llamabro.demo.ui.screens.chat

import android.content.ClipData
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.halilibo.richtext.commonmark.CommonMarkdownParseOptions
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText
import com.suhel.llamabro.demo.R
import com.suhel.llamabro.demo.model.MessageRole
import com.suhel.llamabro.demo.ui.AppScaffold
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    AppScaffold(
        title = "Chat",
        modifier = Modifier.keepScreenOn(),
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
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
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
            configFlow = viewModel.inputConfig,
            onStartGeneration = viewModel::sendMessage,
            onStopGeneration = viewModel::stopGeneration,
        )
    }
}

@Composable
private fun InputBar(
    configFlow: StateFlow<UiChatInputConfig>,
    onStartGeneration: (String, Boolean) -> Unit,
    onStopGeneration: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var inputText by remember("input_text") { mutableStateOf("") }
        var thinkingEnabled by remember("thinking_enabled") { mutableStateOf(false) }
        val config by configFlow.collectAsStateWithLifecycle()

        TextField(
            value = inputText,
            onValueChange = { inputText = it },
            maxLines = 8,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Talk with Llama Bro") },
            shape = RoundedCornerShape(32.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                showKeyboardOnFocus = true,
            )
        )

        if (config.thinkingSupported) {
            IconButton(
                onClick = { thinkingEnabled = !thinkingEnabled }
            ) {
                Icon(
                    painter = painterResource(
                        if (thinkingEnabled) {
                            R.drawable.cognition_filled_24
                        } else {
                            R.drawable.cognition_24
                        }
                    ),
                    contentDescription = "Thinking",
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        IconButton(
            onClick = {
                if (config.isGenerating) {
                    onStopGeneration()
                } else {
                    val text = inputText
                    inputText = ""
                    onStartGeneration(text, thinkingEnabled)
                }
            },
            enabled = config.isGenerating || inputText.isNotBlank(),
        ) {
            Icon(
                painter = painterResource(
                    if (config.isGenerating) {
                        R.drawable.stop_circle_24
                    } else {
                        R.drawable.arrow_circle_up_24
                    }
                ),
                contentDescription = "send-stop",
                modifier = Modifier.size(40.dp),
            )
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (message.isProcessing && message.content.isNullOrBlank() && message.thinking.isNullOrBlank()) {
                ProcessingIndicator()
            }

            message.thinking?.let { thinkingText ->
                ExpandableThinkingBlock(thinkingText = thinkingText)
            }

            message.content?.let { contentText ->
                if (isUser) {
                    UserMessageContent(contentText = contentText)
                } else {
                    AssistantMessageContent(contentText = contentText)
                    if (message.tokensPerSecond != null) {
                        GenerationConclusion(
                            text = contentText,
                            tokensPerSecond = message.tokensPerSecond
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingIndicator() {
    Text(
        text = "Processing...",
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ExpandableThinkingBlock(thinkingText: String) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Thinking",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                painter = painterResource(R.drawable.keyboard_arrow_down_24),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = "chevron-thinking",
                modifier = Modifier.rotate(if (isExpanded) 180f else 0f)
            )
        }

        if (isExpanded) {
            MarkdownRenderer(thinkingText)
        }
    }
}

@Composable
private fun UserMessageContent(contentText: String) {
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .background(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.shapes.medium
            )
            .padding(16.dp)
    ) {
        Text(
            text = contentText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun AssistantMessageContent(contentText: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        MarkdownRenderer(contentText)
    }
}

@Composable
private fun GenerationConclusion(text: String, tokensPerSecond: Float) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(
                            ClipData.newPlainText("Llama Bro", text)
                        )
                    )
                }
            },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.content_copy_24),
                contentDescription = "Copy text",
                modifier = Modifier.size(20.dp)
            )
        }

        Text(
            text = "%.1f tok/s".format(tokensPerSecond),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MarkdownRenderer(content: String) {
    RichText(
        modifier = Modifier.fillMaxWidth()
    ) {
        Markdown(content)
    }
}
