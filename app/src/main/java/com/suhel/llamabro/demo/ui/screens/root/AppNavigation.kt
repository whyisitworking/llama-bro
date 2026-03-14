package com.suhel.llamabro.demo.ui.screens.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.suhel.llamabro.demo.navigation.Chat
import com.suhel.llamabro.demo.navigation.Conversations
import com.suhel.llamabro.demo.navigation.ModelSelection
import com.suhel.llamabro.demo.ui.screens.chat.ChatScreen
import com.suhel.llamabro.demo.ui.screens.conversations.ConversationsScreen
import com.suhel.llamabro.demo.ui.screens.models.ModelSelectionScreen

@Composable
fun AppNavigation(
    state: RootUiState,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()

    LaunchedEffect(state) {
        if (state is RootUiState.NoModelLoaded) {
            navController.navigate(ModelSelection) {
                popUpTo(0)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = ModelSelection,
        modifier = modifier
    ) {
        composable<ModelSelection> {
            ModelSelectionScreen(
                onModelReady = {
                    navController.navigate(Conversations) {
                        popUpTo(0)
                    }
                }
            )
        }

        composable<Conversations> {
            ConversationsScreen(
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
