package com.suhel.llamabro.sdk.engine.internal

/**
 * Chat template metadata returned from native llama.cpp template initialization.
 * Constructed by JNI — field names must match the native constructor call.
 */
class NativeChatTemplateInfo(
    val supportsThinking: Boolean,
    val thinkingStartTag: String?,
    val thinkingEndTag: String?,
)
