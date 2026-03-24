package com.suhel.llamabro.sdk.chat.pipeline

/**
 * An allocation-conscious DFA scanner that processes a stream of strings
 * extracting distinct tags and text.
 *
 * Designed to minimize GC overhead by tracking a single running StringBuilder
 * and yielding explicit allocations ONLY upon confirmed semantic token emission.
 */
internal class AllocationOptimizedScanner(
    private val delimiters: List<TagDelimiter>
) {
    private val buffer = StringBuilder()
    private var activeDelimiter: TagDelimiter? = null

    /**
     * Feeds a raw token into the scanner and returns any definitively parsed events.
     * @param token The string chunk from the LLM, or `null` to signal the end of the stream.
     */
    fun feed(token: String?): List<LexerEvent> {
        if (token != null) {
            buffer.append(token)
        }

        val events = mutableListOf<LexerEvent>()

        while (buffer.isNotEmpty()) {
            if (activeDelimiter == null) {
                var nearestDelimiter: TagDelimiter? = null
                var nearestFullIdx = -1
                var nearestPartialIdx = -1

                for (delimiter in delimiters) {
                    val fullIdx = buffer.indexOf(delimiter.open)
                    if (fullIdx != -1) {
                        if (nearestFullIdx == -1 || fullIdx < nearestFullIdx) {
                            nearestFullIdx = fullIdx
                            nearestDelimiter = delimiter
                        }
                    } else {
                        val partialIdx = findPartialMatch(buffer, delimiter.open)
                        if (partialIdx != -1) {
                            if (nearestPartialIdx == -1 || partialIdx < nearestPartialIdx) {
                                nearestPartialIdx = partialIdx
                            }
                        }
                    }
                }

                if (nearestDelimiter != null) {
                    if (nearestFullIdx > 0) {
                        // Flush preceding pure text
                        events += LexerEvent.Text(buffer.substring(0, nearestFullIdx))
                    }
                    events += LexerEvent.TagOpened(nearestDelimiter)
                    activeDelimiter = nearestDelimiter
                    buffer.delete(0, nearestFullIdx + nearestDelimiter.open.length)
                } else if (nearestPartialIdx != -1) {
                    if (nearestPartialIdx > 0) {
                        events += LexerEvent.Text(buffer.substring(0, nearestPartialIdx))
                        buffer.delete(0, nearestPartialIdx)
                    }
                    // Wait for more tokens to complete or reject the partial tag boundary
                    break
                } else {
                    // Entire buffer is pure text. Flush completely.
                    events += LexerEvent.Text(buffer.toString())
                    buffer.clear()
                    break
                }
            } else {
                val current = activeDelimiter!!
                val closingTag = current.close
                val fullIdx = buffer.indexOf(closingTag)

                if (fullIdx != -1) {
                    if (fullIdx > 0) {
                        events += LexerEvent.TagContent(current, buffer.substring(0, fullIdx))
                    }
                    events += LexerEvent.TagClosed(current)
                    activeDelimiter = null
                    buffer.delete(0, fullIdx + closingTag.length)
                } else {
                    val partialIdx = findPartialMatch(buffer, closingTag)
                    if (partialIdx != -1) {
                        if (partialIdx > 0) {
                            events += LexerEvent.TagContent(current, buffer.substring(0, partialIdx))
                            buffer.delete(0, partialIdx)
                        }
                        // Stop and safely wait for the remainder of the closing tag across future feeds
                        break
                    } else {
                        // Safely flush entire tag content
                        events += LexerEvent.TagContent(current, buffer.toString())
                        buffer.clear()
                        break
                    }
                }
            }
        }

        return events
    }

    /**
     * Quickly identifies if the buffer's tail hosts a prefix of the target tag.
     */
    private fun findPartialMatch(buffer: CharSequence, tag: String): Int {
        val searchStart = maxOf(0, buffer.length - tag.length + 1)
        for (i in searchStart until buffer.length) {
            var match = true
            for (j in i until buffer.length) {
                if (buffer[j] != tag[j - i]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
