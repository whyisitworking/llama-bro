package com.suhel.llamabro.demo.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.suhel.llamabro.demo.designsystem.theme.DemoShapes
import com.suhel.llamabro.demo.designsystem.theme.DemoSize
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * A sample of the app's glass to tune it by (Settings › Appearance › Glass, D152), for a screen's bottom bar: a chip
 * beside a panel shaped like the message bar, over the page as it scrolls, so the chips', the panels' and the bar's
 * glass all answer each change at once. Its chip and its panel answer a press as glass does (D155); they do nothing
 * else, being there to be seen and pressed.
 */
@Composable
fun GlassPreview(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DemoSpacing.block),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalGlassChips provides true) {
            IconAction(DemoIcons.Plus, "A chip of glass", onClick = {})
        }
        GlassSurface(Modifier.weight(1f), corner = DemoShapes.composerRadius, onClick = {}) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DemoSize.target)
                    .padding(start = DemoSpacing.item),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "A panel of glass",
                    Modifier.weight(1f),
                    color = LocalDemoColors.current.muted,
                    style = DemoType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconAction(DemoIcons.Send, "A button on the panel", onClick = {})
            }
        }
    }
}
