package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * How a model runs on this phone, as a word ("Runs great", "Slow here"), in the ink of its [tint] (pd-design 5.2).
 * The app maps its fit label to a [CardTint] (`FitTint` in `:app`) and passes the same tint to the card and the text,
 * so the two can't disagree.
 */
@Composable
fun FitText(label: String, tint: CardTint, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier,
        color = tint.ink(LocalDemoColors.current),
        style = DemoType.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
