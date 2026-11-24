package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * A small uppercase section heading ("Picked for your phone", "Today"), with an optional link at the end ("What's a
 * model?") or a muted [note] there instead ("6.1 GB · 120 GB free", what the section's models take). It is inset by the
 * cards' and rows' own padding ([DemoSpacing.item]), on a screen and on a sheet alike, so it starts where the text of
 * the cards and rows under it starts, not on the edge of their surface; [inset] false puts it on the gutter, where a
 * sheet's own lines start (a [RuledLabel] over a page). At large font ([isLargeText]) the heading wraps
 * and the action or the note moves under it, so the heading is never the part that is cut.
 * [quiet] is the Models page's label (D123): sentence case, Regular, soft, with no tracking.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    note: String? = null,
    inset: Boolean = true,
    quiet: Boolean = false,
) {
    val colors = LocalDemoColors.current
    val edge = modifier.fillMaxWidth().padding(start = if (inset) DemoSpacing.item else 0.dp)
    val heading = if (quiet) text else text.uppercase()
    val headingInk = if (quiet) colors.soft else colors.muted
    val headingStyle = if (quiet) DemoType.quietLabel else DemoType.labelSmall
    val noteInk = if (quiet) colors.soft else colors.muted
    if (isLargeText()) {
        Column(edge) {
            Text(heading, color = headingInk, style = headingStyle)
            if (action != null) QuietButton(text = action, onClick = onAction ?: {})
            if (note != null) Text(note, color = noteInk, style = DemoType.bodySmall)
        }
        return
    }
    Row(edge, verticalAlignment = Alignment.CenterVertically) {
        Text(
            heading,
            Modifier.weight(1f),
            color = headingInk,
            style = headingStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // with an action the row is the action's 48 dp, like Material's list subheader: a target that reaches past
        // its row is cut by the parent's bounds, and accessibility services see only what's left (F130)
        if (action != null) QuietButton(text = action, onClick = onAction ?: {})
        // the note ends where the rows' text ends, inset like the heading
        if (note != null) {
            Text(
                note,
                Modifier.padding(start = DemoSpacing.block, end = DemoSpacing.item),
                color = noteInk,
                style = DemoType.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A muted line under a section's cards ("Changing this reloads the model."): inset like [SectionLabel], so it starts on
 * the text of the cards above it, not on the edge of their surface. With an [action] ("Reset") the line is a touch
 * target tall and the action, a quiet underlined word, ends it; the words wrap before the action gives way.
 */
@Composable
fun SectionNote(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val line = @Composable { textModifier: Modifier ->
        Text(text, textModifier, color = LocalDemoColors.current.muted, style = DemoType.bodySmall)
    }
    if (action == null) {
        line(modifier.padding(start = DemoSpacing.item))
        return
    }
    Row(
        modifier.fillMaxWidth().padding(start = DemoSpacing.item).heightIn(min = DemoSize.target),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
    ) {
        line(Modifier.weight(1f))
        QuietButton(action, onAction)
    }
}

/**
 * Where what follows comes from ("From the model's page on Hugging Face"): a [SectionLabel] over a hairline, so the
 * author's words below don't read as the app's own. Both start on the gutter, as every other line of
 * a sheet does, one inset.
 */
@Composable
fun RuledLabel(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DemoSpacing.inline)) {
        SectionLabel(text, inset = false)
        Box(Modifier.fillMaxWidth().height(DemoSize.hairline).background(LocalDemoColors.current.hairline))
    }
}
