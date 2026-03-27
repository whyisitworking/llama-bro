package com.suhel.llamabro.sdk.engine.internal

/**
 * Internal data class returned from native [beginCompletion] JNI call.
 * Contains template metadata and cache statistics for the current completion.
 *
 * Constructed by JNI — field names must match the native constructor call.
 */
class NativeCompletionInfo(
    val generationPrompt: String?,
    val supportsThinking: Boolean,
    val thinkingStartTag: String?,
    val thinkingEndTag: String?,
    val tokensCached: Int,
    val tokensIngested: Int,
)
