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
            val targetTag = if (isThinking) thinkingEnd else thinkingStart
            val tagIdx = buffer.indexOf(targetTag)

            if (tagIdx != -1) {
                // 1. Tag found! Route the text BEFORE the tag to the correct builder.
                val dest = if (isThinking) thinkingBuilder else contentBuilder

                // Using .append(CharSequence, start, end) copies the raw chars
                // without ever allocating a String on the heap.
                if (tagIdx > 0) dest.append(buffer, 0, tagIdx)

                isThinking = !isThinking
                buffer.delete(0, tagIdx + targetTag.length)
            } else {
                break
            }
        }

        // 2. No full tags left. Check if the very end of the buffer is a partial tag.
        val targetTag = if (isThinking) thinkingEnd else thinkingStart
        val partialLen = getPartialMatchLength(buffer, targetTag)

        // 3. Everything before the partial match is 100% safe to route to the builders.
        val safeLen = buffer.length - partialLen
        if (safeLen > 0) {
            val dest = if (isThinking) thinkingBuilder else contentBuilder
            dest.append(buffer, 0, safeLen)

            // Delete the safe text. The buffer now ONLY holds the partial match.
            buffer.delete(0, safeLen)
        }
    }

    fun flush(contentBuilder: StringBuilder, thinkingBuilder: StringBuilder) {
        if (buffer.isNotEmpty()) {
            val dest = if (isThinking) thinkingBuilder else contentBuilder
            dest.append(buffer)
            buffer.clear()
        }
    }

    fun reset() {
        buffer.clear()
        isThinking = false
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