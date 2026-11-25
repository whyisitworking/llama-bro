package com.suhel.llamabro.hub

/**
 * What an installed model can do, read from its GGUF headers on the phone before anything loads
 * ([D155](https://github.com/whyisitworking/llama-bro/blob/main/docs/decisions.md#d155)). A load's `ModelInfo`
 * is the final word; this is the earlier, offline guess.
 */
public class ModelCapabilities internal constructor(
    /** The GGUF's `tokenizer.chat_template`, null when it has none. */
    public val chatTemplate: String?,
    /** An installed projector declares `clip.has_vision_encoder`. */
    public val takesImages: Boolean,
    /** An installed projector declares `clip.has_audio_encoder`. */
    public val takesAudio: Boolean,
) {
    // the template by length only: it is long and not for logs
    override fun toString(): String =
        "ModelCapabilities(chatTemplate=${chatTemplate?.let { "${it.length} chars" }}, takesImages=$takesImages, " +
            "takesAudio=$takesAudio)"
}
