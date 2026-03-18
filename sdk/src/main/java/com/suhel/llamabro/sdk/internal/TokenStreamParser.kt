package com.suhel.llamabro.sdk.internal

internal class TokenStreamParser(
    private val thinkingStart: String = DEFAULT_THINKING_START,
    private val thinkingEnd: String = DEFAULT_THINKING_END,
    private val stopStrings: List<String> = emptyList(),
) {
    private val buffer = StringBuilder(
        maxOf(
            thinkingStart.length,
            thinkingEnd.length,
            stopStrings.maxOfOrNull { it.length } ?: 0,
        )
    )

    var isThinking = false
        private set

    /** True once a stop string has been matched. Further [process] calls are no-ops. */
    var isStopped = false
        private set

    fun process(
        token: String,
        contentBuilder: StringBuilder,
        thinkingBuilder: StringBuilder,
    ) {
        if (isStopped) return
        buffer.append(token)

        while (true) {
            if (!isThinking) {
                val tagIdx = buffer.indexOf(thinkingStart)
                val stopMatch = findEarliestStop()

                when {
                    // Stop string fires at or before the think tag — stop wins on a tie.
                    stopMatch != null && (tagIdx == -1 || stopMatch.first <= tagIdx) -> {
                        if (stopMatch.first > 0) contentBuilder.append(buffer, 0, stopMatch.first)
                        buffer.clear()
                        isStopped = true
                        return
                    }

                    // Think-start found with no earlier stop string.
                    tagIdx != -1 -> {
                        if (tagIdx > 0) contentBuilder.append(buffer, 0, tagIdx)
                        isThinking = true
                        buffer.delete(0, tagIdx + thinkingStart.length)
                        continue
                    }

                    // No full match — hold back as much as could be the start of any pattern.
                    else -> {
                        val hold = maxOf(
                            getPartialMatchLength(buffer, thinkingStart),
                            stopStrings.maxOfOrNull { getPartialMatchLength(buffer, it) } ?: 0,
                        )
                        val safe = buffer.length - hold
                        if (safe > 0) {
                            contentBuilder.append(buffer, 0, safe)
                            buffer.delete(0, safe)
                        }
                        break
                    }
                }
            } else {
                // In thinking mode: only watch for the closing tag.
                val endIdx = buffer.indexOf(thinkingEnd)
                if (endIdx != -1) {
                    if (endIdx > 0) thinkingBuilder.append(buffer, 0, endIdx)
                    isThinking = false
                    buffer.delete(0, endIdx + thinkingEnd.length)
                    continue
                } else {
                    val hold = getPartialMatchLength(buffer, thinkingEnd)
                    val safe = buffer.length - hold
                    if (safe > 0) {
                        thinkingBuilder.append(buffer, 0, safe)
                        buffer.delete(0, safe)
                    }
                    break
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
        isStopped = false
    }

    /** Returns the (startIndex, length) of the earliest stop-string match, or null. */
    private fun findEarliestStop(): Pair<Int, Int>? {
        if (stopStrings.isEmpty()) return null
        var result: Pair<Int, Int>? = null
        for (ss in stopStrings) {
            val idx = buffer.indexOf(ss)
            if (idx != -1 && (result == null || idx < result.first)) {
                result = idx to ss.length
            }
        }
        return result
    }

    /**
     * Returns how many characters at the END of [sb] could be the beginning of [pattern].
     * Bounded by pattern.length - 1, so it is O(pattern.length) in the worst case.
     */
    private fun getPartialMatchLength(sb: StringBuilder, pattern: String): Int {
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
