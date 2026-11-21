package com.suhel.llamabro.demo.ui.models

import androidx.compose.runtime.Composable
import com.suhel.llamabro.demo.designsystem.components.DemoDialog
import com.suhel.llamabro.demo.designsystem.components.DialogCard
import com.suhel.llamabro.demo.designsystem.components.FitKey
import com.suhel.llamabro.demo.designsystem.components.FitLevel
import com.suhel.llamabro.demo.models.FitLabel
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame

internal const val WHATS_A_MODEL_TITLE = "What's a model?"
internal const val WHATS_A_MODEL_TEXT =
    "A model is the mind that answers you. It's a file that lives on this phone, so what you say stays here.\n\n" +
        "Bigger ones think better but need more room and take longer to answer. The marks on each model say how it " +
        "runs here:"

/** The five fit symbols in their order, each with the words the card's header says (models-page design §6). */
internal val fitKey: List<Pair<FitLevel, String>> =
    FitLabel.entries.map { it.level() to it.text() } + (FitLevel.CantChat to CANT_CHAT)

/**
 * The two plain lines behind "What's a model?", not a tutorial, then the five fit symbols with their words: the rows
 * show the symbols alone (D123), so they are taught where the newcomer asks.
 */
@Composable
fun WhatsAModelDialog(onDismiss: () -> Unit) {
    DemoDialog(
        title = WHATS_A_MODEL_TITLE,
        text = WHATS_A_MODEL_TEXT,
        confirmLabel = "Got it",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        FitKey(fitKey)
    }
}

@DemoPreviews
@Composable
internal fun WhatsAModelPreview() {
    PreviewFrame {
        DialogCard(WHATS_A_MODEL_TITLE, WHATS_A_MODEL_TEXT, "Got it", {}, null, {}) { FitKey(fitKey) }
    }
}
