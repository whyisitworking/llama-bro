package com.suhel.llamabro.sdk.internal

internal class TokenStreamParser(
    private val thinkingStart: String = DEFAULT_THINKING_START,
    private val thinkingEnd: String = DEFAULT_THINKING_END,
) {
    private val buffer = StringBuilder(maxOf(thinkingStart.length, thinkingEnd.length))

    var isThinking = false
        private set

    fun process(
        token: String,
        contentBuilder: StringBuilder,
        thinkingBuilder: StringBuilder
    ) {
        buffer.append(token)

        while (true) {
            if (!isThinking) {
                val startIdx = buffer.indexOf(thinkingStart)

                if (startIdx != -1) {
                    // 1. Found <think>. Everything before it is guaranteed Content.
                    if (startIdx > 0) contentBuilder.append(buffer, 0, startIdx)

                    isThinking = true
                    buffer.delete(0, startIdx + thinkingStart.length)
                    continue // Loop again to process the rest of the buffer in 'thinking' mode
                } else {
                    // 2. No <think> found. Check if the very end of the buffer is starting to form "<think>"
                    val partialLen = getPartialMatchLength(buffer, thinkingStart)
                    val safeLen = buffer.length - partialLen

                    if (safeLen > 0) {
                        contentBuilder.append(buffer, 0, safeLen)
                        buffer.delete(0, safeLen)
                    }
                    break // Stop looping and wait for the next token to arrive
                }
            } else {
                val endIdx = buffer.indexOf(thinkingEnd)

                if (endIdx != -1) {
                    // 1. Found </think>. Everything before it is guaranteed Thinking.
                    if (endIdx > 0) thinkingBuilder.append(buffer, 0, endIdx)

                    isThinking = false
                    buffer.delete(0, endIdx + thinkingEnd.length)
                    continue // Loop again to process the rest of the buffer in 'content' mode
                } else {
                    // 2. No </think> found. Check if the very end is starting to form "</think>"
                    val partialLen = getPartialMatchLength(buffer, thinkingEnd)
                    val safeLen = buffer.length - partialLen

                    if (safeLen > 0) {
                        thinkingBuilder.append(buffer, 0, safeLen)
                        buffer.delete(0, safeLen)
                    }
                    break // Stop looping and wait for the next token to arrive
                }
            }
        }
    }

    fun flush(contentBuilder: StringBuilder, thinkingBuilder: StringBuilder) {
        if (buffer.isNotEmpty()) {
            val dest = if (isThinking) thinkingBuilder else contentBuilder
            dest.append(buffer)
            buffer.clear()
        }
    }

    fun reset(startThinking: Boolean = false) {
        buffer.clear()
        isThinking = startThinking
    }

    /**
     * Custom primitive check. maxOverlap guarantees this loop runs a maximum of
     * 7 times (for a 8-char tag), making it mathematically O(1) in practice.
     */
    private fun getPartialMatchLength(sb: StringBuilder, pattern: String): Int {
        // -1 because a full match would have been caught by indexOf earlier
        val maxOverlap = minOf(sb.length, pattern.length - 1)
        if (maxOverlap == 0) return 0

        for (i in (sb.length - maxOverlap) until sb.length) {
            var isMatch = true
            for (j in 0 until (sb.length - i)) {
                if (sb[i + j] != pattern[j]) {
                    isMatch = false
                    break
                }
            }
            if (isMatch) return sb.length - i
        }
        return 0
    }

    companion object {
        private const val DEFAULT_THINKING_START = "<think>"
        private const val DEFAULT_THINKING_END = "</think>"
    }
}