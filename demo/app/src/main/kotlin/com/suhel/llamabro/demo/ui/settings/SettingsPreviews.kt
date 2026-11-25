package com.suhel.llamabro.demo.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.demo.chat.ChipFallback
import com.suhel.llamabro.demo.chat.InMemory
import com.suhel.llamabro.demo.chat.SamplingLayers
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.data.ContextChoice
import com.suhel.llamabro.demo.data.KeepLoaded
import com.suhel.llamabro.demo.data.ReplyLength
import com.suhel.llamabro.demo.data.VoiceTurns
import com.suhel.llamabro.demo.data.settings.Knob
import com.suhel.llamabro.demo.data.settings.Knobs
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
        SettingsHome(onBack = {}, onOpen = {}, routes = SettingsRoutes("a", "b", "c", "d", "e", "f", "g", "h", "i"))
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
    PreviewFrame { AppearanceContent(p.layout, p.theme, onLayout = {}, onTheme = {}, onBack = {}, onGlass = {}) }
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

internal data class MemoryPreview(val name: String, val state: MemoryState) {
    override fun toString() = name
}

internal class MemoryStates : PreviewParameterProvider<MemoryPreview> {
    private val qwen = InMemory("Qwen3.5 4B", "2.8 GB · NPU · 16k context")
    private val idle = MemoryState(qwen, false, KeepLoaded.OneMinute)
    override val values =
        sequenceOf(
            MemoryPreview("Loaded, the default", idle),
            MemoryPreview("Writing a reply", idle.copy(replying = true, keep = KeepLoaded.FiveMinutes)),
            MemoryPreview("Nothing loaded", idle.copy(inMemory = null, keep = KeepLoaded.UntilNeeded)),
        )
}

@DemoScreenPreviews
@Composable
internal fun MemoryContentPreview(
    @PreviewParameter(MemoryStates::class) p: MemoryPreview,
) {
    PreviewFrame { MemoryContent(p.state, onUnload = {}, onKeep = {}, onBack = {}) }
}

internal data class ProcessorPreview(val name: String, val state: ProcessorState) {
    override fun toString() = name
}

internal class ProcessorStates : PreviewParameterProvider<ProcessorPreview> {
    private val allChips = DeviceKind.entries.toSet()
    private val idle = ProcessorState(ChipChoice.Automatic, allChips, runsOn = null)
    override val values =
        sequenceOf(
            ProcessorPreview("Automatic, the default", idle),
            ProcessorPreview(
                "GPU chosen, fell back to the CPU",
                idle.copy(
                    chip = ChipChoice.Gpu,
                    runsOn = ChipFallback(DeviceKind.Cpu, DeviceKind.Gpu, "the GPU couldn't load it").line,
                ),
            ),
            ProcessorPreview(
                "A phone with no NPU",
                idle.copy(
                    chip = ChipChoice.Npu,
                    chips = setOf(DeviceKind.Gpu, DeviceKind.Cpu),
                    runsOn = ChipFallback(DeviceKind.Gpu, DeviceKind.Npu, "this phone has no NPU").line,
                ),
            ),
            ProcessorPreview(
                "CPU chosen while a reply is written",
                idle.copy(chip = ChipChoice.Cpu, runsOn = "Moves to the CPU after this reply."),
            ),
        )
}

@DemoScreenPreviews
@Composable
internal fun ProcessorContentPreview(
    @PreviewParameter(ProcessorStates::class) p: ProcessorPreview,
) {
    PreviewFrame { ProcessorContent(p.state, onChip = {}, onBack = {}) }
}

private val samplingRows =
    listOf(
        SamplingModelRow(HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"), "Qwen3.5 0.8B", 0),
        SamplingModelRow(HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M"), "Gemma 3 4B", 2),
    )

internal data class InferencePreview(val name: String, val state: InferenceState) {
    override fun toString() = name
}

internal class InferenceStates : PreviewParameterProvider<InferencePreview> {
    private val idle = InferenceState(ContextChoice.Automatic, null, ReplyLength.Automatic, samplingRows)
    override val values =
        sequenceOf(
            InferencePreview("Defaults, two models", idle),
            InferencePreview("Short replies", idle.copy(length = ReplyLength.Short)),
            InferencePreview(
                "8k chosen while a reply is written",
                idle.copy(context = ContextChoice.UpTo8k, contextLine = "Reloads at 8k after this reply."),
            ),
            InferencePreview(
                "32k, no limit, no models yet",
                InferenceState(ContextChoice.UpTo32k, null, ReplyLength.NoLimit, emptyList()),
            ),
        )
}

@DemoScreenPreviews
@Composable
internal fun InferenceContentPreview(
    @PreviewParameter(InferenceStates::class) p: InferencePreview,
) {
    PreviewFrame { InferenceContent(p.state, onContext = {}, onLength = {}, onOpen = {}, onBack = {}) }
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
internal fun InferenceModelPreview(
    @PreviewParameter(SamplingStates::class) p: SamplingPreview,
) {
    val layers = SamplingLayers(Catalog.byRepo(p.repo)?.sampling, p.own)
    PreviewFrame { InferenceModelContent(p.model, layers, onChange = { _, _ -> }, onReset = {}, onBack = {}) }
}

internal class VoiceSettingsStates : PreviewParameterProvider<VoiceSettingsState> {
    override val values =
        sequenceOf(
            VoiceSettingsState(Voices, voice = null, captionYou = true, phoneLanguage = PreviewLanguage),
            VoiceSettingsState(
                Voices,
                voice = "male",
                captionYou = false,
                dictationLanguage = "en",
                phoneLanguage = PreviewLanguage,
                turns = VoiceTurns.HandsFree,
                interruptByTalking = true,
            ),
            VoiceSettingsState(emptyList(), voice = null, captionYou = true, phoneLanguage = PreviewLanguage),
        )
}

/** The previews' phone language, so they don't follow the machine's. */
private val PreviewLanguage = PhoneLanguage("en", "English")

private val Voices = listOf("female", "male")

@DemoScreenPreviews
@Composable
internal fun VoiceSettingsPreview(
    @PreviewParameter(VoiceSettingsStates::class) state: VoiceSettingsState,
) {
    PreviewFrame { VoiceContent(state, onVoice = {}, onCaption = {}, onBack = {}) }
}
