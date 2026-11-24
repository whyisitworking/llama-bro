package com.suhel.llamabro.audio

/**
 * Upstream's limits on a realtime session's input rate (server-realtime.cpp on_session_update, server-context.cpp
 * ws_realtime), checked once for both session configs.
 */
internal object AudioLimits {
    const val MIN_RATE = 8000
    const val MAX_RATE = 192000

    /** @throws IllegalArgumentException outside [MIN_RATE] to [MAX_RATE] Hz; null is the model's own rate. */
    fun requireRate(value: Int?) {
        require(value == null || value in MIN_RATE..MAX_RATE) {
            "inputSampleRate must be between $MIN_RATE and $MAX_RATE Hz"
        }
    }
}
