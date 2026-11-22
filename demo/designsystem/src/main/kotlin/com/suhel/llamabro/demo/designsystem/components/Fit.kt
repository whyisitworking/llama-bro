package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * How a model runs on this phone, as a word ("Runs great", "Slow here"), in the ink of its [tint] (demo design §8.6).
 * The app maps its fit label to a [CardTint] (`FitTint` in `:app`) and passes the same tint to the card and the text,
 * so the two can't disagree.
 */
@Composable
fun FitText(label: String, tint: CardTint, modifier: Modifier = Modifier, style: TextStyle = DemoType.label) {
    Text(
        label,
        modifier,
        color = tint.ink(LocalDemoColors.current),
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** How a model runs on this phone, as the figure that stands for it (models-screen design, "The fit symbol"). */
enum class FitLevel { Great, Well, Slow, TooBig, CantChat }

private fun FitLevel.icon(): ImageVector =
    when (this) {
        FitLevel.Great -> DemoIcons.FitGreat
        FitLevel.Well -> DemoIcons.FitWell
        FitLevel.Slow -> DemoIcons.FitSlow
        FitLevel.TooBig -> DemoIcons.FitTooBig
        FitLevel.CantChat -> DemoIcons.NoEntry
    }

private fun FitLevel.ink(colors: DemoColors): Color =
    when (this) {
        FitLevel.Great -> colors.greatInk
        FitLevel.Well -> colors.ink
        FitLevel.Slow -> colors.slowInk
        FitLevel.TooBig, FitLevel.CantChat -> colors.tooBigInk
    }

/**
 * The fit as a pace figure in its ink, [DemoSize.fitSymbol] square, grown with the font up to [DemoSize.fitSymbolMax].
 * [description] ("Runs well") is what a screen reader says and what a long press shows in a tooltip; the symbol carries
 * no words of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitSymbol(level: FitLevel, description: String, modifier: Modifier = Modifier, small: Boolean = false) {
    val ink = level.ink(LocalDemoColors.current)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description, style = DemoType.bodySmall) } },
        state = rememberTooltipState(),
        // the tooltip is for a finger: a screen reader gets the words as a plain description, not as a 20 dp control
        // with a long-press action (which the accessibility check calls a tiny touch target)
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(
            level.icon(),
            contentDescription = null,
            tint = ink,
            modifier =
                Modifier.size(
                    if (small) {
                        scaledWithText(DemoSize.capabilityIcon, DemoSize.capabilityIconMax)
                    } else {
                        scaledWithText(DemoSize.fitSymbol, DemoSize.fitSymbolMax)
                    },
                ),
        )
    }
}

/**
 * A model's capabilities as quiet hints (D123): each icon at [DemoSize.capabilityIcon] (grown with the font) in the
 * light outline weight and the soft ink, a little apart, with no words; then [trailing] (a Hugging Face result's
 * download count), the line's last and quietest item, fading through as it changes. A screen reader hears the icons'
 * words, then [trailing], as one phrase.
 */
@Composable
fun CapabilityIcons(icons: List<Pair<ImageVector, String>>, trailing: String?, modifier: Modifier = Modifier) {
    val spoken = (icons.map { it.second } + listOfNotNull(trailing)).joinToString(", ")
    Row(
        modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
    ) {
        HintIcons(icons)
        if (trailing != null) {
            FadeThrough(trailing) { shown -> QuietItem(shown) }
        }
    }
}

/** The capability icons alone, soft and a little apart. */
@Composable
internal fun HintIcons(icons: List<Pair<ImageVector, String>>) {
    val colors = LocalDemoColors.current
    val iconSize = scaledWithText(DemoSize.capabilityIcon, DemoSize.capabilityIconMax)
    for ((icon, _) in icons) {
        Icon(icon, contentDescription = null, tint = colors.soft, modifier = Modifier.size(iconSize))
    }
}

/** One quiet item at a line's end, after a soft "·" of its own, so the item is its own words. */
@Composable
internal fun QuietItem(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(DemoSpacing.inline)) {
        QuietText(SEPARATOR)
        QuietText(text)
    }
}

private const val SEPARATOR = "·"

/** One quiet item of a line: bodySmall in the soft ink, never wrapping. */
@Composable
internal fun QuietText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = LocalDemoColors.current.soft,
        style = DemoType.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The fit symbols' key: each [FitLevel]'s figure in its ink beside its words ("Runs great"), one per line, where the
 * symbols a row shows alone are taught ("What's a model?", models-page design §6). The words are what a screen reader
 * says; the figure beside them is decoration.
 */
@Composable
fun FitKey(entries: List<Pair<FitLevel, String>>, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DemoSpacing.block)) {
        for ((level, words) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    level.icon(),
                    contentDescription = null,
                    tint = level.ink(colors),
                    modifier = Modifier.size(scaledWithText(DemoSize.fitSymbol, DemoSize.fitSymbolMax)),
                )
                Spacer(Modifier.width(DemoSpacing.block))
                Text(words, color = colors.ink, style = DemoType.body)
            }
        }
    }
}

/**
 * A model card's one quiet line under its name (D123): the [maker], what the model can do as soft icons with no words,
 * and last the [fit] symbol in its colour, the one signal on the line that must be seen. It wraps as words do at large
 * font. A screen reader hears the maker, the capabilities' words and the fit's, as one phrase.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelFactsLine(
    maker: String?,
    capabilities: List<Pair<ImageVector, String>>,
    modifier: Modifier = Modifier,
    fit: FitLevel? = null,
    fitDescription: String = "",
) {
    val colors = LocalDemoColors.current
    val spoken =
        (listOfNotNull(maker) + capabilities.map { it.second } + listOfNotNull(fitDescription.takeIf { fit != null }))
            .joinToString(", ")
    FlowRow(
        // with nothing known yet the line says nothing: an empty label would be a stop a screen reader can't speak
        modifier.fillMaxWidth().clearAndSetSemantics { if (spoken.isNotEmpty()) contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
        verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (maker != null) Text(maker, color = colors.muted, style = DemoType.bodyMedium)
        if (capabilities.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DemoSpacing.inline),
            ) { HintIcons(capabilities) }
        }
        if (fit != null) FitSymbol(fit, fitDescription)
    }
}
