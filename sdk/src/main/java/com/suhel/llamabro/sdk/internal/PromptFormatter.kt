package com.suhel.llamabro.sdk.internal

import com.suhel.llamabro.sdk.model.Message
import com.suhel.llamabro.sdk.model.PromptFormat

/**
 * Internal utility to wrap raw message text into model-specific chat templates.
 *
 * This class ensures that System, User, and Assistant messages are prefixed
 * and suffixed correctly according to the [PromptFormat] provided by the model.
 *
 * The assistant turn has an explicit lifecycle:
 * - [assistantStart] — opens the turn (prefix only, used before generation).
 * - [assistantEnd]   — closes the turn (suffix + eos, used after generation).
 * - [assistant]       — formats a complete turn (prefix + content + suffix + eos,
 *                       used for history loading).
 */
internal class PromptFormatter(private val fmt: PromptFormat) {
    /** Beginning of stream token. */
    fun bos(): String = fmt.bos.orEmpty()

    /** End of stream token. */
    fun eos(): String = fmt.eos.orEmpty()

    /**
     * Whether the tokenizer should prepend the model's native BOS token.
     *
     * When the [PromptFormat] supplies an explicit [PromptFormat.bos] string,
     * we embed it ourselves in [system] and tell the tokenizer NOT to add another.
     * When it is null, we let the tokenizer handle BOS automatically.
     */
    fun shouldAddSpecial(): Boolean = fmt.bos == null

    /** Formats a single user message (complete turn). */
    fun user(text: String): String =
        "${fmt.userPrefix}$text${fmt.userSuffix}"

    /** Formats a single system instruction (complete turn with BOS). */
    fun system(text: String): String =
        "${bos()}${fmt.systemPrefix}$text${fmt.systemSuffix}"

    /** Returns the assistant turn opening prefix (injected before generation). */
    fun assistantStart(): String = fmt.assistantPrefix

    /** Returns the assistant turn closing tokens (injected after generation). */
    fun assistantEnd(): String = "${fmt.assistantSuffix}${eos()}"

    /** Formats a complete assistant message (used for history loading). */
    fun assistant(text: String, thinking: String? = null): String {
        val thinkingBlock = if (!thinking.isNullOrBlank()) {
            "${fmt.thinkStart}$thinking${fmt.thinkEnd}"
        } else ""

        return "${assistantStart()}$thinkingBlock$text${assistantEnd()}"
    }

    /** Dispatches formatting based on the message role. */
    fun format(message: Message): String =
        when (message) {
            is Message.User -> user(message.content)
            is Message.Assistant -> assistant(message.content, message.thinking)
        }
}
