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

private val LightStops =
    arrayOf(
        0.00f to Color(0xFFFFFFFF),
        0.26f to Color(0xFFF3F1EC),
        0.58f to Color(0xFFDCE1EA),
        0.82f to Color(0xFFC9CFDB),
        1.00f to Color(0xFFE9DFD2),
    )
private val DarkStops =
    arrayOf(
        0.00f to Color(0xFFF4F3F0),
        0.30f to Color(0xFFD6D9E0),
        0.66f to Color(0xFF9DA6B9),
        1.00f to Color(0xFF5D667C),
    )

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
 * The pearl "moonstone": a radial gradient centred at (34%, 28%), a soft shadow tinted with the accent. 56 dp on the
 * first run, 40 dp in an empty chat.
 */
@Composable
fun Orb(size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    // The mockups' accent-tinted shadow (rgba(37,48,77,.16) light, rgba(185,196,222,.18) dark), raised to 0.22/0.35
    // because Android's shadow is fainter than CSS's at the same alpha; elevation = a fifth of the size. Builder's
    // call,
    // settled here.
    val tint = colors.accent.copy(alpha = if (colors.isDark) 0.35f else 0.22f)
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
                colorStops = if (colors.isDark) DarkStops else LightStops,
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
