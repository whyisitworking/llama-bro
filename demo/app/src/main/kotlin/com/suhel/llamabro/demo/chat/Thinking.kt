package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.chat.Reasoning
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel
import com.suhel.llamabro.demo.models.CatalogEntry

/** How a Think level reaches the model (pd-design 4.4). */
object Thinking {
    /** Quick and Balanced cap the thinking; Deep doesn't (builder's call, settled here). */
    private const val QUICK_TOKENS = 512
    private const val BALANCED_TOKENS = 2_048

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
