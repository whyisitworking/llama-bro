package com.suhel.llamabro.voice

/**
 * How a transcription session is set up: the `session.update` upstream's client sends on `/v1/realtime` with
 * `intent=transcription` (`ui/lib/voice/src/realtime.ts`). Each field goes to the server only when set (D98).
 *
 * [language] and [prompt] reach a session that transcribes per commit ([TranscriptionSession.live] false); a live
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
) {
    /** A copy with [configure] applied on top. */
    public fun copy(configure: Builder.() -> Unit): TranscriptionConfig = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean =
        other is TranscriptionConfig &&
            language == other.language &&
            prompt == other.prompt &&
            inputSampleRate == other.inputSampleRate

    override fun hashCode(): Int = listOf(language, prompt, inputSampleRate).hashCode()

    override fun toString(): String =
        "TranscriptionConfig(language=$language, prompt=$prompt, inputSampleRate=$inputSampleRate)"

    /** Mutable scope of `Model.transcriptionSession { }` and [copy]; it starts from the current config. */
    public class Builder internal constructor(from: TranscriptionConfig) {
        public var language: String? = from.language
        public var prompt: String? = from.prompt

        /** @throws IllegalArgumentException outside upstream's 8000 to 192000 Hz. */
        public var inputSampleRate: Int? = from.inputSampleRate
            set(value) {
                require(value == null || value in MIN_RATE..MAX_RATE) {
                    "inputSampleRate must be between $MIN_RATE and $MAX_RATE Hz"
                }
                field = value
            }

        internal fun build(): TranscriptionConfig = TranscriptionConfig(language, prompt, inputSampleRate)
    }

    public companion object {
        /** Nothing set: upstream's and the model's defaults. */
        public val Default: TranscriptionConfig = TranscriptionConfig(null, null, null)

        // upstream's limits on the input rate (server-context.cpp ws_realtime, server-realtime.cpp)
        private const val MIN_RATE = 8000
        private const val MAX_RATE = 192000
    }
}

/** `TranscriptionConfig { language = "en"; prompt = "Llama Bro, GGUF" }`. */
public fun TranscriptionConfig(configure: TranscriptionConfig.Builder.() -> Unit): TranscriptionConfig =
    TranscriptionConfig.Default.copy(configure)
