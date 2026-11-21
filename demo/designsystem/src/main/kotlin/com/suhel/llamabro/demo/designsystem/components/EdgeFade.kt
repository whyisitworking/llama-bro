package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.FadeMask

/**
 * Fades the content's end edge out over [DemoSize.edgeFade] while [shown] (the scroll has more beyond it): the bottom
 * edge, or the end edge when [horizontal]. A wide table cut mid-header, or a page line sitting right on a pinned
 * button, then reads as continuing rather than as ending there.
 */
fun Modifier.edgeFade(shown: () -> Boolean, horizontal: Boolean = false): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (shown()) {
                val fade = DemoSize.edgeFade.toPx()
                val brush =
                    if (horizontal) {
                        Brush.horizontalGradient(listOf(FadeMask, Color.Transparent), size.width - fade, size.width)
                    } else {
                        Brush.verticalGradient(listOf(FadeMask, Color.Transparent), size.height - fade, size.height)
                    }
                drawRect(brush, blendMode = BlendMode.DstIn)
            }
        }
