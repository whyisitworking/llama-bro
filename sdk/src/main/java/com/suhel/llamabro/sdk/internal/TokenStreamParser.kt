package com.suhel.llamabro.sdk.internal

/**
 * Internal parser that processes a stream of raw tokens from the LLM.
 *
 * It is responsible for:
 * 1. Identifying and extracting "thinking" blocks (e.g., `<think>...</think>`).
 * 2. Detecting the assistant's stop sequence (e.g., `<|im_end|>`) to halt generation.
 * 3. Handling partial matches at token boundaries to ensure tags are not missed.
 */
internal class TokenStreamParser(
    private val stopSuffix: String?,
    private val openTag: String = THINKING_START,
    private val closeTag: String = THINKING_END,
) {
    private var buffer = ""
    private var isThinking = false

    /** 
     * Processes a new token and returns a list of [StreamAction]s. 
     * This handles buffering of partial matches.
     */
    fun process(token: String): List<StreamAction> = buildList {
        buffer += token

        while (buffer.isNotEmpty()) {
            val stopIdx = if (!stopSuffix.isNullOrEmpty()) buffer.indexOf(stopSuffix) else -1
            val openIdx = buffer.indexOf(openTag)
            val closeIdx = buffer.indexOf(closeTag)

            // 1. Intercept Stop Suffix first
            if (stopIdx != -1) {
                val before = buffer.substring(0, stopIdx)
                if (before.isNotEmpty()) {
                    add(
                        if (isThinking) {
                            StreamAction.Thinking(before)
                        } else {
                            StreamAction.Content(before)
                        }
                    )
                }
                add(StreamAction.Stop)
                buffer = ""
                break
            }

            // 2. Intercept Open Tag
            if (!isThinking && openIdx != -1) {
                val before = buffer.substring(0, openIdx)
                if (before.isNotEmpty()) {
                    add(StreamAction.Content(before))
                }

                isThinking = true
                buffer = buffer.substring(openIdx + openTag.length)
                continue
            }

            // 3. Intercept Close Tag
            if (isThinking && closeIdx != -1) {
                val before = buffer.substring(0, closeIdx)
                if (before.isNotEmpty()) {
                    add(StreamAction.Thinking(before))
                }

                isThinking = false
                buffer = buffer.substring(closeIdx + closeTag.length)
                continue
            }

            // 4. Hold buffer if it ends with a partial match of any target
            if (hasPartialMatch(buffer, stopSuffix) ||
                (!isThinking && hasPartialMatch(buffer, openTag)) ||
                (isThinking && hasPartialMatch(buffer, closeTag))
            ) {
                break
            }

            // 5. Safe to flush the verified text
            add(
                if (isThinking) {
                    StreamAction.Thinking(buffer)
                } else {
                    StreamAction.Content(buffer)
                }
            )
            buffer = ""
        }
    }

    /** Flushes any remaining text in the buffer as a final action. */
    fun flush(): List<StreamAction> =
        if (buffer.isNotEmpty()) {
            val action = if (isThinking) {
                StreamAction.Thinking(buffer)
            } else {
                StreamAction.Content(buffer)
            }

            buffer = ""
            listOf(action)
        } else {
            listOf()
        }

    /** Checks if the end of [text] matches the beginning of [target]. */
    private fun hasPartialMatch(text: String, target: String?): Boolean {
        if (target.isNullOrEmpty() || text.isEmpty()) {
            return false
        }

        val start = maxOf(0, text.length - target.length + 1)
        for (i in start until text.length) {
            if (target.startsWith(text.substring(i))) {
                return true
            }
        }

        return false
    }

    companion object {
        private const val THINKING_START = "<think>"
        private const val THINKING_END = "</think>"
    }
}
