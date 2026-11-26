package com.suhel.llamabro.demo.ui.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.suhel.llamabro.demo.designsystem.theme.DemoTheme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.server.PairingRequest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Server page as its previews show it (docs/design/server.md §6). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ServerScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun approvalDialogShowsCodeAndClient() {
        val mac = PairingRequest("4821", "192.168.1.12", null)
        val answers = mutableListOf<Boolean>()
        rule.setContent {
            DemoTheme(ThemeMode.Light) {
                PairingDialog(Approval("4821", "192.168.1.12 · Mac · Chrome", mac), { answers += true }, {
                    answers += false
                })
            }
        }
        rule.onNodeWithTag("pairing_code").assertTextEquals("4821")
        rule.onNodeWithText("192.168.1.12 · Mac · Chrome", substring = true).assertExists()
        rule.onNodeWithText("Approve").performClick()
        rule.onNodeWithText("Deny").performClick()
        assertEquals(listOf(true, false), answers)
    }

    @Test fun noQrOnTheServerPage() {
        // a laptop can't scan one: the Address section is the URLs with Copy, and nothing else
        val serving = ServerStates().values.filter { it.page is ServerPage.Serving }.toList()
        var shown by mutableStateOf(serving.first())
        rule.setContent { DemoTheme(ThemeMode.Light) { ServerContent(shown.page, ServerActions(), shown.tab, {}) } }
        serving.forEach { preview ->
            shown = preview
            rule.waitForIdle()
            rule.onNodeWithTag("server_page").assertExists()
            rule.onNodeWithTag("server_qr").assertDoesNotExist()
            rule.onNodeWithTag("server_qr_hidden").assertDoesNotExist()
            rule.onAllNodesWithText("QR", substring = true).assertCountEquals(0)
        }
    }
}
