package com.suhel.llamabro.demo.ui.screens.conversations

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.suhel.llamabro.demo.R
import com.suhel.llamabro.demo.model.Conversation
import com.suhel.llamabro.demo.ui.AppScaffold
import com.suhel.llamabro.demo.ui.theme.Error
import com.suhel.llamabro.demo.ui.theme.OnSurface
import com.suhel.llamabro.demo.ui.theme.OnSurfaceFaint
import com.suhel.llamabro.demo.ui.theme.OnSurfaceMuted
import com.suhel.llamabro.demo.ui.theme.Surface
import com.suhel.llamabro.demo.ui.theme.SurfaceBorder
import com.suhel.llamabro.demo.ui.theme.SurfaceVariant
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenChat: (conversationId: String?) -> Unit,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val conversations = viewModel.conversations.collectAsLazyPagingItems()

    AppScaffold(
        title = "Conversations",
        floatingActionButton = {
            NewConversationFab(
                onClick = { onOpenChat(null) }
            )
        }
    ) {
        if (conversations.itemCount == 0) {
            EmptyConversationsState()
        } else {
            ConversationsList(
                conversations = conversations,
                onOpenChat = onOpenChat,
                onDelete = { conversationId -> viewModel.deleteConversation(conversationId) }
            )
        }
    }
}

@Composable
private fun NewConversationFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.add_24),
            contentDescription = "New conversation"
        )
    }
}

@Composable
private fun EmptyConversationsState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "No conversations yet",
            color = OnSurfaceMuted,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tap + to start chatting",
            color = OnSurfaceFaint,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ConversationsList(
    conversations: LazyPagingItems<Conversation>,
    onOpenChat: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(
            count = conversations.itemCount,
            key = { idx -> conversations[idx]?.id ?: idx }
        ) { idx ->
            conversations[idx]?.let { conversation ->
                ConversationCard(
                    conversation = conversation,
                    onClick = { onOpenChat(conversation.id) },
                    onDelete = { onDelete(conversation.id) },
                )
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ConversationCardContent(
        conversation = conversation,
        onClick = onClick,
        onLongClick = { showDeleteDialog = true },
        onDeleteClick = { showDeleteDialog = true }
    )

    if (showDeleteDialog) {
        DeleteConversationDialog(
            onConfirm = {
                onDelete()
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCardContent(
    conversation: Conversation,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, SurfaceBorder),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationDetails(
                title = conversation.title,
                updatedAt = conversation.updatedAt,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onDeleteClick) {
                Icon(
                    painter = painterResource(R.drawable.delete_sweep_24),
                    contentDescription = "Delete",
                    tint = OnSurfaceFaint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun ConversationDetails(
    title: String,
    updatedAt: Long,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = OnSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Active ${formatRelativeTime(updatedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted
        )
    }
}

@Composable
private fun DeleteConversationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete conversation?") },
        text = { Text("This cannot be undone.", color = OnSurfaceMuted) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = Error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = SurfaceVariant,
    )
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
