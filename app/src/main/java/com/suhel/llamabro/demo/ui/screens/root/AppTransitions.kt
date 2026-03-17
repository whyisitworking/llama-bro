package com.suhel.llamabro.demo.ui.screens.root

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.navigation.NavBackStackEntry

object AppTransitions {
    // Standard Fade
    val fadeEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = tween(300))
    }

    val fadeExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = tween(300))
    }

    // Scale & Fade (Great for app startup or modal reveals)
    val scaleInEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        scaleIn(
            initialScale = 0.95f,
            animationSpec = tween(400, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(400))
    }

    val scaleOutExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        scaleOut(
            targetScale = 0.95f,
            animationSpec = tween(400, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(400))
    }

    // Lateral Slide (Standard screen forward movement)
    val slideInForward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        )
    }

    val slideOutBackward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        )
    }

    // Parallax Slide (The underlying screen moving slightly)
    val parallaxOutForward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                targetOffset = { it / 4 }
            ) + fadeOut(animationSpec = tween(300))
        }

    val parallaxInBackward: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                initialOffset = { it / 4 }
            ) + fadeIn(animationSpec = tween(300))
        }
}
