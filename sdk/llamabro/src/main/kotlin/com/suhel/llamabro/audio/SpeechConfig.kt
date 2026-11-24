package com.suhel.llamabro.audio

import com.suhel.llamabro.MediaSource
import com.suhel.llamabro.internal.JsonMergePatch

/**
 * How `Model.speak` speaks: upstream's speech request (`POST /v1/audio/speech`, `handle_tts_impl`). Each field goes to
 * the server only when set, so an unset one keeps upstream's and the model's default
 * ([D98](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d98)). A model that doesn't take a
 * setting, such as [speed] on LFM3, refuses the request with upstream's message. Values are not range-checked here:
 * upstream checks what a model takes (a negative [temperature], a [speed] of 0), as it does for a chat's sampling
 * ([D143](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d143)).
 */
public class SpeechConfig internal constructor(
    /** One of the model's voices ([com.suhel.llamabro.ModelInfo.voices]); null speaks with the model's first. */
    public val voice: String?,
    /** A clip of the voice to speak with (WAV, MP3 or FLAC), for a model that clones one: upstream's `speaker_ref`. */
    public val speakerReference: MediaSource?,
    /** What [speakerReference] says, for a model that takes it (Qwen3-TTS reference mode): `speaker_ref_text`. */
    public val speakerReferenceText: String?,
    /** The language to speak, e.g. `"en"`, for a model whose template takes it (upstream's `lang`). */
    public val language: String?,
    /** Speaking speed against the model's typical pace (1.0), for a model that supports it (Kokoro). */
    public val speed: Float?,
    /** The seed of the sampler and the vocoder, for speech that repeats exactly. */
    public val seed: Long?,
    /** The sampling temperature, 0 for greedy. */
    public val temperature: Float?,
    /** The most audio frames to make (`max_tokens`): a bound on speech that runs on. */
    public val maxFrames: Int?,
    /**
     * A JSON object applied to the request body last, as a JSON Merge Patch (RFC 7396): any field the typed ones don't
     * cover, such as `loudness`, `cfg_scale` or `flow_steps`.
     */
    public val extra: String?,
) {
    /** A copy with [configure] applied on top. */
    public fun copy(configure: Builder.() -> Unit): SpeechConfig = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean = other is SpeechConfig && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "SpeechConfig(voice=$voice, speakerReference=$speakerReference, speakerReferenceText=$speakerReferenceText, " +
            "language=$language, speed=$speed, seed=$seed, temperature=$temperature, maxFrames=$maxFrames, " +
            "extra=$extra)"

    private fun parts() =
        listOf(voice, speakerReference, speakerReferenceText, language, speed, seed, temperature, maxFrames, extra)

    /** Mutable scope of `Model.speak(text) { }` and [copy]; it starts from the current config. */
    public class Builder internal constructor(from: SpeechConfig) {
        public var voice: String? = from.voice
        public var speakerReference: MediaSource? = from.speakerReference
        public var speakerReferenceText: String? = from.speakerReferenceText
        public var language: String? = from.language

        public var speed: Float? = from.speed

        public var seed: Long? = from.seed

        public var temperature: Float? = from.temperature

        /** @throws IllegalArgumentException when set below 1. */
        public var maxFrames: Int? = from.maxFrames
            set(value) {
                require(value == null || value > 0) { "maxFrames must be at least 1" }
                field = value
            }

        /**
         * See [SpeechConfig.extra]. Checked when set: it must be a JSON object, and may not set the fields the SDK
         * owns (`input`, `response_format`, `stream_format`, `stream`).
         * @throws IllegalArgumentException
         */
        public var extra: String? = from.extra
            set(value) {
                if (value != null) JsonMergePatch.checkPatch(value, OWNED)
                field = value
            }

        internal fun build(): SpeechConfig =
            SpeechConfig(
                voice,
                speakerReference,
                speakerReferenceText,
                language,
                speed,
                seed,
                temperature,
                maxFrames,
                extra,
            )
    }

    public companion object {
        /** Nothing set: upstream's and the model's defaults. */
        public val Default: SpeechConfig = SpeechConfig(null, null, null, null, null, null, null, null, null)

        /** Body keys the SDK sets and `extra` may not touch. */
        internal val OWNED = setOf("input", "response_format", "stream_format", "stream")
    }
}

/** `SpeechConfig { voice = "af_heart"; speed = 1.1f }`. */
public fun SpeechConfig(configure: SpeechConfig.Builder.() -> Unit): SpeechConfig = SpeechConfig.Default.copy(configure)

/**
 * A piece of speech as it is made: [pcm] is 16-bit PCM at [sampleRate], [channels] interleaved (mono for every
 * speech model so far), ready for `AudioTrack.write(short[])`.
 */
public class SpeechAudio internal constructor(
    public val sampleRate: Int,
    public val channels: Int,
    public val pcm: ShortArray,
) {
    // not a data class: a data class compares arrays by reference
    override fun equals(other: Any?): Boolean =
        other is SpeechAudio &&
            other.sampleRate == sampleRate &&
            other.channels == channels &&
            other.pcm.contentEquals(pcm)

    override fun hashCode(): Int = (sampleRate * 31 + channels) * 31 + pcm.contentHashCode()

    override fun toString(): String = "SpeechAudio(sampleRate=$sampleRate, channels=$channels, samples=${pcm.size})"
}
