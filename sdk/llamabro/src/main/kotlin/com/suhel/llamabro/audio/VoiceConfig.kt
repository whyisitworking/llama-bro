package com.suhel.llamabro.audio

import com.suhel.llamabro.internal.JsonMergePatch

/**
 * How a voice session is set up: upstream's `session.update` (`/v1/realtime`,
 * [D95](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d95)). Each field goes to the server
 * only when set, so an unset one keeps upstream's default; the SDK picks none of its own
 * ([D98](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d98)).
 *
 * LFM3-Omni needs [voice], [language] and an instruction to answer in [instructions]: without them, a reply in
 * text and speech can speak noise for minutes, speak another language, or read the question back
 * ([F166](https://github.com/whyisitworking/llama-bro/blob/main/docs/research/findings.md#f166)). A cap on
 * [maxOutputTokens] bounds a reply that runs on.
 */
public class VoiceConfig internal constructor(
    /** A voice from the model's list (upstream's `voices` in `GET /props`), e.g. `"female"`. */
    public val voice: String?,
    /** The language to speak, e.g. `"en"`. */
    public val language: String?,
    /** The session's system instructions; fixed once the first audio or message has gone in. */
    public val instructions: String?,
    /**
     * What a reply is made of: text and audio by default, the captioned mode of
     * [D95](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d95). Never empty.
     */
    public val outputModalities: Set<OutputModality>,
    /** The longest reply in tokens; null leaves upstream's `"inf"`. */
    public val maxOutputTokens: Int?,
    /** The rate of the PCM given to `VoiceSession.append`; null is the model's own (16 kHz for LFM3). */
    public val inputSampleRate: Int?,
    /**
     * A JSON object applied to the `session` of the `session.update` last, as a JSON Merge Patch (RFC 7396): any
     * upstream session field the typed ones don't cover, such as `tools`.
     *
     * Don't patch `audio.input.format`: it changes what the server expects, while `append` still sends 16-bit PCM.
     */
    public val extra: String?,
) {
    /** A copy with [configure] applied on top. */
    public fun copy(configure: Builder.() -> Unit): VoiceConfig = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean = other is VoiceConfig && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "VoiceConfig(voice=$voice, language=$language, instructions=$instructions, " +
            "outputModalities=$outputModalities, maxOutputTokens=$maxOutputTokens, " +
            "inputSampleRate=$inputSampleRate, extra=$extra)"

    private fun parts() =
        listOf(
            voice,
            language,
            instructions,
            outputModalities,
            maxOutputTokens,
            inputSampleRate,
            extra,
        )

    /** Mutable scope of `Model.voiceSession { }` and [copy]; it starts from the current config. */
    public class Builder internal constructor(from: VoiceConfig) {
        /** One of [com.suhel.llamabro.ModelInfo.voices]: each model names its own; another one is refused at once. */
        public var voice: String? = from.voice

        /** The language the reply is spoken in, as an ISO 639-1 code such as `"en"` or `"fr"`. */
        public var language: String? = from.language
        public var instructions: String? = from.instructions

        /** @throws IllegalArgumentException when set empty. */
        public var outputModalities: Set<OutputModality> = from.outputModalities
            set(value) {
                require(value.isNotEmpty()) { "outputModalities must not be empty" }
                field = value.toSet()
            }

        /** @throws IllegalArgumentException when set below 1. */
        public var maxOutputTokens: Int? = from.maxOutputTokens
            set(value) {
                require(value == null || value > 0) { "maxOutputTokens must be at least 1" }
                field = value
            }

        /** @throws IllegalArgumentException outside upstream's 8000 to 192000 Hz. */
        public var inputSampleRate: Int? = from.inputSampleRate
            set(value) {
                AudioLimits.requireRate(value)
                field = value
            }

        /**
         * See [VoiceConfig.extra]. Checked when set: it must be a JSON object, and may not set `type`, which the
         * session owns.
         * @throws IllegalArgumentException
         */
        public var extra: String? = from.extra
            set(value) {
                if (value != null) JsonMergePatch.checkPatch(value, OWNED)
                field = value
            }

        internal fun build(): VoiceConfig =
            VoiceConfig(
                voice,
                language,
                instructions,
                outputModalities,
                maxOutputTokens,
                inputSampleRate,
                extra,
            )
    }

    public companion object {
        /** Nothing set but the captioned output: upstream's and the model's defaults. */
        public val Default: VoiceConfig =
            VoiceConfig(null, null, null, setOf(OutputModality.Text, OutputModality.Audio), null, null, null)

        /** Session keys the SDK sets and `extra` may not touch. */
        internal val OWNED = setOf("type")
    }
}

/** `VoiceConfig { voice = "female"; language = "en" }`. */
public fun VoiceConfig(configure: VoiceConfig.Builder.() -> Unit): VoiceConfig = VoiceConfig.Default.copy(configure)

/** What a reply is made of (upstream's `output_modalities`). */
public enum class OutputModality {
    /** The reply's text: [VoiceEvent.ReplyText], the captions when audio is on too. */
    Text,

    /** The reply's speech: [VoiceEvent.ReplyAudio]. */
    Audio,
}
