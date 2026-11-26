package com.suhel.llamabro.demo.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.suhel.llamabro.demo.designsystem.components.DialogCard
import com.suhel.llamabro.demo.designsystem.components.gutterPadding
import com.suhel.llamabro.demo.server.LogLine
import com.suhel.llamabro.demo.server.Thermal
import com.suhel.llamabro.demo.ui.chat.STOP_SERVING_TEXT
import com.suhel.llamabro.demo.ui.chat.stopServingTitle
import com.suhel.llamabro.demo.ui.preview.DemoPreviews
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame
import com.suhel.llamabro.hub.HfModel
import java.time.ZoneOffset
import java.util.Locale

// The Server page in each state a person can find it in (docs/design/server.md §3), and its two questions.

private val qwen = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M")
private val gemma = HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M")
private const val URL = "http://192.168.1.20:8080"
private const val KEY = "q8Vw3nX0dJk2LmP5sT7uY9zA1bC4eF6gH8iK0lM2nO4"
private const val NOON = 1_795_000_000_000L

private val models =
    listOf(
        ServeChoice(qwen, "Qwen3.5 4B", "Q4_K_M · 2.7 GB"),
        ServeChoice(gemma, "Gemma 3 4B", "Q4_K_M · 3.3 GB"),
    )

private val log =
    listOf(
        LogLine(NOON, "192.168.1.12", "POST", "/v1/messages", 200, 14_100, 1_912, 233),
        LogLine(NOON - 40_000, "192.168.1.12", "POST", "/v1/chat/completions", 200, 3_200, 41, 58),
        LogLine(NOON - 60_000, "192.168.1.31", null, "/v1/models", 401, null, null, null),
    ).map { ServerFormat.logRow(it, ZoneOffset.UTC, Locale.US) }

private fun serving(
    revealed: Boolean = false,
    urls: List<String> = listOf(URL),
    charging: Boolean = true,
    rows: List<LogRow> = log,
): ServerPage.Serving {
    val shown = if (revealed) KEY else ServerFormat.MASK
    val first = urls.firstOrNull()
    val snippets =
        first
            ?.let { url ->
                ConnectSnippets
                    .of(
                        url,
                        shown,
                        qwen.toString(),
                    ).zip(ConnectSnippets.of(url, KEY, qwen.toString())) { s, c ->
                        SnippetView(s.tool, s.code, c.code)
                    }
            }.orEmpty()
    return ServerPage.Serving(
        name = "Qwen3.5 4B",
        urls = urls,
        qr = first?.takeIf { revealed }?.let { ConnectSnippets.qrPayload(it, KEY) },
        canShowQr = first != null,
        key = KeyView(shown, KEY, revealed),
        slots = 2,
        busy = if (rows.isEmpty()) 0 else 1,
        speed = if (rows.isEmpty()) null else "16.5 tok/s",
        heat = ServerFormat.heat(Thermal(1, 0.62f)),
        charging = charging,
        log = rows,
        snippets = snippets,
    )
}

internal data class ServerPreview(val name: String, val page: ServerPage, val tab: Int = 0) {
    override fun toString() = name
}

internal class ServerStates : PreviewParameterProvider<ServerPreview> {
    override val values =
        sequenceOf(
            ServerPreview("Off", ServerPage.Off(models, gemma, 1)),
            ServerPreview("Off, no models", ServerPage.Off(emptyList(), null, 1)),
            ServerPreview("Starting", ServerPage.Starting("Qwen3.5 4B", 0.4f)),
            ServerPreview("Serving, key masked", serving()),
            ServerPreview("Serving, key and QR code shown", serving(revealed = true)),
            ServerPreview("Serving, key shown, Claude Code", serving(revealed = true, rows = emptyList()), tab = 2),
            ServerPreview("Serving, no Wi-Fi, not charging", serving(urls = emptyList(), charging = false)),
            ServerPreview("Failed", ServerPage.Failed("Address already in use")),
        )
}

@DemoScreenPreviews
@Composable
internal fun ServerContentPreview(
    @PreviewParameter(ServerStates::class) p: ServerPreview,
) {
    PreviewFrame { ServerContent(p.page, ServerActions(onBack = {}), p.tab, onTab = {}) }
}

@DemoPreviews
@Composable
internal fun NewKeyDialogPreview() {
    PreviewFrame { DialogCard(NEW_KEY_TITLE, NEW_KEY_TEXT, "New key", {}, {}, Modifier.gutterPadding(), "Cancel") }
}

@DemoPreviews
@Composable
internal fun StopServingDialogPreview() {
    PreviewFrame {
        DialogCard(
            stopServingTitle("Qwen3.5 4B", "Gemma 3 4B"),
            STOP_SERVING_TEXT,
            "Stop serving",
            {},
            {},
            Modifier.gutterPadding(),
            "Cancel",
        )
    }
}
