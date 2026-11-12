package com.suhel.llamabro.demo.ui.firstrun

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suhel.llamabro.demo.designsystem.components.DemoDialog
import com.suhel.llamabro.demo.designsystem.components.DialogCard
import com.suhel.llamabro.demo.designsystem.theme.DemoSpacing
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

internal const val WHATS_A_MODEL_TITLE = "What's a model?"
internal const val WHATS_A_MODEL_TEXT =
    "A model is the mind that answers you. It's a file that lives on this phone, so what you say stays here.\n\n" +
        "Bigger ones think better but need more room and take longer to answer."

/** The two plain lines behind "What's a model?": not a tutorial. */
@Composable
fun WhatsAModelDialog(onDismiss: () -> Unit) {
    DemoDialog(
        title = WHATS_A_MODEL_TITLE,
        text = WHATS_A_MODEL_TEXT,
        confirmLabel = "Got it",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    )
}

@DemoPreviews
@Composable
internal fun WhatsAModelPreview() {
    PreviewFrame {
        DialogCard(WHATS_A_MODEL_TITLE, WHATS_A_MODEL_TEXT, "Got it", {}, null, {}, Modifier)
    }
}
