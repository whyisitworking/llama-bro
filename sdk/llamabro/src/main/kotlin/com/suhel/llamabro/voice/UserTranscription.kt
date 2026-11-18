package com.suhel.llamabro.voice

/**
 * How a voice session transcribes the user's turns ([VoiceConfig.Builder.transcribeUser], D99): the options of
 * `Model.transcribe`, apart from the session's own `language`, which is the reply's speech.
 */
public class UserTranscription internal constructor(
    /** The language the user speaks, e.g. `"fr"`; null lets the model tell. */
    public val language: String?,
    /** Words to expect, or the model's instruction in place of its own, as `Model.transcribe` takes it. */
    public val prompt: String?,
) {
    /** A copy with [configure] applied on top. */
    public fun copy(configure: Builder.() -> Unit): UserTranscription = Builder(this).apply(configure).build()

    override fun equals(other: Any?): Boolean =
        other is UserTranscription && other.language == language && other.prompt == prompt

    override fun hashCode(): Int = (language?.hashCode() ?: 0) * 31 + (prompt?.hashCode() ?: 0)

    override fun toString(): String = "UserTranscription(language=$language, prompt=$prompt)"

    /** Mutable scope of `transcribeUser { }`. */
    public class Builder internal constructor(from: UserTranscription) {
        public var language: String? = from.language
        public var prompt: String? = from.prompt

        internal fun build(): UserTranscription = UserTranscription(language, prompt)
    }

    public companion object {
        /** Nothing set: the model tells the language. */
        @JvmField
        public val Default: UserTranscription = UserTranscription(null, null)
    }
}
