package com.suhel.llamabro.audio

import com.suhel.llamabro.internal.JsonMergePatch

/**
 * How `Model.transcribe` transcribes: upstream's transcription request (`POST /v1/audio/transcriptions`), which
 * llama-server runs as a chat request. Each field goes to the server only when set, so an unset one keeps upstream's
 * and the model's default ([D98](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d98)). The
 * one-shot call takes a builder like the rest of the audio family
 * ([D143](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d143)).
 *
 * Besides the sampling fields a chat request has (`top_k`, `top_p`, …), `stop`, the reasoning fields
 * (`reasoning_format`, the reasoning budget) and the chat-template fields (`chat_template_kwargs`, e.g.
 * `enable_thinking`) go through [extra]: `extra = """{"reasoning_format":"none"}"""`.
 */
public class TranscribeConfig internal constructor(
    /** The language spoken (e.g. `"fr"`), as upstream's `language`; null lets the model tell. */
    public val language: String?,
    /**
     * Upstream's `prompt`: words to expect, or the model's instruction in place of its own (`asr_user_text`); only a
     * model whose template takes context (VibeVoice-ASR) reads it as extra information.
     */
    public val prompt: String?,
    /** The most tokens to transcribe; a transcript cut there is [Transcript.truncated]. */
    public val maxTokens: Int?,
    /** The sampling temperature, 0 for greedy. */
    public val temperature: Float?,
    /** The seed of the sampler, for a transcript that repeats exactly. */
    public val seed: Long?,
    /**
     * A JSON object applied to the request body last, as a JSON Merge Patch (RFC 7396): any field the typed ones don't
     * cover, such as `top_k` or `top_p`.
     */
    public val extra: String?,
) {
    /** A copy with [configure] applied on top. */
    public fun copy(configure: Builder.() -> Unit): TranscribeConfig = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean = other is TranscribeConfig && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "TranscribeConfig(language=$language, prompt=$prompt, maxTokens=$maxTokens, temperature=$temperature, " +
            "seed=$seed, extra=$extra)"

    private fun parts() = listOf(language, prompt, maxTokens, temperature, seed, extra)

    /** Mutable scope of `Model.transcribe(source) { }` and [copy]; it starts from the current config. */
    public class Builder internal constructor(from: TranscribeConfig) {
        /** See [TranscribeConfig.language]. */
        public var language: String? = from.language

        /** See [TranscribeConfig.prompt]. */
        public var prompt: String? = from.prompt

        /** @throws IllegalArgumentException when set below 1. */
        public var maxTokens: Int? = from.maxTokens
            set(value) {
                require(value == null || value > 0) { "maxTokens must be at least 1" }
                field = value
            }

        /** See [TranscribeConfig.temperature]. */
        public var temperature: Float? = from.temperature

        /** See [TranscribeConfig.seed]. */
        public var seed: Long? = from.seed

        /**
         * See [TranscribeConfig.extra]. Checked when set: it must be a JSON object, and may not set the fields the SDK
         * owns (`response_format`, `stream`, `stream_options`, `return_progress`).
         * @throws IllegalArgumentException
         */
        public var extra: String? = from.extra
            set(value) {
                if (value != null) JsonMergePatch.checkPatch(value, OWNED)
                field = value
            }

        internal fun build(): TranscribeConfig = TranscribeConfig(language, prompt, maxTokens, temperature, seed, extra)
    }

    /** The defaults, and the key set `extra` may not touch. */
    public companion object {
        /** Nothing set: upstream's and the model's defaults. */
        public val Default: TranscribeConfig = TranscribeConfig(null, null, null, null, null, null)

        /** Body keys the SDK sets and `extra` may not touch. */
        internal val OWNED = setOf("response_format", "stream", "stream_options", "return_progress")
    }
}

/** `TranscribeConfig { language = "fr"; maxTokens = 256 }`. */
public fun TranscribeConfig(configure: TranscribeConfig.Builder.() -> Unit): TranscribeConfig =
    TranscribeConfig.Default.copy(configure)
