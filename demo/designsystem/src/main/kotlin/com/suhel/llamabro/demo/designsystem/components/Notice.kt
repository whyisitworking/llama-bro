package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * An error, an empty list or a search with no results (models-page design §5, "Notices"): one plain bodyMedium line
 * where the content would be, in the muted ink, and its [action] ("Try again", "OK") as a bordered quiet button of the
 * standard height at the line's end, never underlined text: one layout for
 * every notice, so an action never lands after the text's last word on one screen and at the line's end on another.
 * [inset] starts the line at the inner inset, like [SectionLabel] and a list's rows' text; false puts it on the gutter,
 * where a sheet's own lines start. A [failure] notice says its first word in the danger ink ("Couldn't …"). Never
 * the hero serif and never centred in empty space. At large font the action moves under the line,
 * so the words keep the width.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    inset: Boolean = true,
    failure: Boolean = false,
) {
    val colors = LocalDemoColors.current
    val line = @Composable { textModifier: Modifier ->
        Text(
            buildAnnotatedString {
                append(text)
                val word = text.indexOf(' ').takeIf { it > 0 } ?: text.length
                if (failure) addStyle(SpanStyle(color = colors.danger), 0, word)
            },
            textModifier,
            color = colors.muted,
            style = DemoType.bodyMedium,
        )
    }
    val edge = modifier.fillMaxWidth().padding(start = if (inset) DemoSpacing.item else 0.dp)
    when {
        action == null -> {
            // without an action the line is a button's height too, so a notice that gains one doesn't move the content
            Row(edge.heightIn(min = DemoSize.button), verticalAlignment = Alignment.CenterVertically) {
                line(Modifier)
            }
        }

        isLargeText() -> {
            Column(edge, verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline)) {
                line(Modifier)
                QuietButton(action, onAction, bordered = true)
            }
        }

        else -> {
            Row(
                edge.heightIn(min = DemoSize.button),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
            ) {
                line(Modifier.weight(1f))
                QuietButton(action, onAction, bordered = true)
            }
        }
    }
}

/**
 * A short state on a row or a card ("On this phone", "Default", "Recommended"): [DemoType.labelSmall] in the accent on
 * the soft accent, one line that ellipsises. Tonal, never a solid accent fill: the solid fill is the primary action's.
 */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier) {
    val colors = LocalDemoColors.current
    Text(
        text,
        modifier
            .background(colors.accentSoft, CircleShape)
            .heightIn(min = DemoSize.tag)
            .padding(horizontal = DemoSpacing.block)
            .wrapContentHeight(Alignment.CenterVertically),
        color = colors.accent,
        style = DemoType.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
