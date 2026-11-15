package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.suhel.llamabro.demo.chat.InMemory
import com.suhel.llamabro.demo.chat.Knob
import com.suhel.llamabro.demo.chat.Knobs
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.KeepLoaded
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.LibraryModel
import com.suhel.llamabro.demo.ui.preview.DemoScreenPreviews
import com.suhel.llamabro.demo.ui.preview.PreviewFrame
import com.suhel.llamabro.hub.HfModel

// Previews of the settings pages, each in the states a user can find it in.

@DemoScreenPreviews
@Composable
internal fun SettingsHomePreview() {
    PreviewFrame {
        SettingsHome(onBack = {}, onOpen = {}, routes = SettingsRoutes("a", "b", "c", "d", "e", "f", "g", "h"))
    }
}

internal data class AppearancePreview(val name: String, val layout: AppLayout, val theme: ThemeMode) {
    override fun toString() = name
}

internal class AppearanceStates : PreviewParameterProvider<AppearancePreview> {
    override val values =
        sequenceOf(
            AppearancePreview("Sidebar, system theme", AppLayout.Sidebar, ThemeMode.System),
            AppearancePreview("Chat list, dark theme", AppLayout.ChatList, ThemeMode.Dark),
        )
}

@DemoScreenPreviews
@Composable
internal fun AppearanceContentPreview(
    @PreviewParameter(AppearanceStates::class) p: AppearancePreview,
) {
    PreviewFrame { AppearanceContent(p.layout, p.theme, onLayout = {}, onTheme = {}, onBack = {}) }
}

internal class InstructionsStates : PreviewParameterProvider<Pair<String, String>> {
    override val values =
        sequenceOf(
            "" to "",
            "I'm a product designer in Lisbon. I prefer metric units." to "Short and plain, no lists unless I ask.",
        )
}

@DemoScreenPreviews
@Composable
internal fun InstructionsContentPreview(
    @PreviewParameter(InstructionsStates::class) p: Pair<String, String>,
) {
    PreviewFrame { InstructionsContent(p.first, p.second, onAbout = {}, onStyle = {}, onSave = {}, onBack = {}) }
}

private val models =
    listOf(
        LibraryModel(HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"), "Qwen3.5 0.8B", 739_863_648, 0, false),
        LibraryModel(
            HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M"),
            "Gemma 3 4B",
            3_341_145_472,
            0,
            true,
        ),
    )

internal class DefaultModelStates : PreviewParameterProvider<String?> {
    override val values = sequenceOf(null, models[1].key.toString())
}

@DemoScreenPreviews
@Composable
internal fun DefaultModelContentPreview(
    @PreviewParameter(DefaultModelStates::class) pinned: String?,
) {
    PreviewFrame { DefaultModelContent(pinned, models, onPin = {}, onBack = {}) }
}

internal data class TokenPreview(val name: String, val hasToken: Boolean, val text: String) {
    override fun toString() = name
}

internal class TokenStates : PreviewParameterProvider<TokenPreview> {
    override val values =
        sequenceOf(
            TokenPreview("No token", hasToken = false, text = ""),
            TokenPreview("Typing a token", hasToken = false, text = "hf_abc123"),
            TokenPreview("A token is saved", hasToken = true, text = ""),
        )
}

@DemoScreenPreviews
@Composable
internal fun TokenContentPreview(
    @PreviewParameter(TokenStates::class) p: TokenPreview,
) {
    PreviewFrame { TokenContent(p.hasToken, p.text, onText = {}, onSave = {}, onRemove = {}, onBack = {}) }
}

@DemoScreenPreviews
@Composable
internal fun AboutPreview() {
    PreviewFrame { AboutContent("1.0", onBack = {}) }
}

internal data class MemoryPreview(
    val name: String,
    val inMemory: InMemory?,
    val replying: Boolean,
    val keep: KeepLoaded,
) {
    override fun toString() = name
}

internal class MemoryStates : PreviewParameterProvider<MemoryPreview> {
    private val qwen = InMemory("Qwen3.5 4B", "2.8 GB · NPU")
    override val values =
        sequenceOf(
            MemoryPreview("Loaded, the default", qwen, replying = false, KeepLoaded.OneMinute),
            MemoryPreview("Writing a reply", qwen, replying = true, KeepLoaded.FiveMinutes),
            MemoryPreview("Nothing loaded", null, replying = false, KeepLoaded.UntilNeeded),
        )
}

@DemoScreenPreviews
@Composable
internal fun MemoryContentPreview(
    @PreviewParameter(MemoryStates::class) p: MemoryPreview,
) {
    PreviewFrame { MemoryContent(p.inMemory, p.replying, p.keep, onUnload = {}, onKeep = {}, onBack = {}) }
}

@DemoScreenPreviews
@Composable
internal fun SamplingModelsPreview() {
    val rows =
        listOf(
            SamplingModelRow(HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"), "Qwen3.5 0.8B", 0),
            SamplingModelRow(HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M"), "Gemma 3 4B", 2),
        )
    PreviewFrame { SamplingModelsContent(rows, onOpen = {}, onBack = {}) }
}

internal data class SamplingPreview(val name: String, val model: String, val repo: String, val own: Knobs) {
    override fun toString() = name
}

internal class SamplingStates : PreviewParameterProvider<SamplingPreview> {
    override val values =
        sequenceOf(
            SamplingPreview("Card values, two modes", "Qwen3.5 0.8B", "unsloth/Qwen3.5-0.8B-GGUF", Knobs.None),
            SamplingPreview(
                "Changed, one mode",
                "Llama 3.2 3B",
                "unsloth/Llama-3.2-3B-Instruct-GGUF",
                Knobs.of(Knob.Temperature to 0.9f),
            ),
            SamplingPreview("Not in the catalog", "SmolLM2 1.7B", "bartowski/SmolLM2-1.7B-Instruct-GGUF", Knobs.None),
        )
}

@DemoScreenPreviews
@Composable
internal fun SamplingModelPreview(
    @PreviewParameter(SamplingStates::class) p: SamplingPreview,
) {
    val layers = SamplingLayers(Catalog.byRepo(p.repo)?.sampling, p.own)
    PreviewFrame { SamplingModelContent(p.model, layers, onChange = { _, _ -> }, onReset = {}, onBack = {}) }
}
