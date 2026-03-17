package com.suhel.llamabro.demo.ui.screens.root

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
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

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        NavHost(
            navController = navController,
            startDestination = ModelSelection,
            modifier = modifier,
            enterTransition = { fadeIn(animationSpec = tween(300)) },
            exitTransition = { fadeOut(animationSpec = tween(300)) }
        ) {
            modelSelectionGraph(
                onModelReady = {
                    navController.navigate(Conversations) {
                        popUpTo(0)
                    }
                }
            )

            conversationsGraph(
                onOpenChat = { conversationId ->
                    navController.navigate(Chat(conversationId))
                }
            )

            chatGraph(
                onBack = { navController.popBackStack() }
            )
        }
    }
}

private fun NavGraphBuilder.modelSelectionGraph(onModelReady: () -> Unit) {
    composable<ModelSelection>(
        exitTransition = AppTransitions.scaleOutExit
    ) {
        ModelSelectionScreen(onModelReady = onModelReady)
    }
}

private fun NavGraphBuilder.conversationsGraph(onOpenChat: (String) -> Unit) {
    composable<Conversations>(
        enterTransition = AppTransitions.scaleInEnter,
        exitTransition = AppTransitions.parallaxOutForward,
        popEnterTransition = AppTransitions.parallaxInBackward
    ) {
        ConversationsScreen(onOpenChat = onOpenChat)
    }
}

private fun NavGraphBuilder.chatGraph(onBack: () -> Unit) {
    composable<Chat>(
        enterTransition = AppTransitions.slideInForward,
        popExitTransition = AppTransitions.slideOutBackward
    ) {
        ChatScreen(
            onBack = onBack
        )
    }
}
