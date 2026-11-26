package com.suhel.llamabro.demo.ui.server

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.suhel.llamabro.demo.designsystem.components.DemoDialog
import com.suhel.llamabro.demo.designsystem.components.DialogCard
import com.suhel.llamabro.demo.designsystem.theme.DemoType
import com.suhel.llamabro.demo.designsystem.theme.LocalDemoColors

/**
 * "Pair a computer?" (docs/design/server.md §6, D174): a computer's browser asks for the key, and the person checks
 * that its page shows the same [Approval.code] before Approve. Deny, Back and a tap outside all deny; so does no
 * answer within two minutes, when the server withdraws the question and the dialog goes.
 */
@Composable
fun PairingDialog(approval: Approval, onApprove: () -> Unit, onDeny: () -> Unit) {
    DemoDialog(
        PAIRING_TITLE,
        pairingText(approval.client),
        confirmLabel = "Approve",
        onConfirm = onApprove,
        onDismiss = onDeny,
        dismissLabel = "Deny",
    ) { PairingCode(approval.code) }
}

/** The dialog's card alone, for previews, where a window can't be drawn. */
@Composable
fun PairingCard(approval: Approval, modifier: Modifier = Modifier) {
    DialogCard(
        PAIRING_TITLE,
        pairingText(approval.client),
        confirmLabel = "Approve",
        onConfirm = {},
        onDismiss = {},
        modifier = modifier,
        dismissLabel = "Deny",
    ) { PairingCode(approval.code) }
}

/** The code as large as the docked numbers: it is read across from a laptop's screen. */
@Composable
private fun PairingCode(code: String) {
    Text(
        code,
        color = LocalDemoColors.current.ink,
        style = DemoType.glance,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("pairing_code"),
    )
}

internal const val PAIRING_TITLE = "Pair a computer?"

internal fun pairingText(client: String) =
    "$client asks for this phone's key. Approve only if its page shows this code."
