package com.suhel.llamabro.sdk.chat.pipeline

sealed interface FeatureMarker {
    val open: String
    val close: String
}

data class ThinkingMarker(override val open: String, override val close: String) : FeatureMarker
data class ToolCallMarker(override val open: String, override val close: String) : FeatureMarker
