package com.suhel.llamabro.demo.ui.screens.root

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.suhel.llamabro.demo.navigation.Chat
import com.suhel.llamabro.demo.navigation.Conversations
import com.suhel.llamabro.demo.navigation.ModelSelection
import com.suhel.llamabro.demo.ui.screens.chat.ChatScreen
import com.suhel.llamabro.demo.ui.screens.conversations.ConversationsScreen
import com.suhel.llamabro.demo.ui.screens.models.ModelSelectionScreen

import androidx.compose.ui.Modifier

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = ModelSelection,
        modifier = modifier
    ) {
        composable<ModelSelection> {
            ModelSelectionScreen(
                onModelReady = {
                    navController.navigate(Conversations)
                }
            )
        }

        composable<Conversations> {
            ConversationsScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { conversationId ->
                    navController.navigate(Chat(conversationId))
                }
            )
        }

        composable<Chat> {
            ChatScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
