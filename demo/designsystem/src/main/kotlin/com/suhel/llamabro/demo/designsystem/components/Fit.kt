package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * How a model runs on this phone, as a word ("Runs great", "Slow here"), in the ink of its [tint] (demo design §8.6).
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

/**
 * A model as a card, the same anatomy wherever one is offered (first run, Get models): the [name] in `title` with how
 * it runs here ([fit] in its tint's ink) at the end of the same line; the plain [line] under it in muted `bodyMedium`;
 * then, quietly, [facts] in `bodySmall` (size, "Thinks", "Sees images", "On this phone", downloads); and an optional
 * [footer] a block below (a download's progress). [fit] null leaves the card plain until the fit arrives.
 */
@Composable
fun ModelCard(
    name: String,
    line: String,
    modifier: Modifier = Modifier,
    fit: Pair<String, CardTint>? = null,
    facts: String? = null,
    onClick: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val colors = LocalDemoColors.current
    DemoCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(DemoSpacing.block)) {
            Column(verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        Modifier.weight(1f),
                        color = colors.ink,
                        style = DemoType.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (fit != null) {
                        Spacer(Modifier.width(DemoSpacing.block))
                        FitText(fit.first, fit.second)
                    }
                }
                Text(line, color = colors.muted, style = DemoType.bodyMedium)
                if (facts != null) {
                    Text(
                        facts,
                        color = colors.muted,
                        style = DemoType.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            footer?.invoke()
        }
    }
}
