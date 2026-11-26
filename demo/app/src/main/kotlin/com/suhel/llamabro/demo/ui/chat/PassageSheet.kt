package com.suhel.llamabro.demo.ui.chat

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.suhel.llamabro.demo.designsystem.components.DemoSheet
import com.suhel.llamabro.demo.designsystem.components.Gap
import com.suhel.llamabro.demo.designsystem.components.Stack
import com.suhel.llamabro.demo.designsystem.theme.DemoColors
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * A reply's source (docs/design/rag.md): the document's title and the headings above the passage, then the passage
 * highlighted in the text around it, or alone when its document is gone. It scrolls when long.
 */
@Composable
fun PassageSheet(view: PassageView, onDismiss: () -> Unit) {
    DemoSheet(onDismissRequest = onDismiss, expandable = true) { PassageSheetContent(view) }
}

/** The sheet's contents, without the window. */
@Composable
fun ColumnScope.PassageSheetContent(view: PassageView) {
    val colors = LocalDemoColors.current
    Stack(Gap.Inline, Modifier.testTag("passage_sheet")) {
        Text("[${view.number}] ${view.title}", color = colors.ink, style = DemoType.title)
        view.path?.let { Text(it, color = colors.muted, style = DemoType.bodyMedium) }
    }
    val text = remember(view, colors) { highlighted(view, colors) }
    Text(text, Modifier.testTag("passage_text"), color = colors.muted, style = DemoType.body)
}

/** The surroundings in the muted ink, the passage in the ink on the soft accent: what the reply read stands out. */
private fun highlighted(view: PassageView, colors: DemoColors): AnnotatedString =
    buildAnnotatedString {
        append(view.before)
        withStyle(SpanStyle(color = colors.ink, background = colors.accentSoft)) { append(view.passage) }
        append(view.after)
    }
