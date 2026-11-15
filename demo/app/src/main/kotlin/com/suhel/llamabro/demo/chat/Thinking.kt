package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.chat.Reasoning
import com.suhel.llamabro.demo.data.ReplyLength
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.CatalogEntry

/** How a Think level reaches the model (demo design §4.4). */
object Thinking {
    /** Quick and Balanced cap the thinking; Deep doesn't (a design choice). */
    private const val QUICK_TOKENS = 512
    private const val BALANCED_TOKENS = 2_048

    /** The answer's share of a reply's length limit (D60): about 1,500 words, past any answer a chat needs. */
    private const val ANSWER_TOKENS = 2_048

    /** Deep has no thinking budget, so its limit is a fixed, generous one. */
    private const val DEEP_REPLY_TOKENS = 8_192

    /** Settings › Sampling's Short and Long reply lengths: about 750 and 6,000 words, thinking included. */
    private const val SHORT_REPLY_TOKENS = 1_024
    private const val LONG_REPLY_TOKENS = 8_192

    /** Whether a turn at [level] thinks; null is a model that can't. */
    fun thinks(level: ThinkLevel?): Boolean = level != null && level != ThinkLevel.Off

    /**
     * The whole reply's length limit at [level], thinking and answer together (D60, F113): the answer's share plus the
     * level's thinking budget. A small model that loops ends here, as "Stopped at the length limit", instead of
     * running until Stop; Continue picks the reply up again.
     */
    fun replyTokens(level: ThinkLevel?): Int =
        when (level) {
            null, ThinkLevel.Off -> ANSWER_TOKENS
            ThinkLevel.Quick -> ANSWER_TOKENS + QUICK_TOKENS
            ThinkLevel.Balanced -> ANSWER_TOKENS + BALANCED_TOKENS
            ThinkLevel.Deep -> DEEP_REPLY_TOKENS
        }

    /**
     * The reply's length limit under the [length] chosen in Settings › Sampling: [ReplyLength.Automatic] is the level's
     * own ([replyTokens]); Short and Long replace that total and leave the level's thinking budget as it is, so a short
     * reply can stop while it still thinks, and Continue goes on; null, for No limit, sends none: the reply runs until
     * the model stops or the context is full.
     */
    fun replyTokens(level: ThinkLevel?, length: ReplyLength): Int? =
        when (length) {
            ReplyLength.Automatic -> replyTokens(level)
            ReplyLength.Short -> SHORT_REPLY_TOKENS
            ReplyLength.Long -> LONG_REPLY_TOKENS
            ReplyLength.NoLimit -> null
        }

    /**
     * Whether the Think chip shows: the catalog says so, else the template reports reasoning, else it mentions
     * `enable_thinking` or `<think>` (F97).
     */
    fun canThink(info: ModelInfo, catalog: CatalogEntry?): Boolean {
        if (catalog != null) return catalog.thinks
        if (info.templateCaps["supports_reasoning_effort"] == true) return true
        val template = info.chatTemplate ?: return false
        return template.contains("enable_thinking") || template.contains("<think>")
    }

    /** The request's reasoning settings for [level]: Off turns thinking off; the others turn it on with a budget. */
    fun reasoning(level: ThinkLevel): Reasoning = Reasoning.Default.copy { configure(level, this) }

    /** Writes [level]'s settings into a request's reasoning builder. */
    fun configure(level: ThinkLevel, builder: Reasoning.Builder) {
        when (level) {
            ThinkLevel.Off -> {
                builder.enableThinking = false
            }

            ThinkLevel.Quick -> {
                builder.enableThinking = true
                builder.budgetTokens = QUICK_TOKENS
            }

            ThinkLevel.Balanced -> {
                builder.enableThinking = true
                builder.budgetTokens = BALANCED_TOKENS
            }

            ThinkLevel.Deep -> {
                builder.enableThinking = true
            }
        }
    }
}
