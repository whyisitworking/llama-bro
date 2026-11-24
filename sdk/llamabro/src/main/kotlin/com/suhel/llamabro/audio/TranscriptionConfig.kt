package com.suhel.llamabro.audio

import com.suhel.llamabro.internal.JsonMergePatch

/**
 * How a transcription session is set up: the `session.update` upstream's client sends on `/v1/realtime` with
 * `intent=transcription` (`ui/lib/voice/src/realtime.ts`). Each field goes to the server only when set
 * ([D98](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d98)).
 *
 * [language] and [prompt] reach a session that transcribes per commit ([TranscriptionSession.isLive] false); a live
 * session (upstream's streaming ASR, `SERVER_TASK_TYPE_TRANSCRIBE_RT`) takes neither, as upstream reads neither there.
 */
public class TranscriptionConfig internal constructor(
    /** The language spoken, as an ISO 639-1 code such as `"en"`; null lets the model tell. */
    public val language: String?,
    /**
     * Upstream's `prompt` (#32507), as `transcribe` takes it: on most models it replaces the model's own instruction
     * (`asr_user_text`); only a model whose template takes context (VibeVoice-ASR) reads it as extra information.
     */
    public val prompt: String?,
    /** The rate of the PCM given to `TranscriptionSession.append`; null is the model's own. */
    public val inputSampleRate: Int?,
    /**
     * A JSON object applied to the `session` of the `session.update` last, as a JSON Merge Patch (RFC 7396): any
     * upstream session field the typed ones don't cover, as [VoiceConfig.extra] does for a conversation
     * ([D143](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d143)).
     *
     * Don't patch `audio.input.format`: it changes what the server expects, while `append` still sends 16-bit PCM.
     */
    public val extra: String?,
) {
    /** A copy with [configure] applied on top. */
    public fun copy(configure: Builder.() -> Unit): TranscriptionConfig = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean = other is TranscriptionConfig && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "TranscriptionConfig(language=$language, prompt=$prompt, inputSampleRate=$inputSampleRate, extra=$extra)"

    private fun parts() = listOf(language, prompt, inputSampleRate, extra)

    /** Mutable scope of `Model.transcriptionSession { }` and [copy]; it starts from the current config. */
    public class Builder internal constructor(from: TranscriptionConfig) {
        public var language: String? = from.language
        public var prompt: String? = from.prompt

        /** @throws IllegalArgumentException outside upstream's 8000 to 192000 Hz. */
        public var inputSampleRate: Int? = from.inputSampleRate
            set(value) {
                AudioLimits.requireRate(value)
                field = value
            }

        /**
         * See [TranscriptionConfig.extra]. Checked when set: it must be a JSON object, and may not set `type`, which
         * the session owns.
         * @throws IllegalArgumentException
         */
        public var extra: String? = from.extra
            set(value) {
                if (value != null) JsonMergePatch.checkPatch(value, OWNED)
                field = value
            }

        internal fun build(): TranscriptionConfig = TranscriptionConfig(language, prompt, inputSampleRate, extra)
    }

    public companion object {
        /** Nothing set: upstream's and the model's defaults. */
        public val Default: TranscriptionConfig = TranscriptionConfig(null, null, null, null)

        /** Session keys the SDK sets and `extra` may not touch. */
        internal val OWNED = setOf("type")
    }
}

/** `TranscriptionConfig { language = "en"; prompt = "Llama Bro, GGUF" }`. */
public fun TranscriptionConfig(configure: TranscriptionConfig.Builder.() -> Unit): TranscriptionConfig =
    TranscriptionConfig.Default.copy(configure)
