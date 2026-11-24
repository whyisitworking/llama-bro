package com.suhel.llamabro.audio

/**
 * What `Model.transcribe` heard: the [text], and whether it was cut at `maxTokens` before the clip's end
 * ([truncated]).
 */
public data class Transcript(public val text: String, public val truncated: Boolean = false)
