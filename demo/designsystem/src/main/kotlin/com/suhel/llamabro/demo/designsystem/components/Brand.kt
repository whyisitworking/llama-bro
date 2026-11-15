package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors
import kotlin.math.hypot

/** The icon's warmth: amber at the lit point falling to rose at the far edge, the same in both themes. */
private val Amber = Color(0xFFFDB969)
private val Rose = Color(0xFFC35F5A)
private val Stops = arrayOf(0.00f to Amber, 1.00f to Rose)

/** The two sizes the orb is drawn at. */
enum class OrbSize(internal val size: Dp) {
    /** First run's hero. */
    Hero(DemoSize.markHero),

    /** An empty chat. */
    Empty(DemoSize.markEmpty),
}

/** The orb at one of the app's two sizes. */
@Composable
fun Orb(size: OrbSize, modifier: Modifier = Modifier) {
    Orb(size.size, modifier)
}

/**
 * The orb: a radial gradient centred at (34%, 28%), amber falling to rose, with a soft rose shadow. It is the app's
 * only hue outside the fit words and Remove (D69). 56 dp on the first run, 40 dp in an empty chat.
 */
@Composable
fun Orb(size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    // Android's shadow is fainter than CSS's at the same alpha, hence 0.22 light and 0.35 dark; elevation = a fifth of
    // the size.
    val tint = Rose.copy(alpha = if (colors.isDark) 0.35f else 0.22f)
    Canvas(
        modifier
            .size(size)
            .shadow(elevation = size / 5, shape = CircleShape, ambientColor = tint, spotColor = tint),
    ) {
        val center = Offset(this.size.width * 0.34f, this.size.height * 0.28f)
        // CSS's "farthest-corner": the last stop sits at the corner farthest from the centre
        val radius = hypot(this.size.width - center.x, this.size.height - center.y)
        drawCircle(
            Brush.radialGradient(
                colorStops = Stops,
                center = center,
                radius = radius,
            ),
        )
    }
}

/** The serif greeting of the first run ("Good evening"), and the serif titles of whole screens ("Chats"). */
@Composable
fun Greeting(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Center) {
    Text(text, modifier, color = LocalDemoColors.current.ink, style = DemoType.displayHero, textAlign = textAlign)
}

/** The serif line of an empty state ("What's on your mind?"). */
@Composable
fun EmptyLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = LocalDemoColors.current.ink,
        style = DemoType.displayHero,
        textAlign = TextAlign.Center,
    )
}

/**
 * A model's name in serif, with its real name in sans under it when the name is a friendly one ("Swift" over "Qwen3.5
 * 0.8B").
 */
@Composable
fun ModelName(name: String, modifier: Modifier = Modifier, realName: String? = null, centered: Boolean = false) {
    val colors = LocalDemoColors.current
    Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(name, color = colors.ink, style = DemoType.sheetTitle)
        if (realName != null) Text(realName, color = colors.muted, style = DemoType.bodyMedium)
    }
}
